package mx.vicko.permadeath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bloque 1 — Integración de temporada (propietario único del código existente).
 *
 * <p>Conecta {@link SeasonCalendar}, {@link SeasonRoster}, {@link SeasonState},
 * {@link SeasonStore} y {@link SeasonObjectives} con {@link DeathRegistry} y
 * {@link DayManager}. El día real lo sigue dando {@link DayManager}; aquí solo
 * se comprime (calendario), se calcula N/M del roster y se persiste el progreso
 * en {@code season-state.yml} (nunca {@code config.yml} como BD).
 *
 * <p>Sin defaults destructivos: si falta {@code season.id} se usa
 * {@code temporada-1} con aviso; si falta la lista {@code season.roster} se usa
 * el fallback numérico solo para cálculos (sin inventar UUID ni enrolar a
 * nadie) y el enforcement de login queda desactivado con aviso visible.
 */
public final class SeasonService {
    public static final String DEFAULT_SEASON_ID = "temporada-1";

    private final PermadeathPlugin plugin;
    private final SeasonStore store;
    private SeasonState state;
    private SeasonRoster roster;
    private SeasonObjectives objectives;
    private SeasonObjectives.Listener bridge;

    private String seasonId = DEFAULT_SEASON_ID;
    private int seasonLengthDays = SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS;
    private int gatePercent = SeasonRoster.DEFAULT_GATE_PERCENT;
    private int rosterFallback = SeasonRoster.FALLBACK_ROSTER_SIZE;
    private boolean enforceRoster = true;

    private int lastDay = -1;
    private boolean sleptToday;
    private int lastNightEvaluated = -1;
    private BukkitTask dayTask;
    /** Sello anti-perdida: corrupto/ilegible bloquea todo save hasta reload correcto. */
    private final SeasonStore.WriteGate writeGate = new SeasonStore.WriteGate();

    public SeasonService(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.store = new SeasonStore(plugin.getDataFolder().toPath());
    }

