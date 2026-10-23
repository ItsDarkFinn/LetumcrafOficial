package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bloque 1.6 — Objetivos de temporada. Modelo puro: <b>sin Bukkit</b> y sin depender de las clases
 * paralelas del bloque (calendario, actos, roster, banco, tienda). Solo java.util.
 *
 * <p>Contrato fijado por el plan (docs/plan-temporada.md, 1.6 y enmiendas):
 * <ul>
 *   <li>una meta por acto, con las metas originales aprobadas (I portal Reserva, II noche sin dormir
 *       + muestra de esporas, III primer Nucleo, IV Fortaleza, V Caceria, VI 3 dias reales con N&lt;5,
 *       VII fondo del banco antes del ultimo dia);</li>
 *   <li>anuncio unico, cumplimiento unico y recompensa unica por objetivo;</li>
 *   <li>evaluacion por eventos y timers, <b>nunca</b> escaneando entidades/mundos;</li>
 *   <li>los objetivos abiertos persisten entre actos: el I (portal) no es posible en los dias 1-3
 *       porque la Reserva se genera al dia 8, asi que ni se adelanta el portal ni se sustituye la
 *       meta;</li>
 *   <li>si el proveedor de recompensa (banco/chispas) no esta conectado, se emite mensaje y el
 *       objetivo queda en {@link Status#COMPLETED}: se puede reintentar despues pero <b>nunca</b> se
 *       paga dos veces, y no se lanza excepcion ni se pierde el estado.</li>
 * </ul>
 *
 * <p>Persistencia: este archivo solo produce/consume un mapa serializable
 * ({@link #snapshot()}/{@link #fromSnapshot}). {@code SeasonStore} lo escribe en
 * {@code season-state.yml} (nunca en {@code config.yml}); al rehidratar no se repiten anuncios,
 * cumplimientos ni pagos porque el estado se restaura antes de cualquier evaluacion.
 *
 * <p>Alimentacion con eventos reales (todo desde el hilo principal del servidor):
 * <pre>
 *   ReservaPortalController (portal de la Reserva generado/encendido)
 *       -&gt; onPortalOpened(uuid, "reserva:reserva")
 *   DayManager (fin de noche real; alguien durmio en el rango noche)
 *       -&gt; onNightEnded(dia, alguienDurmio, uuidTestigo)
 *   ReservaPodrida (recogida de la muestra de esporas)
 *       -&gt; onEsporaSample(uuid)
 *   EclipseCoreItem (primer Nucleo entregado o crafteado)
 *       -&gt; onFirstCore(uuid)
 *   FortressHuntEvent (recompensa desbloqueada tras la 3a oleada)
 *       -&gt; onFortressCompleted(uuid)
 *   NightHuntEvent (caceria cerrada / amanecer)
 *       -&gt; onNightHuntCompleted(uuid)
 *   DayManager (rollover de dia real, N = roster menos eliminados de DeathRegistry)
 *       -&gt; onDayEvaluated(dia, vivosEnRoster)
 *   Banco Eclipse (compra con Nucleos, dia actual y dia final conocidos)
 *       -&gt; onBankFunded(uuid, dia, diaFinal, nucleosAportados)
 *   Calendario/actos (cambio de acto) -&gt; onActChanged(acto)
 *   Recompensas al montar el plugin -&gt; bindRewardProvider(...) y retryPendingRewards()
 * </pre>
 * Ninguno de esos metodos escanea el mundo: cada uno es un unico evento ya existente en el plugin.
 * Los handlers son idempotentes, asi que un doble disparo (por ejemplo dos veces el rollover del
 * mismo dia) no duplica progreso.
 */
public final class SeasonObjectives {

    /** Umbral de vivos del roster: un dia con menos vivos que esto cuenta como dia diezmado. */
    public static final int DIAS_DIEZMADOS_VIVOS = 5;
    /** Dias reales consecutivos con N&lt;5 que cierran el objetivo VI. */
    public static final int DIAS_DIEZMADOS_RACHA = 3;

    /** Reloj inyectado: los hitos se marcan por eventos/timers, nunca por escaneos. */
    @FunctionalInterface
    public interface SeasonClock {
        long nowMillis();
    }

    public static SeasonClock systemClock() {
        return System::currentTimeMillis;
    }

    /** Moneda de la recompensa. {@link #NINGUNA} degrada a mensaje sin pagar nada. */
    public enum Currency {
        NINGUNA,
        CHISPAS,
        NUCLEOS,
        ESQUIRLAS
    }

    /** Recompensa pequena y configurable. {@code itemKey} permite usar material de banco. */
    public record Reward(Currency currency, int amount, String itemKey) {
        public Reward {
            currency = currency == null ? Currency.NINGUNA : currency;
            itemKey = itemKey == null ? "" : itemKey;
            if (amount < 0) {
                amount = 0;
            }
        }

        public static Reward none() {
            return new Reward(Currency.NINGUNA, 0, "");
        }

        public static Reward of(Currency currency, int amount) {
            return new Reward(currency, amount, "");
        }

        public static Reward of(Currency currency, int amount, String itemKey) {
            return new Reward(currency, amount, itemKey);
        }

        public boolean payable() {
            return currency != Currency.NINGUNA && amount > 0;
        }

        public String describe() {
            if (!payable()) {
                return "sin recompensa";
            }
            String label = currency.name().toLowerCase(Locale.ROOT);
            return amount + " " + label + (itemKey.isBlank() ? "" : " (" + itemKey + ")");
        }
    }

    /**
     * Recompensas por objetivo. Defaults pequenos (1 Esquirla). La integracion las sobrescribe desde
     * config sin recompilar: {@code with(...)} / {@code without(...)}.
     */
    public static final class RewardConfig {
        private final EnumMap<Objective, Reward> rewards = new EnumMap<>(Objective.class);

        private RewardConfig() {
            for (Objective objective : Objective.values()) {
                rewards.put(objective, Reward.of(Currency.ESQUIRLAS, 1));
            }
        }

        public static RewardConfig defaults() {
            return new RewardConfig();
        }

        public Reward rewardFor(Objective objective) {
            Reward reward = rewards.get(objective);
            return reward == null ? Reward.none() : reward;
        }

        public RewardConfig with(Objective objective, Reward reward) {
            Objects.requireNonNull(objective, "objective");
            rewards.put(objective, reward == null ? Reward.none() : reward);
            return this;
        }

        public RewardConfig without(Objective objective) {
            return with(objective, Reward.none());
        }
    }

    /**
     * Proveedor de la recompensa (banco/chispas/entrega). Devuelve {@code true} solo si el pago se
     * aplico de verdad. Si es {@code null} o devuelve {@code false}, el objetivo queda cumplido y el
     * pago pendiente: se puede reintentar, nunca se duplica.
     */
    @FunctionalInterface
    public interface RewardProvider {
        boolean grant(UUID actor, Reward reward);
    }

    /** Caja de salida para bossbar/chat/log. Nada lanza: un evento nunca debe romper el plugin. */
    public interface Listener {
        default void onAnnounce(Objective objective, String message) {
        }

        default void onComplete(Objective objective, UUID actor, String message) {
        }

        default void onReward(Objective objective, Reward reward, boolean granted, UUID actor, String message) {
        }

        default void onProgress(Objective objective, String message) {
        }

        static Listener silent() {
            return new Listener() {
            };
        }
    }

    /** Metas originales aprobadas. El ordinal + 1 es el acto (I..VII). */
    public enum Objective {
        PORTAL_RESERVA(1, "Abrir el portal de la Reserva"),
        NOCHE_INSOMNE(2, "Pasar una noche sin dormir y muestrear esporas"),
        PRIMER_NUCLEO(3, "Conseguir el primer Nucleo de Eclipse"),
        FORTALEZA(4, "Completar la Fortaleza"),
        CACERIA(5, "Completar la Caceria Nocturna"),
        TRES_DIAS_DIEZMADOS(6, "Sobrevivir 3 dias reales con menos de 5 vivos"),
        BANCO_ANTES_DEL_FINAL(7, "Fondear el Banco Eclipse antes del ultimo dia");

        private final int act;
        private final String description;

        Objective(int act, String description) {
            this.act = act;
            this.description = description;
        }

        public int act() {
            return act;
        }

        public String description() {
            return description;
        }

        public String announcement() {
            return "Objetivo del acto " + roman(act) + ": " + description;
        }

        public static String roman(int act) {
            return switch (act) {
                case 1 -> "I";
                case 2 -> "II";
                case 3 -> "III";
                case 4 -> "IV";
                case 5 -> "V";
                case 6 -> "VI";
                case 7 -> "VII";
                default -> String.valueOf(act);
            };
        }
    }

    /** Ciclo de vida de un objetivo: anunciar -&gt; cumplir -&gt; pagar, cada paso una sola vez. */
    public enum Status {
        PENDING,
        ANNOUNCED,
        COMPLETED,
        REWARDED;

        static Status parse(Object raw, Status fallback) {
            if (raw instanceof String text) {
                for (Status status : values()) {
                    if (status.name().equalsIgnoreCase(text.trim())) {
                        return status;
                    }
                }
            }
            return fallback;
        }
    }

    /** Sub-condiciones del objetivo II; nunca se borra una ya lograda. */
    private enum Flag {
        NOCHE_SIN_DORMIR,
        ESPORA_MUESTREADA;

        static Flag parse(Object raw) {
            if (raw instanceof String text) {
                for (Flag flag : values()) {
                    if (flag.name().equalsIgnoreCase(text.trim())) {
                        return flag;
                    }
                }
            }
            return null;
        }
    }

    private static final class Progress {
        private Status status = Status.PENDING;
        private long announcedAt;
        private long completedAt;
        private long rewardedAt;
        private String actor = "";
        private final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        private int streak;
        private int lastStreakDay = -1;
        private int lastNightDay = -1;

        private boolean done() {
            return status == Status.COMPLETED || status == Status.REWARDED;
        }

        private void load(Map<?, ?> entry) {
            status = Status.parse(entry.get("status"), Status.PENDING);
            announcedAt = toLong(entry.get("announcedAt"), 0L);
            completedAt = toLong(entry.get("completedAt"), 0L);
            rewardedAt = toLong(entry.get("rewardedAt"), 0L);
            actor = str(entry.get("actor"), "");
            flags.clear();
            if (entry.get("flags") instanceof Iterable<?> raw) {
                for (Object item : raw) {
                    Flag flag = Flag.parse(item);
                    if (flag != null) {
                        flags.add(flag);
                    }
                }
            }
            streak = Math.max(0, toInt(entry.get("streak"), 0));
            lastStreakDay = toInt(entry.get("lastStreakDay"), -1);
            lastNightDay = toInt(entry.get("lastNightDay"), -1);
            if (status == Status.REWARDED && rewardedAt == 0L) {
                rewardedAt = completedAt;
            }
            if (status != Status.PENDING && announcedAt == 0L) {
                announcedAt = completedAt == 0L ? 1L : completedAt;
            }
        }
    }

    private final RewardConfig config;
    private final SeasonClock clock;
    private final Listener listener;
    private final EnumMap<Objective, Progress> progress = new EnumMap<>(Objective.class);
    private final String seasonId;
    private int currentAct;
    private RewardProvider provider;

    private SeasonObjectives(String seasonId, int initialAct, RewardConfig config, SeasonClock clock, Listener listener) {
        this.seasonId = Objects.requireNonNull(seasonId, "seasonId de temporada obligatorio (season-state.yml)");
        this.config = config == null ? RewardConfig.defaults() : config;
        this.clock = clock == null ? systemClock() : clock;
        this.listener = listener == null ? Listener.silent() : listener;
        for (Objective objective : Objective.values()) {
            progress.put(objective, new Progress());
        }
        if (initialAct >= 1) {
            onActChanged(initialAct);
        }
    }

    /** Temporada nueva: anuncia el objetivo del acto inicial (una sola vez). */
    public static SeasonObjectives forNewSeason(String seasonId, int initialAct, RewardConfig config,
                                                SeasonClock clock, Listener listener) {
        return new SeasonObjectives(seasonId, Math.max(1, initialAct), config, clock, listener);
    }

    /**
     * Rehidratacion desde {@code season-state.yml}. Tolerante a datos corruptos o incompletos: nunca
     * lanza y nunca borra en silencio; lo que falta vuelve a su valor inicial de forma segura.
     * No reanuncia lo ya anunciado, no recumple lo cumplido y no vuelve a pagar.
     */
    public static SeasonObjectives fromSnapshot(Map<?, ?> snapshot, String expectedSeasonId,
                                                RewardConfig config, SeasonClock clock, Listener listener) {
        Objects.requireNonNull(expectedSeasonId, "seasonId");
        String storedSeasonId = snapshot == null ? expectedSeasonId : str(snapshot.get("seasonId"), expectedSeasonId);
        SeasonObjectives model = new SeasonObjectives(storedSeasonId, 0, config, clock, listener);
        int savedAct = snapshot == null ? 0 : toInt(snapshot.get("currentAct"), 0);
        if (snapshot != null && snapshot.get("objectives") instanceof Map<?, ?> objectives) {
            for (Objective objective : Objective.values()) {
                if (objectives.get(objective.name()) instanceof Map<?, ?> entry) {
                    model.progress.get(objective).load(entry);
                }
            }
        }
        // Se parte de acto 0 para que el anuncio de lo pendiente ocurra ahora, sin repetir lo ya
        // anunciado (el estado restaurado lo garantiza).
        model.onActChanged(Math.max(1, savedAct));
        return model;
    }

    // ------------------------------------------------------------------ consulta

    public String seasonId() {
        return seasonId;
    }

    public int currentAct() {
        return currentAct;
    }

    public RewardConfig rewardConfig() {
        return config;
    }

    public Status status(Objective objective) {
        return progress.get(Objects.requireNonNull(objective, "objective")).status;
    }

    public boolean isCompleted(Objective objective) {
        return progress.get(Objects.requireNonNull(objective, "objective")).done();
    }

    public boolean isRewarded(Objective objective) {
        return status(objective) == Status.REWARDED;
    }

    public int streak(Objective objective) {
        return progress.get(Objects.requireNonNull(objective, "objective")).streak;
    }

    public int currentStreak() {
        return progress.get(Objective.TRES_DIAS_DIEZMADOS).streak;
    }

    /** Objetivos anunciados y aun sin cumplir, en orden de acto. Persisten entre actos. */
    public List<Objective> openObjectives() {
        List<Objective> open = new ArrayList<>();
        for (Objective objective : Objective.values()) {
            Status status = progress.get(objective).status;
            if (status == Status.ANNOUNCED || status == Status.PENDING) {
                open.add(objective);
            }
        }
        return List.copyOf(open);
    }

    /** Objetivos cumplidos cuyo pago aun no se aplico (proveedor ausente o no disponible). */
    public List<Objective> pendingRewards() {
        List<Objective> pending = new ArrayList<>();
        for (Objective objective : Objective.values()) {
            if (progress.get(objective).status == Status.COMPLETED) {
                pending.add(objective);
            }
        }
        return List.copyOf(pending);
    }

    /** Linea corta para {@code /pd temporada}: objetivo abierto mas antiguo + racha. */
    public String summary() {
        StringBuilder text = new StringBuilder("Objetivos del acto ").append(Objective.roman(Math.max(1, currentAct)));
        List<Objective> open = openObjectives();
        if (open.isEmpty()) {
            text.append(" · todos cumplidos");
        } else {
            text.append(" · abierto: ").append(open.get(0).description());
            if (open.size() > 1) {
                text.append(" (+").append(open.size() - 1).append(" abiertos de actos previos)");
            }
        }
        Progress viernes = progress.get(Objective.TRES_DIAS_DIEZMADOS);
        if (viernes.streak > 0 && !viernes.done()) {
            text.append(" · racha diezmados ").append(viernes.streak).append('/').append(DIAS_DIEZMADOS_RACHA);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ eventos

    /** Cambio de acto. Anuncia lo pendiente de los actos alcanzados; jamas completa nada. */
    public void onActChanged(int act) {
        int target = Math.max(1, act);
        if (target <= currentAct) {
            return;
        }
        currentAct = target;
        announceReached();
    }

    private void announceReached() {
        for (Objective objective : Objective.values()) {
            if (objective.act() > currentAct) {
                continue;
            }
            Progress state = progress.get(objective);
            if (state.status != Status.PENDING) {
                continue;
            }
            state.status = Status.ANNOUNCED;
            state.announcedAt = clock.nowMillis();
            listener.onAnnounce(objective, objective.announcement());
        }
    }

    /**
     * Portal de la Reserva abierto. Solo cuenta si el mundo es la Reserva: la meta I no se sustituye
     * ni se adelanta por abrir otros portales.
     */
    public void onPortalOpened(UUID actor, String dimensionKey) {
        if (dimensionKey == null) {
            return;
        }
        if (!dimensionKey.toLowerCase(Locale.ROOT).contains("reserva")) {
            listener.onProgress(Objective.PORTAL_RESERVA,
                    "Portal fuera de la Reserva: el objetivo del acto I sigue siendo el portal de la Reserva");
            return;
        }
        complete(Objective.PORTAL_RESERVA, actor, "portal de la Reserva abierto");
    }

    /**
     * Fin de una noche real. Si alguien durmio, esa noche no cuenta (pero no borra una noche valida
     * ya registrada). Un mismo dia solo se evalua una vez.
     */
    public void onNightEnded(int dayIndex, boolean alguienDurmio, UUID actor) {
        Objective objective = Objective.NOCHE_INSOMNE;
        Progress state = progress.get(objective);
        if (state.done() || dayIndex <= 0 || dayIndex == state.lastNightDay) {
            return;
        }
        state.lastNightDay = dayIndex;
        if (alguienDurmio) {
            listener.onProgress(objective, "La noche del dia " + dayIndex + " no cuenta: alguien durmio");
            return;
        }
        state.flags.add(Flag.NOCHE_SIN_DORMIR);
        tryCompleteNight(actor);
    }

    /** Muestra de esporas recogida. Puede llegar antes o despues de la noche sin dormir. */
    public void onEsporaSample(UUID actor) {
        Progress state = progress.get(Objective.NOCHE_INSOMNE);
        if (state.done()) {
            return;
        }
        state.flags.add(Flag.ESPORA_MUESTREADA);
        tryCompleteNight(actor);
    }

    private void tryCompleteNight(UUID actor) {
        Progress state = progress.get(Objective.NOCHE_INSOMNE);
        if (state.done()) {
            return;
        }
        boolean noche = state.flags.contains(Flag.NOCHE_SIN_DORMIR);
        boolean espora = state.flags.contains(Flag.ESPORA_MUESTREADA);
        if (noche && espora) {
            complete(Objective.NOCHE_INSOMNE, actor, "noche sin dormir + muestra de esporas");
            return;
        }
        if (espora) {
            listener.onProgress(Objective.NOCHE_INSOMNE, "Esporas listas; falta pasar una noche entera sin que nadie duerma");
        } else if (noche) {
            listener.onProgress(Objective.NOCHE_INSOMNE, "Noche sin dormir registrada; falta la muestra de esporas");
        }
    }

    /** Primer Nucleo de Eclipse obtenido. */
    public void onFirstCore(UUID actor) {
        complete(Objective.PRIMER_NUCLEO, actor, "primer Nucleo de Eclipse");
    }

    /** Fortaleza completada (recompensa de la 3a oleada desbloqueada). */
    public void onFortressCompleted(UUID actor) {
        complete(Objective.FORTALEZA, actor, "Fortaleza completada");
    }

    /** Caceria Nocturna completada. */
    public void onNightHuntCompleted(UUID actor) {
        complete(Objective.CACERIA, actor, "Caceria Nocturna completada");
    }

    /**
     * Rollover de dia real con N = vivos del roster menos eliminados (nunca conectados). Racha de
     * {@value #DIAS_DIEZMADOS_RACHA} dias con N&lt;{@value #DIAS_DIEZMADOS_VIVOS}; se corta en cuanto
     * N vuelve a ser 5 o mas. Idempotente por dia (un doble rollover no suma dos veces).
     */
    public void onDayEvaluated(int dayIndex, int vivosEnRoster) {
        Objective objective = Objective.TRES_DIAS_DIEZMADOS;
        Progress state = progress.get(objective);
        if (dayIndex <= 0 || dayIndex == state.lastStreakDay) {
            return;
        }
        boolean yaCumplido = state.done();
        state.lastStreakDay = dayIndex;
        if (vivosEnRoster < DIAS_DIEZMADOS_VIVOS) {
            if (yaCumplido) {
                return;
            }
            state.streak = Math.min(DIAS_DIEZMADOS_RACHA, state.streak + 1);
            if (state.streak >= DIAS_DIEZMADOS_RACHA) {
                complete(objective, null, "3 dias reales seguidos con menos de 5 vivos");
            } else {
                listener.onProgress(objective, "Racha diezmados " + state.streak + '/' + DIAS_DIEZMADOS_RACHA
                        + " (se corta al volver a " + DIAS_DIEZMADOS_VIVOS + " vivos o mas)");
            }
            return;
        }
        if (state.streak > 0) {
            state.streak = 0;
            if (!yaCumplido) {
                listener.onProgress(objective, "Racha cortada: el roster vuelve a " + vivosEnRoster + " vivos");
            }
        }
    }

    /**
     * Fondo del Banco Eclipse. Solo cumple si hay aporte real de Nucleos y es <b>antes</b> del ultimo
     * dia; si llega el ultimo dia, se avisa y la meta sigue abierta (no se sustituye).
     */
    public void onBankFunded(UUID actor, int dayIndex, int finalDay, long nucleosAportados) {
        Objective objective = Objective.BANCO_ANTES_DEL_FINAL;
        if (nucleosAportados <= 0) {
            return;
        }
        if (finalDay > 0 && dayIndex >= finalDay) {
            listener.onProgress(objective, "El fondo llego el ultimo dia: la meta pedia fondear antes");
            return;
        }
        complete(objective, actor, "fondo de " + nucleosAportados + " Nucleos antes del dia final");
    }

    // ------------------------------------------------------------------ recompensas

    /** Conecta (o desconecta con {@code null}) el proveedor de pagos: banco, chispas o entrega. */
    public SeasonObjectives bindRewardProvider(RewardProvider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Reintenta los pagos pendientes. Seguro de llamar en cada arranque o cada dia: solo paga lo que
     * sigue en {@link Status#COMPLETED}, y el exito lo pasa a {@link Status#REWARDED} en el acto.
     *
     * @return cuantos pagos se aplicaron en esta llamada
     */
    public int retryPendingRewards() {
        int granted = 0;
        for (Objective objective : Objective.values()) {
            if (progress.get(objective).status == Status.COMPLETED && grant(objective)) {
                granted++;
            }
        }
        return granted;
    }

    // ------------------------------------------------------------------ interno

    private boolean complete(Objective objective, UUID actor, String reason) {
        Progress state = progress.get(objective);
        if (state.done()) {
            return false;
        }
        state.status = Status.COMPLETED;
        state.completedAt = clock.nowMillis();
        if (state.announcedAt == 0L) {
            state.announcedAt = state.completedAt;
        }
        state.actor = actor == null ? "" : actor.toString();
        listener.onComplete(objective, actor,
                "Objetivo cumplido — " + objective.description() + " (" + reason + ")");
        grant(objective);
        return true;
    }

    private boolean grant(Objective objective) {
        Progress state = progress.get(objective);
        if (state.status != Status.COMPLETED) {
            return false;
        }
        Reward reward = config.rewardFor(objective);
        UUID actor = parseUuid(state.actor);
        if (!reward.payable()) {
            state.status = Status.REWARDED;
            state.rewardedAt = clock.nowMillis();
            listener.onReward(objective, reward, false, actor,
                    "Objetivo cumplido sin recompensa configurada — " + objective.description());
            return true;
        }
        RewardProvider current = provider;
        if (current == null) {
            listener.onReward(objective, reward, false, actor,
                    "Recompensa pendiente (" + reward.describe() + "): no hay proveedor de "
                            + reward.currency().name().toLowerCase(Locale.ROOT) + " conectado; se reintentara");
            return false;
        }
        boolean granted;
        String failure = null;
        try {
            granted = current.grant(actor, reward);
        } catch (RuntimeException exception) {
            granted = false;
            failure = exception.getClass().getSimpleName();
        }
        if (!granted) {
            // Ni excepcion ni pago: el objetivo sigue COMPLETED y el pago pendiente. Nunca se paga
            // dos veces porque el exito pasa a REWARDED antes de cualquier otro intento.
            listener.onReward(objective, reward, false, actor,
                    "Recompensa pendiente (" + reward.describe() + "): proveedor no disponible"
                            + (failure == null ? "" : " (" + failure + ")") + "; se reintentara");
            return false;
        }
        state.status = Status.REWARDED;
        state.rewardedAt = clock.nowMillis();
        listener.onReward(objective, reward, true, actor,
                "Objetivo cumplido — " + objective.description() + " · recibes " + reward.describe());
        return true;
    }

    // ------------------------------------------------------------------ persistencia

    /** Estado serializable para {@code season-state.yml} (strings, numeros, listas y mapas). */
    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seasonId", seasonId);
        root.put("currentAct", currentAct);
        Map<String, Object> objectives = new LinkedHashMap<>();
        for (Objective objective : Objective.values()) {
            Progress state = progress.get(objective);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", state.status.name());
            entry.put("announcedAt", state.announcedAt);
            entry.put("completedAt", state.completedAt);
            entry.put("rewardedAt", state.rewardedAt);
            entry.put("actor", state.actor);
            List<String> flags = new ArrayList<>();
            for (Flag flag : state.flags) {
                flags.add(flag.name());
            }
            entry.put("flags", flags);
            entry.put("streak", state.streak);
            entry.put("lastStreakDay", state.lastStreakDay);
            entry.put("lastNightDay", state.lastNightDay);
            objectives.put(objective.name(), entry);
        }
        root.put("objectives", objectives);
        return root;
    }

    // ------------------------------------------------------------------ helpers

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String str(Object value, String fallback) {
        if (value instanceof String text) {
            return text.isBlank() ? fallback : text.trim();
        }
        return fallback;
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