    /** Arranca: lee config, carga estado, construye roster/objetivos y programa el borde de día. */
    public void start() {
        loadFromConfig();
        int day = currentDay();
        state.setDay(day);
        int act = SeasonCalendar.actForDay(day, seasonLengthDays);
        objectives.onActChanged(act);
        if (state.markActAnnounced(act)) {
            announceAct(act);
        }
        saveState();
        lastDay = day;
        if (dayTask == null) {
            dayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDayRollover, 1200L, 1200L);
        }
    }

    /** Relee config en caliente (null guards: nunca rompe el estado si algo falta). */
    public void reload() {
        try {
            loadFromConfig();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] reload con config incompleta, se conserva el estado: "
                    + exception.getMessage());
        }
    }

    /** Cancela el timer de borde de día y guarda el progreso (nunca lo borra). */
    public void shutdown() {
        if (dayTask != null) {
            dayTask.cancel();
            dayTask = null;
        }
        saveState();
    }

    // ------------------------------------------------------------------ config

    private void loadFromConfig() {
        String id = null;
        int len = SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS;
        int gate = SeasonRoster.DEFAULT_GATE_PERCENT;
        int fallback = SeasonRoster.FALLBACK_ROSTER_SIZE;
        boolean enforce = true;
        List<String> rawRoster = List.of();
        try {
            id = plugin.getConfig().getString("season.id", DEFAULT_SEASON_ID);
            len = plugin.getConfig().getInt("season.length-days", SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS);
            gate = plugin.getConfig().getInt("season.gate-percent", SeasonRoster.DEFAULT_GATE_PERCENT);
            fallback = plugin.getConfig().getInt("season.roster-size", SeasonRoster.FALLBACK_ROSTER_SIZE);
            enforce = plugin.getConfig().getBoolean("season.enforce-roster", true);
            rawRoster = plugin.getConfig().getStringList("season.roster");
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] config incompleta, se usan valores seguros: "
                    + exception.getMessage());
        }
        if (id == null || id.isBlank()) {
            plugin.getLogger().warning("[Temporada] falta 'season.id': se usa '" + DEFAULT_SEASON_ID
                    + "' sin tocar datos existentes.");
            id = DEFAULT_SEASON_ID;
        }
        boolean idChanged = !id.equals(seasonId) && state != null;
        seasonId = id;
        seasonLengthDays = SeasonCalendar.normalizeSeasonLength(len);
        gatePercent = Math.min(100, Math.max(0, gate));
        rosterFallback = Math.max(1, fallback);
        enforceRoster = enforce;

        List<UUID> members = new ArrayList<>();
        for (String raw : rawRoster) {
            try {
                members.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("[Temporada] UUID inválido en 'season.roster': " + raw);
            }
        }
        roster = new SeasonRoster(members);
        if (roster.isEmpty()) {
            plugin.getLogger().warning("[Temporada] configuración incompleta: 'season.roster' vacía. "
                    + "Se usa fallback M=" + rosterFallback + " solo para cálculos; "
                    + "NO se inventan UUID, NO se enrola a nadie y el enforcement de login queda desactivado.");
        }

        try {
            Optional<Path> archived = store.archiveIfSeasonChanged(seasonId);
            if (archived.isPresent()) {
                plugin.getLogger().info("[Temporada] 'season.id' ahora es '" + seasonId
                        + "': historial anterior archivado en " + archived.get().getFileName()
                        + " (no se borra nada). Se inicia estado fresco.");
            }
            state = store.load(seasonId);
            writeGate.unseal();
        } catch (SeasonStore.CorruptStateException corrupt) {
            plugin.getLogger().severe("[Temporada] " + corrupt.getMessage()
                    + " Se arranca con estado fresco en memoria y escritura BLOQUEADA"
                    + " hasta un reload correcto; el archivo queda intacto.");
            state = new SeasonState(seasonId, 1, seasonLengthDays);
            writeGate.seal(corrupt.getMessage());
        } catch (IOException io) {
            plugin.getLogger().severe("[Temporada] no se pudo leer season-state.yml: " + io.getMessage()
                    + " Escritura BLOQUEADA hasta un reload correcto; no se borra nada.");
            state = new SeasonState(seasonId, 1, seasonLengthDays);
            writeGate.seal(io.getMessage());
        }
        int effective = SeasonCalendar.normalizeSeasonLength(seasonLengthDays
                + (state.isBankExtensionUsed() ? EclipseBank.EXTENSION_DAYS : 0));
        state.setSeasonLengthDays(effective);
        if (idChanged) {
            lastDay = -1;
            lastNightEvaluated = -1;
            sleptToday = false;
        }
        bridge = new SeasonObjectives.Listener() {
            @Override
            public void onAnnounce(SeasonObjectives.Objective objective, String message) {
                if (state.markObjectiveAnnounced(objective.name())) {
                    Bukkit.broadcastMessage("§6[Temporada] §f" + message);
                    saveState();
                }
            }

            @Override
            public void onComplete(SeasonObjectives.Objective objective, UUID actor, String message) {
                if (state.markObjectiveCompleted(objective.name())) {
                    Bukkit.broadcastMessage("§6[Temporada] §a" + message);
                    saveState();
                }
            }

            @Override
            public void onReward(SeasonObjectives.Objective objective, SeasonObjectives.Reward reward,
                    boolean granted, UUID actor, String message) {
                if (granted || !reward.payable()) {
                    if (state.markRewardPaid(objective.name())) {
                        Bukkit.broadcastMessage("§6[Temporada] §e" + message);
                        saveState();
                    }
                    return;
                }
                // Pago pendiente (banco/chispas aún no conectados en B1): el beneficiario
                // persiste en season-state.yml y se reintenta; aquí solo mensaje, sin excepción.
                if (actor != null) {
                    Player player = Bukkit.getPlayer(actor);
                    if (player != null && player.isOnline()) {
                        player.sendMessage("§6[Temporada] §7" + message);
                    }
                }
                plugin.getLogger().info("[Temporada] recompensa pendiente: " + message);
            }

            @Override
            public void onProgress(SeasonObjectives.Objective objective, String message) {
                plugin.getLogger().info("[Temporada] " + objective.name() + ": " + message);
            }
        };
        objectives = SeasonObjectives.fromSnapshot(snapshotFromState(), seasonId,
                loadRewardConfig(), () -> System.currentTimeMillis(), bridge);
        objectives.bindRewardProvider((actor, reward) -> {
            if (reward == null || !reward.payable()) {
                return false;
            }
            // B1: solo Esquirlas entregables directas; Núcleos/Chispas esperan banco/Fosa (B2/B3).
            if (reward.currency() != SeasonObjectives.Currency.ESQUIRLAS) {
                return false;
            }
            // Objetivos colectivos (racha diezmados) se completan sin actor. Pagar a un
            // representante online evita que la recompensa quede pendiente para siempre.
            UUID target = actor != null ? actor : representativeActor();
            if (target == null) {
                return false;
            }
            Player player = Bukkit.getPlayer(target);
            if (player == null || !player.isOnline()) {
                return false;
            }
            if (plugin.eclipseShardItem() == null) {
                return false;
            }
            for (int i = 0; i < Math.max(1, reward.amount()); i++) {
                Map<Integer, org.bukkit.inventory.ItemStack> overflow =
                        player.getInventory().addItem(plugin.eclipseShardItem().createItem());
                overflow.values().forEach(item ->
                        player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
            return true;
        });
        objectives.retryPendingRewards();
        // Escrow durable: al arrancar/recargar, toda tx abierta de un crash
        // anterior se cierra aquí (vivo = commit una vez, muerto = refund),
        // sin esperar al join del afectado (que podría ya no ser elegible).
        try {
            reconcileAllReviveTx();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] reconcile inicial omitido: "
                    + exception.getMessage());
        }
          // Bloque 4 — recupera el final tras offline largo o crash (idempotente, una sola vez).
        try {
            checkFinale();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] final diferido: " + exception.getMessage());
        }
    }

    private Map<String, Object> snapshotFromState() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("seasonId", state.seasonId());
        snap.put("currentAct", SeasonCalendar.actForDay(currentDay(), seasonLengthDays));
        Map<String, Object> objs = new LinkedHashMap<>();
        for (SeasonObjectives.Objective objective : SeasonObjectives.Objective.values()) {
            String name = objective.name();
            SeasonObjectives.Status status = state.paidRewards().contains(name)
                    ? SeasonObjectives.Status.REWARDED
                    : state.completedObjectives().contains(name) ? SeasonObjectives.Status.COMPLETED
                    : state.announcedObjectives().contains(name) ? SeasonObjectives.Status.ANNOUNCED
                    : SeasonObjectives.Status.PENDING;
            SeasonState.ObjectiveDetail detail = state.objectiveDetail(name);
            if (detail != null) {
                objs.put(name, detail.toMap());
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", status.name());
            entry.put("announcedAt", 0L);
            entry.put("completedAt", 0L);
            entry.put("rewardedAt", 0L);
            entry.put("actor", "");
            entry.put("flags", List.of());
            entry.put("streak", 0);
            entry.put("lastStreakDay", -1);
            entry.put("lastNightDay", -1);
            objs.put(name, entry);
        }
        snap.put("objectives", objs);
        return snap;
    }

    // ------------------------------------------------------------------ recompensas configurables

    /** Recompensas pequeñas por objetivo desde config; lo ausente/ilegible conserva el default. */
    private SeasonObjectives.RewardConfig loadRewardConfig() {
        Map<String, String> currencies = new LinkedHashMap<>();
        Map<String, Integer> amounts = new LinkedHashMap<>();
        Map<String, String> itemKeys = new LinkedHashMap<>();
        for (SeasonObjectives.Objective objective : SeasonObjectives.Objective.values()) {
            String base = "season.rewards." + objective.name();
            try {
                String currency = plugin.getConfig().getString(base + ".currency", null);
                if (currency != null && !currency.isBlank()) {
                    currencies.put(objective.name(), currency);
                }
                if (plugin.getConfig().isSet(base + ".amount")) {
                    amounts.put(objective.name(), plugin.getConfig().getInt(base + ".amount", -1));
                }
                String itemKey = plugin.getConfig().getString(base + ".item-key", null);
                if (itemKey != null && !itemKey.isBlank()) {
                    itemKeys.put(objective.name(), itemKey);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return buildRewardConfig(currencies, amounts, itemKeys);
    }

    /** Puro y testeable: combina defaults con lo configurado sin defaults destructivos. */
    static SeasonObjectives.RewardConfig buildRewardConfig(Map<String, String> currencies,
            Map<String, Integer> amounts, Map<String, String> itemKeys) {
        SeasonObjectives.RewardConfig config = SeasonObjectives.RewardConfig.defaults();
        for (SeasonObjectives.Objective objective : SeasonObjectives.Objective.values()) {
            boolean hasCurrency = currencies != null && currencies.containsKey(objective.name());
            boolean hasAmount = amounts != null && amounts.containsKey(objective.name());
            boolean hasItemKey = itemKeys != null && itemKeys.containsKey(objective.name());
            if (!hasCurrency && !hasAmount && !hasItemKey) {
                continue;
            }
            SeasonObjectives.Currency currency = config.rewardFor(objective).currency();
            if (hasCurrency) {
                try {
                    currency = SeasonObjectives.Currency.valueOf(
                            currencies.get(objective.name()).trim().toUpperCase(Locale.ROOT));
                } catch (RuntimeException invalid) {
                    continue;
                }
            }
            int amount = config.rewardFor(objective).amount();
            if (hasAmount) {
                amount = amounts.get(objective.name());
            }
            String itemKey = hasItemKey ? itemKeys.get(objective.name()) : "";
            if (currency == SeasonObjectives.Currency.NINGUNA || amount <= 0) {
                config.without(objective);
            } else {
                config.with(objective,
                        SeasonObjectives.Reward.of(currency, amount, itemKey == null ? "" : itemKey));
            }
        }
        return config;
    }

    /** Vuelca el snapshot vivo (actor/flags/racha/noches) al estado antes de guardar. */
    private void syncStateFromObjectives() {
        if (objectives == null || state == null) {
            return;
        }
        Map<String, Object> snap;
        try {
            snap = objectives.snapshot();
        } catch (RuntimeException ignored) {
            return;
        }
        Object objs = snap.get("objectives");
        if (!(objs instanceof Map<?, ?> map)) {
            return;
        }
        for (SeasonObjectives.Objective objective : SeasonObjectives.Objective.values()) {
            Object entry = map.get(objective.name());
            if (entry instanceof Map<?, ?> entryMap) {
                state.setObjectiveDetail(objective.name(),
                        SeasonState.ObjectiveDetail.fromMap(entryMap));
            }
        }
    }

    // ------------------------------------------------------------------ consulta

    public String seasonId() {
        return seasonId;
    }

    /** Longitud efectiva (base config + extensión +3 si está usada): fecha real del fin. */
    public int seasonLengthDays() {
        return effectiveSeasonLengthDays();
    }

    /** Base de config para calendario (desbloqueos de fases/actos nunca se recomprimen). */
    public int baseSeasonLengthDays() {
        return seasonLengthDays;
    }

    /** Fin real = base + 3 si la extensión está usada (tope 45); durable vía flag. */
    public int effectiveSeasonLengthDays() {
        int extra = (state != null && state.isBankExtensionUsed()) ? EclipseBank.EXTENSION_DAYS : 0;
        return SeasonCalendar.normalizeSeasonLength(seasonLengthDays + extra);
    }

    public int currentDay() {
        try {
            return Math.max(1, plugin.days().getCurrentDay());
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    public int currentAct() {
        return SeasonCalendar.actForDay(currentDay(), seasonLengthDays);
    }

    /** Longitud usada para desbloqueos (base): la extensión solo retrasa el fin. */
    public int calendarLengthDays() {
        return seasonLengthDays;
    }

    public SeasonRoster roster() {
        return roster;
    }

    public SeasonState state() {
        return state;
    }

    public SeasonObjectives objectives() {
        return objectives;
    }

    /**
     * Reintenta pagos pendientes cuando entra un jugador. Necesario para objetivos colectivos:
     * al completarse sin nadie online no hay destinatario y el pago espera hasta el proximo join.
     */
    public void retryPendingRewards() {
        if (objectives != null) {
            objectives.retryPendingRewards();
        }
    }

    public boolean hasRoster() {
        return roster != null && !roster.isEmpty();
    }

    public boolean isEnforcing() {
        return enforceRoster;
    }

    /** M real si hay lista explícita; fallback numérico si no (sin inventar UUID). */
    public int effectiveM() {
        if (roster == null) {
            return rosterFallback;
        }
        return roster.effectiveSize(rosterFallback);
    }

    /** UUID eliminados según DeathRegistry (offline, nunca conectados). */
    public Set<UUID> deadUuids() {
        Set<UUID> dead = new HashSet<>();
        try {
            for (DeathRegistry.DeadPlayer entry : plugin.deaths().list()) {
                try {
                    dead.add(UUID.fromString(entry.uuid()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] no se pudo leer eliminados: " + exception.getMessage());
        }
        return dead;
    }

    /**
     * N = roster menos eliminados. Sin lista no hay fuente de verdad: se devuelve
     * el fallback para NO suspender efectos ni bloquear al administrador.
     */
    public int aliveN() {
        if (!hasRoster()) {
            return effectiveM();
        }
        return roster.aliveCount(deadUuids());
    }

    /** N para racha (siempre offline vía DeathRegistry). */
    public int aliveForStreak() {
        return aliveN();
    }

    /**
     * Gate único online: {@code max(2, ceil(M*gate%/100), minimum-online-players)}.
     * El {@code minimum-online-players} heredado (default 15) prevalece si es mayor:
     * con M=16 y 40% el gate calculado es 7, pero el default 15 domina.
     */
    public int requiredOnline() {
        int cfgMin = 15;
        try {
            cfgMin = plugin.getConfig().getInt("random-events.minimum-online-players", 15);
        } catch (RuntimeException ignored) {
        }
        return SeasonRoster.requiredOnline(effectiveM(), gatePercent, cfgMin);
    }

    /** Elegibles online para el gate: intersección de conectados con el roster menos eliminados. */
    public int eligibleOnlineCount(Collection<UUID> online) {
        if (!hasRoster()) {
            return online == null ? 0 : (int) online.stream().filter(id -> id != null).count();
        }
        return roster.eligibleOnlineCount(online, deadUuids());
    }

    /** ¿Pasa el gate la colección de jugadores dada? (pruebas manuales: bypass, no llamar). */
    public boolean passesGateForPlayers(Collection<? extends Player> players) {
        List<UUID> ids = new ArrayList<>();
        if (players != null) {
            for (Player player : players) {
                if (player == null || !player.isOnline() || player.isDead()) {
                    continue;
                }
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                try {
                    if (!plugin.deaths().isDead(player.getUniqueId())) {
                        ids.add(player.getUniqueId());
                    }
                } catch (RuntimeException ignored) {
                    ids.add(player.getUniqueId());
                }
            }
        }
        return eligibleOnlineCount(ids) >= requiredOnline();
    }

    /** Día comprimido de un umbral legado: Reserva 20→8, Ancla 35→14, Nether 10→4 (con 40d). */
    public int mapLegacyDay(int legacyDay) {
        if (legacyDay <= 0) {
            return 1;
        }
        return SeasonCalendar.phaseDay(legacyDay, seasonLengthDays);
    }

    /** Día efectivo de desbloqueo para un {@code unlock-day} configurado (legado). */
    public int effectiveUnlockDay(int configuredDay) {
        return mapLegacyDay(configuredDay);
    }

    /** Día comprimido de una fase (para bossbar y próximos desbloqueos). */
    public int seasonPhaseDay(int phase) {
        return SeasonCalendar.phaseDay(phase, seasonLengthDays);
    }

    /** Desbloqueo narrativo (bossbar/calendario): independiente de la suspensión N&lt;4. */
    public boolean isPhaseUnlocked(int phase) {
        return SeasonCalendar.isPhaseUnlocked(phase, currentDay(), seasonLengthDays);
    }

    /** Efectos suspendidos con N&lt;4 (solo efectos; narrativa y bossbar siguen). */
    public boolean areEffectsSuspended() {
        return hasRoster() && SeasonCalendar.arePhaseEffectsSuspended(aliveN());
    }

    /**
     * Punto único de decisión de efectos: fase original enabled &amp;&amp; calendario
     * comprimido desbloqueado &amp;&amp; N&gt;=4 &amp;&amp; temporada no terminada
     * (B4: tras el sellado no se aplican efectos ni eventos de fase a los conectados).
     */
    public boolean isPhaseActive(int phase) {
        boolean enabled;
        try {
            enabled = plugin.getConfig().getBoolean("phases." + phase + ".enabled", true);
        } catch (RuntimeException exception) {
            enabled = true;
        }
        return phaseActiveEffective(enabled, isPhaseUnlocked(phase), areEffectsSuspended(), isSeasonEnded());
    }

    /**
     * Núcleo puro del punto de decisión (testeable sin Bukkit): la temporada
     * terminada apaga todos los efectos aunque la fase siga desbloqueada.
     */
    static boolean phaseActiveEffective(boolean enabled, boolean unlocked, boolean suspended, boolean ended) {
        return enabled && unlocked && !suspended && !ended;
    }

    /**
     * Sonda inyectable de presencia online (tests sin Bukkit; null = Bukkit real).
     * Solo la usa el guard de extinción de {@link #checkFinale}.
     */
    static volatile java.util.function.IntSupplier onlineCountProbe;

    /**
     * Conectados ahora (cualquier jugador). Ante duda (Bukkit no disponible)
     * devuelve 1 para NO sellar una extinción irreversible a ciegas: el sellado
     * se difiere al siguiente borde con dato real.
     */
    static int onlineCount() {
        try {
            java.util.function.IntSupplier probe = onlineCountProbe;
            if (probe != null) {
                return Math.max(0, probe.getAsInt());
            }
        } catch (RuntimeException ignored) {
        }
        try {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null && player.isOnline()) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ borde de día

    /** Idempotente: evalúa noche, acto, racha y reintenta pagos; no duplica al reiniciar ni al ir atrás. */
    public void handleDayChanged(int day) {
        if (objectives == null || state == null) {
            return;
        }
        if (day == lastDay && lastNightEvaluated == lastDay) {
            return;
        }
        int prevNight = lastDay > 0 ? lastDay : day;
        if (prevNight != lastNightEvaluated) {
            lastNightEvaluated = prevNight;
            // Bloque 4 — tras el sellado la noche no evalúa objetivos (foto congelada).
            if (!isSeasonEnded()) {
                try {
                    objectives.onNightEnded(prevNight, sleptToday, representativeActor());
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("[Temporada] onNightEnded: " + exception.getMessage());
                }
            }
            sleptToday = false;
        }
        lastDay = day;
        state.setDay(day);
        // Bloque 4 — tras el sellado el día avanza pero los objetivos, el anuncio
        // de acto y la Caída del Sol ya no mutan ni premian (foto congelada).
        if (!isSeasonEnded()) {
            int act = SeasonCalendar.actForDay(day, seasonLengthDays);
            try {
                objectives.onActChanged(act);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[Temporada] onActChanged: " + exception.getMessage());
            }
            if (state.markActAnnounced(act)) {
                announceAct(act);
            }
            try {
                evaluateStreak(day);
                objectives.retryPendingRewards();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[Temporada] onDayEvaluated: " + exception.getMessage());
            }
            saveState();
            triggerSolarFallIfDue();
        }
          // Bloque 4 — dispara el final en el borde de día (una sola vez, sin duplicar premios).
        try {
              checkFinale();
          } catch (RuntimeException exception) {
              plugin.getLogger().warning("[Temporada] final diferido: " + exception.getMessage());
          }
      }

    /** Borde de día para el tick: solo evalúa (y guarda) cuando el día realmente cambió. */
    public void checkDayRollover() {
        int day = currentDay();
        if (day != lastDay) {
            handleDayChanged(day);
        }
    }

    public void noteBedEnter() {
        sleptToday = true;
    }

    /**
     * La racha de "3 dias reales" no debe contar saltos forzados con /pd dia. Ademas pasa un
     * actor representativo para que la recompensa colectiva tenga destinatario.
     */
    private void evaluateStreak(int day) {
        if (objectives == null || state == null || plugin.days().isManualDayOverride()) {
            return;
        }
        objectives.onDayEvaluated(day, aliveForStreak(), representativeActor());
    }

    private UUID representativeActor() {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOnline() && !player.isDead() && !plugin.deaths().isDead(player.getUniqueId())
                        && (!hasRoster() || roster.isMember(player.getUniqueId()))) {
                    return player.getUniqueId();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ eventos reales

    /** Portal con mundo actual del jugador (solo cuenta si ya esta en la Reserva). */
    public void notifyPortal(Player player) {
        notifyPortal(player, player == null ? null : player.getWorld());
    }

    /**
     * Portal con destino real del teleport (tiene prioridad sobre el mundo actual:
     * en pre-teleport {@code player.getWorld()} aun es el origen). Solo llama al
     * objetivo si el destino es la Reserva; el nombre real se pasa sin prefijos.
     */
    public void notifyPortal(Player player, World destination) {
        if (objectives == null || player == null || isSeasonEnded()) {
            return;
        }
        String world = destination == null ? "" : destination.getName();
        if (!world.toLowerCase(Locale.ROOT).contains("reserva")) {
            return;
        }
        try {
            objectives.onPortalOpened(player.getUniqueId(), world);
            saveState();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] portal: " + exception.getMessage());
        }
    }

    public void notifyCore(Player player) {
        if (objectives == null || player == null || isSeasonEnded()) {
            return;
        }
        try {
            objectives.onFirstCore(player.getUniqueId());
            saveState();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] núcleo: " + exception.getMessage());
        }
    }

    public void notifyFortress(Player player) {
        if (objectives == null || player == null || isSeasonEnded()) {
            return;
        }
        try {
            objectives.onFortressCompleted(player.getUniqueId());
            saveState();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] fortaleza: " + exception.getMessage());
        }
    }

    public void notifyNightHunt(Player player) {
        if (objectives == null || player == null || isSeasonEnded()) {
            return;
        }
        try {
            objectives.onNightHuntCompleted(player.getUniqueId());
            saveState();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] cacería: " + exception.getMessage());
        }
    }

    public void notifyEspora(Player player) {
        if (objectives == null || player == null || isSeasonEnded()) {
            return;
        }
        try {
            objectives.onEsporaSample(player.getUniqueId());
            saveState();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] esporas: " + exception.getMessage());
        }
    }

    /** Muerte real: reevalúa racha de forma diferida (idempotente por día). */
    public void onDeathStreak() {
        if (isSeasonEnded()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                evaluateStreak(currentDay());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[Temporada] racha/muerte: " + exception.getMessage());
            }
        }, 1L);
    }

    // ------------------------------------------------------------------ bloque 2: sacrificios y banco

    public SacrificeType sacrificeOf(UUID uuid) {
        if (state == null || uuid == null) {
            return null;
        }
        return SacrificeType.fromId(state.sacrificeChoice(uuid).orElse(null));
    }

    public boolean hasSacrifice(UUID uuid, SacrificeType type) {
        return type != null && type.equals(sacrificeOf(uuid));
    }

    public Map<String, String> allSacrifices() {
        return state == null ? Map.of() : state.sacrifices();
    }

    /**
     * Elección irreversible por UUID (durable en season-state.yml). Primera vez true;
     * si el UUID ya eligió devuelve false sin cambios (muerte/revive/reinicio no reabren).
     */
    public boolean chooseSacrifice(UUID uuid, SacrificeType type) {
        if (state == null || uuid == null || type == null || isSeasonEnded()) {
            return false;
        }
        if (!state.setSacrifice(uuid, type)) {
            return false;
        }
        saveState();
        return true;
    }

    public int bankBalance() {
        return state == null ? 0 : state.bankBalance();
    }

    public int bankTotalDeposited() {
        return state == null ? 0 : state.bankTotalDeposited();
    }

    public int bankTotalSpent() {
        return state == null ? 0 : state.bankTotalSpent();
    }

    public boolean isBankRitualOpen() {
        return state != null && state.isBankRitualOpen();
    }

    public boolean isBankSunReserved() {
        return state != null && state.isBankSunReserved();
    }

    public boolean isBankExtensionUsed() {
        return state != null && state.isBankExtensionUsed();
    }

    public java.util.Set<String> bankTxIds() {
        return state == null ? java.util.Set.of() : state.bankTxIds();
    }

    public Map<String, Integer> bankEscrow() {
        return state == null ? Map.of() : state.bankEscrow();
    }

    private EclipseBank readBank() {
        if (state == null) {
            return new EclipseBank();
        }
        return EclipseBank.fromParts(state.bankBalance(), state.bankTotalDeposited(),
                state.bankTotalSpent(), state.isBankExtensionUsed(), state.isBankRitualOpen(),
                state.isBankSunReserved(), state.bankTxIds(), state.bankEscrow());
    }

    private void writeBank(EclipseBank bank) {
        if (state == null || bank == null) {
            return;
        }
        state.setBankBalance(bank.balance());
        state.setBankTotalDeposited(bank.totalDeposited());
        state.setBankTotalSpent(bank.totalSpent());
        state.setBankExtensionUsed(bank.isExtensionUsed());
        state.setBankRitualOpen(bank.isRitualOpen());
        state.setBankSunReserved(bank.isSunReserved());
        for (String tx : bank.processedTx()) {
            state.addBankTx(tx);
        }
        for (Map.Entry<String, Integer> e : bank.escrow().entrySet()) {
            if (!state.bankEscrow().containsKey(e.getKey())) {
                state.putBankEscrow(e.getKey(), e.getValue());
            }
        }
        for (String known : new java.util.ArrayList<>(state.bankEscrow().keySet())) {
            if (!bank.escrow().containsKey(known)) {
                state.removeBankEscrow(known);
            }
        }
    }

    /**
     * B4 — núcleo puro del depósito al banco (testeable sin Bukkit): con ENDED o
     * importe inválido devuelve 0 sin mutar nada; si no, acredita en el banco y
     * devuelve lo acreditado. Nunca destruye antes de acreditar.
     */
    static int executeBankDeposit(SeasonState state, EclipseBank bank, int cores) {
        if (state == null || bank == null || cores <= 0 || state.isSeasonEnded()) {
            return 0;
        }
        if (!bank.deposit(cores)) {
            return 0;
        }
        return cores;
    }

    /** Depósito real de Núcleos (ya validados como auténticos): acredita y evalúa el objetivo VII. */
    public int depositBankCores(int cores, UUID actor) {
        if (state == null || cores <= 0 || isSeasonEnded()) {
            return 0;
        }
        EclipseBank bank = readBank();
        if (executeBankDeposit(state, bank, cores) <= 0) {
            return 0;
        }
        writeBank(bank);
        try {
            objectives.onBankFunded(actor, currentDay(), seasonLengthDays, bank.totalDeposited());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] banco: " + exception.getMessage());
        }
        saveState();
        return cores;
    }

    /** Compra 1: 12N extensión única +3d (tope 45). Idempotente por tx; replay no suma días. */
    public boolean purchaseBankExtension(String txId) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        boolean wasUsed = bank.isExtensionUsed();
        if (!bank.purchaseExtension(txId, effectiveSeasonLengthDays())) {
            return false;
        }
        writeBank(bank);
        if (!wasUsed) {
            state.setSeasonLengthDays(
                    SeasonCalendar.normalizeSeasonLength(seasonLengthDays + EclipseBank.EXTENSION_DAYS));
        }
        saveState();
        return true;
    }

    /** Compra 2: 8N abre el ritual global persistente. Idempotente por tx. */
    public boolean purchaseBankRitual(String txId) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        if (!bank.purchaseRitual(txId)) {
            return false;
        }
        writeBank(bank);
        saveState();
        return true;
    }

    /** Compra 3a/3b/3c: reserva, pago y cancelación del Sol (30N, escrow idempotente). */
    public boolean reserveBankSun(String txId) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        if (!bank.reserveSun(txId)) {
            return false;
        }
        writeBank(bank);
        saveState();
        return true;
    }

    public boolean confirmBankSun(String txId) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        if (!bank.confirmSun(txId)) {
            return false;
        }
        writeBank(bank);
        saveState();
        return true;
    }

    public boolean cancelBankSun(String txId) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        if (!bank.cancelSun(txId)) {
            return false;
        }
        writeBank(bank);
        saveState();
        return true;
    }

    /** Devolución interna de 1 Núcleo (refund idempotente de cancelación, sin tocar objetivos). */
    private void refundBankCore() {
        if (state == null) {
            return;
        }
        EclipseBank bank = readBank();
        bank.deposit(1);
        writeBank(bank);
    }

    /** Consumo interno (Ancla del grupo): 1 Núcleo o false sin mutación. */
    public boolean consumeBankCore(int cores) {
        if (state == null || isSeasonEnded()) {
            return false;
        }
        EclipseBank bank = readBank();
        if (!bank.consume(cores)) {
            return false;
        }
        writeBank(bank);
        saveState();
        return true;
    }

    public boolean isSolarFallDone() {
        return state != null && state.isSolarFallDone();
    }

    public void markSolarFallDone() {
        if (state == null || state.isSolarFallDone() || isSeasonEnded()) {
            return;
        }
        state.setSolarFallDone(true);
        saveState();
    }

    // ------------------------------------------------------------------ bloque 3: Fosa y Chispas

    /** Temporada activa: hay estado cargado (para puertas de login/kick de la Fosa). */
    public boolean isSeasonActive() {
        return state != null;
    }

    /** Duración de run de la Fosa 10–15 min (default 12); fuera de rango cae al default. */
    public int pitRunMinutes() {
        int configured = PitLedger.DEFAULT_RUN_MINUTES;
        try {
            configured = plugin.getConfig().getInt("season.pit-duration-minutes",
                    PitLedger.DEFAULT_RUN_MINUTES);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] pit-duration-minutes ilegible, se usa 12: "
                    + exception.getMessage());
        }
        return PitLedger.clampRunMinutes(configured);
    }

    /**
     * Elegible para la Fosa: temporada activa + eliminado del roster.
     * Con roster explícito exige pertenencia; sin roster (fallback) basta estar eliminado.
     */
    public boolean isPitEligible(java.util.UUID uuid) {
        if (state == null || uuid == null) {
            return false;
        }
        try {
            if (!plugin.deaths().isDead(uuid)) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        if (hasRoster() && roster != null) {
            try {
                return roster.isMember(uuid);
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return true;
    }

    /** Registra la muerte para la válvula de 5 días (llamar al morir en temporada). */
    public void noteSeasonDeath(java.util.UUID uuid) {
        noteSeasonDeath(uuid, System.currentTimeMillis());
    }

    void noteSeasonDeath(java.util.UUID uuid, long epochMillis) {
        if (state == null || uuid == null || isSeasonEnded()) {
            return;
        }
        state.noteSeasonDeath(uuid, epochMillis);
        // Bloque 4 — contador de por vida por hook (revivir no lo borra).
          try {
            state.addLifetimeDeath(uuid.toString());
        } catch (RuntimeException ignored) {
        }
        saveState();
    }

    /** Días reales desde la muerte (−1 si no hay registro). */
    public long daysSinceSeasonDeath(java.util.UUID uuid) {
        if (state == null || uuid == null) {
            return -1L;
        }
        long death = state.seasonDeathTimeOf(uuid);
        if (death <= 0) {
            return -1L;
        }
        return PitLedger.daysSinceDeath(death, System.currentTimeMillis());
    }

    public int pitEarnedOf(java.util.UUID uuid) {
        return state == null ? 0 : state.pitEarnedOf(uuid);
    }

    public int pitBalanceOf(java.util.UUID uuid) {
        return state == null ? 0 : state.pitBalanceOf(uuid);
    }

    public int revivalsOf(java.util.UUID uuid) {
        return state == null ? 0 : state.revivalsOf(uuid);
    }

    /** Coste de la siguiente vuelta (N = revivals+1). */
    public int nextRevivalCost(java.util.UUID uuid) {
        return PitLedger.nextCost(revivalsOf(uuid));
    }

    /**
     * Gana 1 Chispa por run completado: tope 1/día real y 6 ganadas por temporada.
     * Solo eliminados elegibles; sin anuncios globales (el llamador avisa en privado).
     */
    public synchronized boolean tryEarnChispa(java.util.UUID uuid, long nowMillis) {
        if (state == null || uuid == null || isSeasonEnded() || !isPitEligible(uuid)) {
            return false;
        }
        long today = PitLedger.epochDay(nowMillis);
        if (!PitLedger.canEarn(state.pitEarnedOf(uuid), state.pitLastEarnDayOf(uuid), today)) {
            return false;
        }
        state.addPitEarned(uuid, today);
        saveState();
        return true;
    }

    /** Evalúa la entrada de vuelta sin mutar (mensajes de faltantes incluidos). */
    public RevivalPolicy.Decision evaluateRevival(java.util.UUID target, boolean hasSeal, long nowMillis) {
        if (state == null || target == null) {
            return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, 1, false,
                    java.util.List.of("temporada no disponible."));
        }
        long death = state.seasonDeathTimeOf(target);
        long days = death <= 0 ? 0 : PitLedger.daysSinceDeath(death, nowMillis);
        return RevivalPolicy.evaluate(aliveN(), state.revivalsOf(target), state.pitBalanceOf(target),
                state.isBankRitualOpen(), state.bankBalance(), days, hasSeal);
    }

    /** Puertos IO del escrow de vuelta (producción = disco real; tests = fallos scriptados). */
    interface ReviveTxPorts {
        /** Persiste season-state.yml (con banco ya volcado); false = disco no durable. */
        boolean persist();

        /** Registra la vuelta en DeathRegistry de forma durable; false = no registrada. */
        boolean registryRevive(UUID target);
    }

    /** Resultado durable del intento de vuelta (el llamador lo mapea a Decision). */
    enum ReviveTxOutcome {
        COMMITTED,
        DENIED_CHISPAS,
        DENIED_TOLL,
        DENIED_INFLIGHT,
        DENIED_PREPARE_PERSIST,
        DENIED_REGISTRY
    }

    /**
     * Bloque 3 — núcleo transaccional testeable de la vuelta (puro salvo puertos).
     * Orden durable: cobrar Chispas (+peaje SOLO en ALTAR_SOLO) → abrir tx →
     * persistir PREPARED → registro en DeathRegistry → commit idempotente
     * (asegura base+1, repetir no recuenta) → persistir. Un fallo de disco en
     * PREPARED aborta con rollback total y NO autoriza (el jugador sigue muerto
     * y con sus Chispas); un fallo en el registro revierte con refund; un crash
     * tras el registro se recupera por reconcile (vivo = commit, muerto = refund).
     */
    static ReviveTxOutcome executeReviveTx(SeasonState state, EclipseBank bank, UUID target,
            int cost, boolean toll, long deathTime, ReviveTxPorts ports) {
        if (state == null || target == null || cost <= 0 || ports == null) {
            return ReviveTxOutcome.DENIED_CHISPAS;
        }
        if (!state.spendPit(target, cost)) {
            return ReviveTxOutcome.DENIED_CHISPAS;
        }
        boolean coreTaken = false;
        if (toll) {
            if (bank == null || !bank.consume(1)) {
                state.refundPit(target, cost);
                return ReviveTxOutcome.DENIED_TOLL;
            }
            coreTaken = true;
        }
        if (!state.beginReviveTx(target, cost, coreTaken, deathTime)) {
            state.refundPit(target, cost);
            if (coreTaken && bank != null) {
                bank.deposit(1);
            }
            return ReviveTxOutcome.DENIED_INFLIGHT;
        }
        if (!safePersist(ports)) {
            state.refundPit(target, cost);
            if (coreTaken && bank != null) {
                bank.deposit(1);
            }
            state.noteSeasonDeath(target, deathTime);
            state.clearReviveTx(target);
            safePersist(ports);
            return ReviveTxOutcome.DENIED_PREPARE_PERSIST;
        }
        boolean revived;
        try {
            revived = ports.registryRevive(target);
        } catch (RuntimeException exception) {
            revived = false;
        }
        if (!revived) {
            state.refundPit(target, cost);
            if (coreTaken && bank != null) {
                bank.deposit(1);
            }
            state.noteSeasonDeath(target, deathTime);
            state.clearReviveTx(target);
            safePersist(ports);
            return ReviveTxOutcome.DENIED_REGISTRY;
        }
        state.commitReviveTx(target);
        // Si este persist falla, el disco conserva la tx abierta con revivals=base
        // y el jugador VIVO: el reconcile posterior completa con base+1 idempotente.
        safePersist(ports);
        return ReviveTxOutcome.COMMITTED;
    }

    private static boolean safePersist(ReviveTxPorts ports) {
        try {
            return ports.persist();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Reconciliación pura y testeable de una tx abierta: muerto = rollback
     * (refund + restaura muerte), vivo = commit idempotente (asegura base+1).
     * Devuelve {@code true} si había tx abierta. El Núcleo de peaje se devuelve
     * al banco que se pasa (el llamador lo vuelca al estado).
     */
    static boolean reconcileTxOutcome(SeasonState state, EclipseBank bank, UUID target, boolean dead) {
        if (state == null || target == null || !state.hasReviveTx(target)) {
            return false;
        }
        if (dead) {
            int cost = state.reviveTxCostOf(target);
            boolean core = state.reviveTxTookCore(target);
            long death = state.reviveTxDeathOf(target);
            state.refundPit(target, cost);
            if (core && bank != null) {
                bank.deposit(1);
            }
            state.noteSeasonDeath(target, death);
            state.clearReviveTx(target);
            return true;
        }
        state.commitReviveTx(target);
        return true;
    }

    /**
     * Transacción de vuelta normal (toda vía normal pasa por aquí: ritual, /pd revivir,
     * altar de La Fosa). Escrow durable: cobra Chispas (+1 Núcleo de peaje SOLO en
     * ALTAR_SOLO; la válvula de 5 días no lleva peaje), persiste el intento ANTES de
     * tocar DeathRegistry y confirma con commit idempotente SOLO en ÉXITO; toda
     * cancelación hace refund sin contar vuelta ni duplicar al restart/doble ritual.
     */
    public synchronized RevivalPolicy.Decision trySeasonRevive(java.util.UUID target, boolean hasSeal,
            long nowMillis) {
        reconcileReviveTx(target);
        if (isSeasonEnded()) {
            return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, 1, false,
                    java.util.List.of("temporada terminada: el final ya se selló."));
        }
        RevivalPolicy.Decision decision = evaluateRevival(target, hasSeal, nowMillis);
        if (!decision.allowed()) {
            return decision;
        }
        int cost = decision.cost();
        // Peaje de 1 Núcleo SOLO en ALTAR_SOLO (roster ≤1). La válvula de 5 días
        // no lleva peaje: solo exige Chispas suficientes para el siguiente coste.
        boolean toll = decision.path() == RevivalPolicy.Path.ALTAR_SOLO;
        if (state.pitBalanceOf(target) < cost) {
            return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                    java.util.List.of("faltan Chispas: la vuelta " + cost + " cuesta " + cost + "."));
        }
        if (toll && readBank().balance() < 1) {
            return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                    java.util.List.of("el banco no tiene 1 Núcleo de peaje; Chispas intactas."));
        }
        long deathTime = state.seasonDeathTimeOf(target);
        EclipseBank bank = readBank();
        ReviveTxOutcome outcome = executeReviveTx(state, bank, target, cost, toll, deathTime,
                new ReviveTxPorts() {
                    @Override
                    public boolean persist() {
                        writeBank(bank);
                        return persistState();
                    }

                    @Override
                    public boolean registryRevive(java.util.UUID id) {
                        try {
                            return plugin.deaths().reviveSeasonalChecked(id);
                        } catch (RuntimeException exception) {
                            return false;
                        }
                    }
                });
        switch (outcome) {
            case COMMITTED:
                try {
                    onReviveStreak();
                } catch (RuntimeException ignored) {
                }
                return decision;
            case DENIED_TOLL:
                return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                        java.util.List.of("el banco no tiene 1 Núcleo de peaje; Chispas devueltas."));
            case DENIED_INFLIGHT:
                return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                        java.util.List.of("vuelta en curso: reintenta en unos segundos."));
            case DENIED_PREPARE_PERSIST:
                return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                        java.util.List.of("no se pudo persistir el intento; sin cobro ni registro."));
            case DENIED_REGISTRY:
                return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                        java.util.List.of("no se pudo registrar la vuelta; Chispas devueltas."));
            case DENIED_CHISPAS:
            default:
                return new RevivalPolicy.Decision(RevivalPolicy.Path.DENIED, cost, false,
                        java.util.List.of("faltan Chispas: la vuelta " + cost + " cuesta " + cost + "."));
        }
    }

    /**
     * Bloque 3 — reconcilia un intento de vuelta interrumpido por crash/restart
     * entre el cobro y el registro. Muerto = el registro nunca ocurrió:
     * rollback (refund + restaura muerte). Vivo = el registro sí ocurrió:
     * commit idempotente (cuenta la vuelta una sola vez). Sin tx abierta no hace nada.
     */
    public synchronized void reconcileReviveTx(java.util.UUID target) {
        if (state == null || target == null || !state.hasReviveTx(target)) {
            return;
        }
        boolean dead;
        try {
            dead = plugin.deaths().isDead(target);
        } catch (RuntimeException exception) {
            return;
        }
        EclipseBank bank = readBank();
        boolean reconciled;
        try {
            reconciled = reconcileTxOutcome(state, bank, target, dead);
        } catch (RuntimeException exception) {
            return;
        }
        if (!reconciled) {
            return;
        }
        writeBank(bank);
        // Best-effort: si el disco falla aquí, la memoria queda bien y el siguiente
        // guardado lo persiste; tras un restart el commit es idempotente (base+1)
        // y el refund no duplica (clamp a 0 + single-flight).
        persistState();
    }

    /**
     * Reconcilia TODAS las tx abiertas (arranque/reload de temporada). Cubre el
     * caso crash-tras-registro de un jugador que ya está VIVO: su reconcile
     * individual nunca se dispararía por reintento ni por onJoin con guard de
     * elegibilidad, así que se barre el ledger completo al cargar.
     */
    public synchronized void reconcileAllReviveTx() {
        if (state == null) {
            return;
        }
        for (String id : new java.util.ArrayList<>(state.reviveTxIds())) {
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            try {
                reconcileReviveTx(uuid);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[Temporada] no se pudo reconciliar escrow de " + id
                        + ": " + exception.getMessage());
            }
        }
    }

    /**
     * Cancela un escrow abierto con refund total y sin contar vuelta (para la
     * vía forzada administrativa: el forzado no consume Chispas ni vuelta, y un
     * reconcile posterior no debe cometer nada). {@code true} si había tx.
     */
    public synchronized boolean cancelReviveTx(java.util.UUID target) {
        if (state == null || target == null || !state.hasReviveTx(target)) {
            return false;
        }
        EclipseBank bank = readBank();
        boolean cancelled;
        try {
            // Rollback como "muerto": refund + restaura muerte + cierra tx, sin contar.
            cancelled = reconcileTxOutcome(state, bank, target, true);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!cancelled) {
            return false;
        }
        writeBank(bank);
        persistState();
        return true;
    }

    /** Señal a B4: 0 vivos del roster (el llamador la retiene entre muerte y teleport/kick). */
    public boolean shouldSignalSeasonEnd() {
        if (state == null) {
            return false;
        }
        return RevivalPolicy.shouldSignalSeasonEnd(aliveN());
    }

    // ------------------------------------------------------------------ bloque 4: Último Día

    /** Día final real (base + única extensión 3d, tope 45; compat 100 → 100). */
    public int finalDay() {
        return SeasonFinale.finalDay(seasonLengthDays, state != null && state.isBankExtensionUsed());
    }

    /** Estado del final persistido (RUNNING/VICTORY/DEFEAT). */
    public String finaleStatus() {
        return state == null ? SeasonFinale.Status.RUNNING.name() : state.finaleStatus();
    }

    /** Temporada terminada: congela run/compra/revive/ranking (una sola vez). */
    public boolean isSeasonEnded() {
        return state != null && state.isSeasonEnded();
    }

    /** Hook de join: fija el primer día visto por UUID (stats días sobrevividos). */
    public void notePlayerSeen(java.util.UUID uuid, int day) {
        if (state == null || uuid == null || isSeasonEnded()) {
            return;
        }
        state.noteFirstSeen(uuid.toString(), day);
        saveState();
    }

    /**
     * Evalúa y sella el final una sola vez (borde de día, arranque tras offline
     * largo o señal B3 de extinción). Al día final intenta encender el Sol con
     * fondos del Banco vía tx determinista idempotente: con 30 Núcleos victoria,
     * sin ellos derrota. {@code /pd dia} hacia atrás no reabre ni duplica.
     *
     * @return {@code true} si esta llamada selló el final.
     */
    public synchronized boolean checkFinale() {
        if (state == null || state.isSeasonEnded()) {
            return false;
        }
        int day = currentDay();
        int fin = effectiveSeasonLengthDays();
        // Bloque 4 — extinción solo con 0 vivos del roster Y nadie conectado:
        // el último muerto online en La Fosa (válvula de 5 días, vueltas B3)
        // mantiene RUNNING hasta el plazo. El día final cierra siempre.
        int alive = aliveN();
        boolean zeroAlive = alive <= 0;
        int online = onlineCount();
        // Auto-encendido al día final: primero confirma cualquier reserva del
        // Sol ya pagada al escrow (flujo en dos pasos sin confirmar a tiempo);
        // solo sin escrow pendiente se paga directo con saldo. Una sola vez.
        if (day >= fin && !state.isBankSunReserved()) {
            try {
                EclipseBank bank = readBank();
                boolean funded;
                if (!bank.escrow().isEmpty()) {
                    funded = SeasonFinale.confirmPendingSun(bank);
                } else if (bank.balance() >= EclipseBank.SUN_COST) {
                    funded = SeasonFinale.tryLightSun(bank, seasonId, fin);
                } else {
                    funded = false;
                }
                if (funded) {
                    writeBank(bank);
                }
            } catch (RuntimeException ignored) {
            }
        }
        SeasonFinale.Status decided;
        try {
            decided = SeasonFinale.decide(day, fin, state.isBankSunReserved(), alive, online);
        } catch (RuntimeException exception) {
            return false;
        }
        if (decided == SeasonFinale.Status.RUNNING) {
            return false;
        }
        String actor;
        try {
            java.util.UUID rep = representativeActor();
            actor = rep == null ? "—" : rep.toString();
        } catch (RuntimeException exception) {
            actor = "—";
        }
        String reason = decided == SeasonFinale.Status.VICTORY
                ? "El grupo reunió 30 Núcleos y encendió el Sol el día " + fin + "."
                : (zeroAlive && day < fin
                        ? "Extinción del roster el día " + day + " (fin anticipado; fecha real " + fin + ")."
                        : "Sin Sol al día final " + fin + ": el Sol se apaga.");
        if (!state.setFinale(decided.name(), fin, actor, reason)) {
            return false;
        }
        // Bloque 4 — foto fija ANTES de persistir y anunciar: el reload no
        // inventa otra foto y /pd final reproduce siempre la misma lista.
        try {
            java.util.List<String> snapEvents = new java.util.ArrayList<>(state.completedObjectives());
            java.util.Collections.sort(snapEvents);
            state.freezeFinale(alive, effectiveM(), aliveDisplayNames(),
                    state.lifetimeDeaths(), state.firstSeenDays(),
                    state.bankTotalDeposited(), state.bankTotalSpent(), snapEvents);
        } catch (RuntimeException ignored) {
        }
        persistState();
        try {
            String title = decided == SeasonFinale.Status.VICTORY
                    ? "§6☀ El Sol se enciende" : "§8☾ El Sol se apaga";
            Bukkit.broadcastMessage(title + " §7— temporada " + seasonId
                    + " (día " + fin + ", actor " + actor + ")");
            for (org.bukkit.entity.Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.sendTitle(title, "§fDía " + fin + " · " + seasonId, 10, 80, 20);
            }
        } catch (RuntimeException | NoClassDefFoundError ignored) {
        }
        return true;
    }

    /**
     * Líneas del final para /pd final (accesible a todo el roster, incluidos
     * muertos). Sellada la temporada, reproduce la foto del sellado sin
     * recalcular contra el estado vivo; antes del final usa el estado vivo.
     */
    public java.util.List<String> finaleLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (state == null) {
            out.add("Temporada no disponible todavía.");
            return out;
        }
        if (state.isSeasonEnded() && state.hasFinaleSnapshot()) {
            out.addAll(renderFinaleLines(seasonId, state.finaleStatus(), state.finaleDay(),
                    state.finaleActor(), state.finaleReason(), state.finaleAliveN(),
                    state.finaleAliveM(), state.finaleBankDeposited(), state.finaleBankSpent(),
                    state.finaleLifetimeDeaths(), state.sacrifices(), state.finaleEvents(),
                    state.finaleFirstSeenDays(), state.finaleAliveNames()));
            return out;
        }
        int fin = effectiveSeasonLengthDays();
        java.util.List<String> events = new java.util.ArrayList<>(state.completedObjectives());
        java.util.Collections.sort(events);
        out.addAll(renderFinaleLines(seasonId, state.finaleStatus(), fin,
                state.finaleActor(), state.finaleReason(), aliveN(), effectiveM(),
                state.bankTotalDeposited(), state.bankTotalSpent(),
                state.lifetimeDeaths(), state.sacrifices(), events,
                state.firstSeenDays(), aliveDisplayNames()));
        if (!state.isSeasonEnded()) {
            out.add("§7El final llega el día §f" + fin
                    + " §7(base " + seasonLengthDays
                    + (state.isBankExtensionUsed() ? " +3 extensión" : "") + ").");
        }
        return out;
    }

    /**
     * Núcleo puro del render del final (testeable sin Bukkit): mismos datos
     * de entrada, misma lista. La foto del sellado y el estado vivo pasan por aquí.
     */
    static java.util.List<String> renderFinaleLines(String seasonId, String status, int fin,
            String actor, String reason, int alive, int total, int deposited, int spent,
            java.util.Map<String, Integer> deaths, java.util.Map<String, String> sacrifices,
            java.util.List<String> events, java.util.Map<String, Integer> firstSeen,
            java.util.List<String> aliveNames) {
        return SeasonStatistics.buildLines(seasonId, status, fin, actor, reason,
                alive, total, deposited, spent, deaths, sacrifices, events, firstSeen, aliveNames);
    }

    /**
     * Nombres de los vivos del roster para {@code /pd final} (best-effort:
     * sin roster no hay a quién nombrar; un nombre ilegible cae al UUID).
     */
    private java.util.List<String> aliveDisplayNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (!hasRoster() || roster == null) {
                return names;
            }
            java.util.Set<java.util.UUID> dead = deadUuids();
            for (java.util.UUID id : roster.members()) {
                if (id == null || dead.contains(id)) {
                    continue;
                }
                String name = null;
                try {
                    name = Bukkit.getOfflinePlayer(id).getName();
                } catch (RuntimeException | NoClassDefFoundError ignored) {
                }
                names.add(name == null || name.isBlank() ? id.toString() : name);
            }
        } catch (RuntimeException | NoClassDefFoundError ignored) {
        }
        java.util.Collections.sort(names);
        return names;
    }

    /**
     * Bloque 2 — Ancla: destino empaquetado {@code mundo,x,y,z} decodificado sin Bukkit.
     *
     * <p>El nombre del mundo puede contener comas: las últimas 3 comas separan
     * las coordenadas y el resto es el nombre completo del mundo.
     */
    public record AnchorTarget(String worldName, int x, int y, int z) {
    }

    /**
     * Puro y testeable: decodifica {@code mundo,x,y,z} sin tocar Bukkit ni el estado.
     * Devuelve {@code null} si los campos son insuficientes, el mundo está vacío
     * o las coordenadas no son enteros (incluye NaN/Infinity/texto).
     */
    static AnchorTarget parseAnchorPacked(String packed) {
        if (packed == null || packed.isBlank()) {
            return null;
        }
        int last = packed.lastIndexOf(',');
        if (last < 0) {
            return null;
        }
        int mid = packed.lastIndexOf(',', last - 1);
        if (mid < 0) {
            return null;
        }
        int first = packed.lastIndexOf(',', mid - 1);
        if (first < 0) {
            return null;
        }
        String worldName = packed.substring(0, first).trim();
        String xs = packed.substring(first + 1, mid).trim();
        String ys = packed.substring(mid + 1, last).trim();
        String zs = packed.substring(last + 1).trim();
        if (worldName.isEmpty() || xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) {
            return null;
        }
        try {
            return new AnchorTarget(worldName, Integer.parseInt(xs), Integer.parseInt(ys),
                    Integer.parseInt(zs));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** Bloque 2 — Ancla: lectura sin consumo (la entrada queda PENDIENTE). */
    public String peekAnchorDeath(UUID uuid) {
        if (state == null || uuid == null) {
            return null;
        }
        return state.peekAnchorDeath(uuid);
    }

    /**
     * Bloque 2 — Ancla: peek -&gt; decode -&gt; validar destino -&gt; consume solo tras éxito.
     *
     * <p>Si el destino es inválido o el mundo no está cargado, la entrada queda
     * PENDIENTE intacta en el fichero (sin borrar, sin cobrar, sin Location con
     * mundo null) y se registra un aviso acotado.
     */
    public org.bukkit.Location takeAnchorDeath(UUID uuid) {
        if (state == null || uuid == null) {
            return null;
        }
        String packed = state.peekAnchorDeath(uuid);
        if (packed == null) {
            return null;
        }
        AnchorTarget target = parseAnchorPacked(packed);
        if (target == null) {
            plugin.getLogger().warning("[Altar] Ancla de " + uuid
                    + " con destino ilegible: se conserva PENDIENTE sin cobrar ni borrar.");
            return null;
        }
        org.bukkit.World world;
        try {
            world = Bukkit.getWorld(target.worldName());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Altar] Ancla de " + uuid
                    + " no resoluble ahora: se conserva PENDIENTE sin cobrar ni borrar.");
            return null;
        }
        if (world == null) {
            plugin.getLogger().warning("[Altar] Ancla de " + uuid + " en mundo no cargado '"
                    + target.worldName() + "': se conserva PENDIENTE sin cobrar ni borrar.");
            return null;
        }
        state.takeAnchorDeath(uuid);
        saveState();
        return new org.bukkit.Location(world, target.x() + 0.5, target.y(), target.z() + 0.5);
    }

    public void putAnchorDeath(UUID uuid, String packed) {
        if (state == null) {
            return;
        }
        state.putAnchorDeath(uuid, packed);
        saveState();
    }

    public boolean isAnchorUsed(UUID uuid) {
        return state != null && state.isAnchorUsed(uuid);
    }

    public void markAnchorUsed(UUID uuid) {
        if (state == null) {
            return;
        }
        state.markAnchorUsed(uuid);
        saveState();
    }

    /** Reintenta la Caída del Sol si toca (un solo intento persistido). */
    public void triggerSolarFallIfDue() {
        try {
            if (plugin.solarFallEvent() != null) {
                plugin.solarFallEvent().checkAndRun(currentDay());
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Reincorporación: reevalúa racha (idempotente por día, sin tocar revived). */
    public void onReviveStreak() {
        if (isSeasonEnded()) {
            return;
        }
        try {
            evaluateStreak(currentDay());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Temporada] racha/revive: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ texto

    public static String actName(int act) {
        return switch (Math.min(Math.max(1, act), SeasonCalendar.ACT_COUNT)) {
            case 1 -> "La Grieta";
            case 2 -> "El Insomnio";
            case 3 -> "El Eclipse";
            case 4 -> "La Invasión";
            case 5 -> "La Hambruna";
            case 6 -> "La Soledad";
            default -> "El Último Día";
        };
    }

    private void announceAct(int act) {
        Bukkit.broadcastMessage("§6[Temporada] §fComienza el acto "
                + SeasonObjectives.Objective.roman(act) + " — " + actName(act));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle("§6Acto " + SeasonObjectives.Objective.roman(act),
                    "§f" + actName(act), 10, 60, 20);
        }
    }

    /** Línea del fin para /pd temporada y /pd actos (fecha real + actor, todos los actos). */
    public String finaleSummary() {
        if (state == null) {
            return "final no disponible";
        }
        int fin = effectiveSeasonLengthDays();
        if (state.isSeasonEnded()) {
            return "Fin día " + fin + " (" + state.finaleStatus() + ", actor " + state.finaleActor() + ")";
        }
        return "Fin día " + fin + " (base " + seasonLengthDays
                + (state.isBankExtensionUsed() ? " +3 extensión" : "") + ")";
    }

    /** Línea corta para {@code /pd temporada}. */
    public String statusLine() {
        int day = currentDay();
        int act = currentAct();
        String summary = objectives == null ? "objetivos no disponibles" : objectives.summary();
        return "§6Temporada §f" + seasonId + " §8• §fActo "
                + SeasonObjectives.Objective.roman(act) + " — " + actName(act)
                + " §8• §fDía " + day + "/" + effectiveSeasonLengthDays()
                + " §8• §aVivos " + aliveN() + "/" + effectiveM()
                + " §8• §7" + summary + " §8• §7" + finaleSummary();
    }

    /** Calendario de actos para {@code /pd actos}. */
    public List<String> actsCalendar() {
        List<String> lines = new ArrayList<>();
        lines.add("§6Calendario de actos §8(temporada " + seasonId + ", " + effectiveSeasonLengthDays()
                + " días, base " + seasonLengthDays + ")");
        int day = currentDay();
        for (int act = 1; act <= SeasonCalendar.ACT_COUNT; act++) {
            int start = SeasonCalendar.actStartDay(act, seasonLengthDays);
            int end = SeasonCalendar.actEndDay(act, seasonLengthDays);
            if (act == SeasonCalendar.ACT_COUNT) {
                end = effectiveSeasonLengthDays();
            }
            String mark = act == currentAct() ? "§a▶ " : "§8  ";
            lines.add(mark + "§fActo " + SeasonObjectives.Objective.roman(act) + " — " + actName(act)
                    + " §8(días " + start + "–" + end + ")"
                    + (state != null && state.isActAnnounced(act) ? " §7[anunciado]" : ""));
        }
        lines.add("§7" + finaleSummary() + " · actor final visible todos los actos." );
        lines.add("§7Día actual: " + day + " · Vivos " + aliveN() + "/" + effectiveM()
                + " · Gate online: " + requiredOnline() + " (40% de M o minimum-online-players si mayor)");
        return lines;
    }

    /** Título de bossbar: acto, día, vivos y próxima fase; visible también con N&lt;4. */
    public String bossbarTitle(int day, int currentPhase, String phaseTitle, int nextPhase) {
        StringBuilder title = new StringBuilder("§cLetumcraft §8• §fActo ")
                .append(SeasonObjectives.Objective.roman(currentAct()))
                .append(" — ").append(actName(currentAct()))
                .append(" §8• §fDía ").append(day)
                .append(" §8• §aVivos ").append(aliveN()).append("/").append(effectiveM())
                .append(" §8• §e").append(phaseTitle);
        if (areEffectsSuspended()) {
            title.append(" §8(§befectos en pausa: N<4§8)");
        }
        if (nextPhase == 101) {
            title.append(" §8(última fase)");
        } else {
            title.append(" §8(próx. fase ").append(nextPhase)
                    .append(" día ").append(seasonPhaseDay(nextPhase)).append(")");
        }
        String paused = "";
        try {
            paused = plugin.days().isPaused() ? " §8(§cPAUSADO§8)" : "";
        } catch (RuntimeException ignored) {
        }
        title.append(paused);
        return title.toString();
    }

    /**
     * Guarda el estado y reporta el éxito. El escrow de B3 no confirma vueltas
     * sin disco durable: las rutas transaccionales usan este resultado para
     * abortar con rollback en vez de contar una vuelta no persistida.
     */
    public boolean persistState() {
        if (state == null) {
            return false;
        }
        if (writeGate.isSealed()) {
            plugin.getLogger().warning("[Temporada] guardado omitido (estado bloqueado, archivo preservado): "
                    + writeGate.reason());
            return false;
        }
        syncStateFromObjectives();
        try {
            store.save(state);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("[Temporada] no se pudo guardar season-state.yml: "
                    + exception.getMessage());
            return false;
        }
    }

    public void saveState() {
        persistState();
    }
}
