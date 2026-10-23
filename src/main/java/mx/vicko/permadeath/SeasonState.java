package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Estado transaccional de temporada (POJO puro, sin Bukkit ni IO).
 *
 * <p>Persiste en {@code season-state.yml} via {@link SeasonStore}, nunca en
 * {@code config.yml}. Lleva {@code seasonId} + UUID-logica de objetivos en
 * forma de ids String (no depende de ninguna clase SeasonObjectives de otro
 * agente). Todos los marcajes son idempotentes: repetirlos devuelve
 * {@code false} y no duplica nada.
 */
public final class SeasonState {

    private final String seasonId;
    private int day;
    private int seasonLengthDays;
    private final Set<Integer> announcedActs = new LinkedHashSet<>();
    private final Set<String> announcedObjectives = new LinkedHashSet<>();
    private final Set<String> completedObjectives = new LinkedHashSet<>();
    private final Set<String> paidRewards = new LinkedHashSet<>();
    private final Map<String, ObjectiveDetail> objectiveDetails = new LinkedHashMap<>();
    // Bloque 2: sacrificios irreversibles por UUID (id TYPE) + banco colectivo + Caída del Sol + Ancla.
    private final Map<String, String> sacrifices = new LinkedHashMap<>();
    private int bankBalance;
    private int bankTotalDeposited;
    private int bankTotalSpent;
    private boolean bankExtensionUsed;
    private boolean bankRitualOpen;
    private boolean bankSunReserved;
    private final Set<String> bankTxIds = new LinkedHashSet<>();
    private final Map<String, Integer> bankEscrow = new LinkedHashMap<>();
    private boolean solarFallDone;
    private final Map<String, String> anchorDeaths = new LinkedHashMap<>();
    private final Set<String> anchorUsed = new LinkedHashSet<>();
    // Bloque 3 — La Fosa: ledger personal por UUID (ganadas/gastadas/vueltas,
    // último día real de ganancia y muerte para la válvula de 5 días). scoped por seasonId.
    private final Map<String, Integer> pitEarned = new LinkedHashMap<>();
    private final Map<String, Integer> pitSpent = new LinkedHashMap<>();
    private final Map<String, Integer> revivals = new LinkedHashMap<>();
    private final Map<String, Long> pitLastEarnDay = new LinkedHashMap<>();
    private final Map<String, Long> seasonDeathTimes = new LinkedHashMap<>();
    // Bloque 3 — escrow de vuelta: intento persistido ANTES de tocar DeathRegistry
    // (coste retenido + peaje + muerte previa + vueltas base para commit idempotente).
    // Un crash entre cobro y registro se reconcilia al arrancar, al reintentar o
    // al unirse: muerto = rollback, vivo = commit (asegura base+1, no recuenta).
    private final Map<String, Integer> reviveTxCost = new LinkedHashMap<>();
    private final Map<String, Long> reviveTxDeath = new LinkedHashMap<>();
    private final Set<String> reviveTxCore = new LinkedHashSet<>();
    private final Map<String, Integer> reviveTxBase = new LinkedHashMap<>();
    // Bloque 4 — Final único RUNNING→VICTORY/DEFEAT + stats por hooks (join/muerte).
    private String finaleStatus = "RUNNING";
    private int finaleDay;
    private String finaleActor = "";
    private String finaleReason = "";
    private final Map<String, Integer> firstSeenDay = new LinkedHashMap<>();
    private final Map<String, Integer> lifetimeDeaths = new LinkedHashMap<>();
    // Bloque 4 — foto fija del sellado: /pd final la reproduce sin recalcular
    // contra el estado vivo (las muertes/hooks post-END no la alteran).
    private boolean finaleSnapshot = false;
    private int finaleAliveN;
    private int finaleAliveM;
    private final java.util.List<String> finaleAliveNames = new java.util.ArrayList<>();
    private final Map<String, Integer> finaleLifetimeDeaths = new LinkedHashMap<>();
    private final Map<String, Integer> finaleFirstSeenDays = new LinkedHashMap<>();
    private int finaleBankDeposited;
    private int finaleBankSpent;
    private final java.util.List<String> finaleEvents = new java.util.ArrayList<>();

    public SeasonState(String seasonId) {
        this(seasonId, 1, SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS);
    }

    public SeasonState(String seasonId, int day, int seasonLengthDays) {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId obligatorio (no usar defaults que mezclen temporadas)");
        }
        this.seasonId = seasonId;
        this.seasonLengthDays = SeasonCalendar.normalizeSeasonLength(seasonLengthDays);
        setDay(day);
    }

    public String seasonId() {
        return seasonId;
    }

    /** Bloque 4 — estado del final (RUNNING/VICTORY/DEFEAT, una sola vez). */
    public String finaleStatus() {
        return SeasonFinale.normalizeStatus(finaleStatus);
    }

    public int finaleDay() {
        return finaleDay;
    }

    public String finaleActor() {
        return finaleActor == null ? "" : finaleActor;
    }

    public String finaleReason() {
        return finaleReason == null ? "" : finaleReason;
    }

    public boolean isSeasonEnded() {
        return SeasonFinale.isEnded(finaleStatus());
    }

    /**
     * Transición única RUNNING → VICTORY/DEFEAT. {@code false} si ya terminó,
     * si el estado es inválido o si intenta volver a RUNNING (incluido
     * {@code /pd dia} hacia atrás o reinicios: nunca reabre).
     */
    public boolean setFinale(String status, int day, String actor, String reason) {
        String next = SeasonFinale.normalizeStatus(status);
        if (isSeasonEnded() || SeasonFinale.Status.RUNNING.name().equals(next)) {
            return false;
        }
        this.finaleStatus = next;
        this.finaleDay = Math.max(1, day);
        this.finaleActor = actor == null ? "" : actor;
        this.finaleReason = reason == null ? "" : reason;
        return true;
    }

    /** Carga tolerante desde season-state.yml (ilegible cae a RUNNING). */
    public void loadFinale(String status, int day, String actor, String reason) {
        String next = SeasonFinale.normalizeStatus(status);
        this.finaleStatus = next;
        this.finaleDay = Math.max(0, day);
        this.finaleActor = actor == null ? "" : actor;
        this.finaleReason = reason == null ? "" : reason;
    }

    /** Primer día visto por UUID (solo el primero cuenta; hooks de join). */
    public Map<String, Integer> firstSeenDays() {
        return new LinkedHashMap<>(firstSeenDay);
    }

    public void noteFirstSeen(String uuid, int day) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        firstSeenDay.putIfAbsent(uuid.trim(), Math.max(1, day));
    }

    public void loadFirstSeen(Map<String, Integer> values) {
        firstSeenDay.clear();
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
                firstSeenDay.put(e.getKey().trim(), e.getValue());
            }
        }
    }

    /** Muertes de por vida por UUID (solo crece; revivir no la borra). */
    public Map<String, Integer> lifetimeDeaths() {
        return new LinkedHashMap<>(lifetimeDeaths);
    }

    public void addLifetimeDeath(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        String key = uuid.trim();
        lifetimeDeaths.put(key, Math.max(0, lifetimeDeaths.getOrDefault(key, 0)) + 1);
    }

    public void loadLifetimeDeaths(Map<String, Integer> values) {
        lifetimeDeaths.clear();
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
                lifetimeDeaths.put(e.getKey().trim(), e.getValue());
            }
        }
    }

    /**
     * Bloque 4 — congela la foto del sellado (una sola vez, antes del broadcast).
     * Copias defensivas: lo que pase después (muertes, hooks, reload) no cambia
     * la foto. {@code false} si ya había foto o el final no está sellado.
     */
    public boolean freezeFinale(int aliveN, int aliveM, java.util.List<String> aliveNames,
            Map<String, Integer> deaths, Map<String, Integer> firstSeen,
            int bankDeposited, int bankSpent, java.util.List<String> events) {
        if (finaleSnapshot || !isSeasonEnded()) {
            return false;
        }
        this.finaleAliveN = Math.max(0, aliveN);
        this.finaleAliveM = Math.max(0, aliveM);
        this.finaleAliveNames.clear();
        if (aliveNames != null) {
            java.util.List<String> sorted = new java.util.ArrayList<>(aliveNames);
            java.util.Collections.sort(sorted);
            this.finaleAliveNames.addAll(sorted);
        }
        this.finaleLifetimeDeaths.clear();
        if (deaths != null) {
            this.finaleLifetimeDeaths.putAll(deaths);
        }
        this.finaleFirstSeenDays.clear();
        if (firstSeen != null) {
            this.finaleFirstSeenDays.putAll(firstSeen);
        }
        this.finaleBankDeposited = Math.max(0, bankDeposited);
        this.finaleBankSpent = Math.max(0, bankSpent);
        this.finaleEvents.clear();
        if (events != null) {
            java.util.List<String> sorted = new java.util.ArrayList<>(events);
            java.util.Collections.sort(sorted);
            this.finaleEvents.addAll(sorted);
        }
        this.finaleSnapshot = true;
        return true;
    }

    /** ¿Hay foto del sellado? (sin foto, /pd final usa el estado vivo como antes). */
    public boolean hasFinaleSnapshot() {
        return finaleSnapshot;
    }

    public int finaleAliveN() {
        return finaleAliveN;
    }

    public int finaleAliveM() {
        return finaleAliveM;
    }

    public java.util.List<String> finaleAliveNames() {
        return new java.util.ArrayList<>(finaleAliveNames);
    }

    public Map<String, Integer> finaleLifetimeDeaths() {
        return new LinkedHashMap<>(finaleLifetimeDeaths);
    }

    public Map<String, Integer> finaleFirstSeenDays() {
        return new LinkedHashMap<>(finaleFirstSeenDays);
    }

    public int finaleBankDeposited() {
        return finaleBankDeposited;
    }

    public int finaleBankSpent() {
        return finaleBankSpent;
    }

    public java.util.List<String> finaleEvents() {
        return new java.util.ArrayList<>(finaleEvents);
    }

    /** Carga tolerante de la foto desde season-state.yml (claves ausentes = sin foto). */
    public void loadFinaleSnapshot(boolean present, int aliveN, int aliveM,
            java.util.List<String> aliveNames, Map<String, Integer> deaths,
            Map<String, Integer> firstSeen, int bankDeposited, int bankSpent,
            java.util.List<String> events) {
        finaleSnapshot = false;
        finaleAliveN = 0;
        finaleAliveM = 0;
        finaleAliveNames.clear();
        finaleLifetimeDeaths.clear();
        finaleFirstSeenDays.clear();
        finaleBankDeposited = 0;
        finaleBankSpent = 0;
        finaleEvents.clear();
        if (!present) {
            return;
        }
        finaleAliveN = Math.max(0, aliveN);
        finaleAliveM = Math.max(0, aliveM);
        if (aliveNames != null) {
            finaleAliveNames.addAll(aliveNames);
            java.util.Collections.sort(finaleAliveNames);
        }
        if (deaths != null) {
            for (Map.Entry<String, Integer> e : deaths.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
                    finaleLifetimeDeaths.put(e.getKey().trim(), e.getValue());
                }
            }
        }
        if (firstSeen != null) {
            for (Map.Entry<String, Integer> e : firstSeen.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
                    finaleFirstSeenDays.put(e.getKey().trim(), e.getValue());
                }
            }
        }
        finaleBankDeposited = Math.max(0, bankDeposited);
        finaleBankSpent = Math.max(0, bankSpent);
        if (events != null) {
            for (String e : events) {
                if (e != null && !e.isBlank()) {
                    finaleEvents.add(e.trim());
                }
            }
            java.util.Collections.sort(finaleEvents);
        }
        finaleSnapshot = true;
    }

    public int day() {
        return day;
    }

    public int seasonLengthDays() {
        return seasonLengthDays;
    }

    public void setDay(int day) {
        this.day = Math.min(Math.max(1, day), seasonLengthDays);
    }

    public void setSeasonLengthDays(int seasonLengthDays) {
        this.seasonLengthDays = SeasonCalendar.normalizeSeasonLength(seasonLengthDays);
        setDay(day);
    }

    public Set<Integer> announcedActs() {
        return Collections.unmodifiableSet(announcedActs);
    }

    public Set<String> announcedObjectives() {
        return Collections.unmodifiableSet(announcedObjectives);
    }

    public Set<String> completedObjectives() {
        return Collections.unmodifiableSet(completedObjectives);
    }

    public Set<String> paidRewards() {
        return Collections.unmodifiableSet(paidRewards);
    }

    /** Elección de sacrificio del UUID (id TYPE) o vacío si no eligió. */
    public java.util.Optional<String> sacrificeChoice(java.util.UUID uuid) {
        if (uuid == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(sacrifices.get(uuid.toString()));
    }

    /** Vista de solo lectura uuid-string -&gt; TYPE-id. */
    public Map<String, String> sacrifices() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sacrifices));
    }

    /**
     * Registra la elección irreversible. Primera vez {@code true}; si el UUID ya eligió
     * (aun tras muerte/revive/reinicio) devuelve {@code false} sin cambios.
     */
    public boolean setSacrifice(java.util.UUID uuid, SacrificeType type) {
        if (uuid == null || type == null) {
            return false;
        }
        String key = uuid.toString();
        if (sacrifices.containsKey(key)) {
            return false;
        }
        sacrifices.put(key, type.id());
        return true;
    }

    /** Rehidratación tolerante desde SeasonStore (ids desconocidos se omiten). */
    public void loadSacrifices(Map<String, String> raw) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            try {
                java.util.UUID.fromString(entry.getKey().trim());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (SacrificeType.fromId(entry.getValue()) != null) {
                sacrifices.putIfAbsent(entry.getKey().trim(), SacrificeType.fromId(entry.getValue()).id());
            }
        }
    }

    public int bankBalance() {
        return bankBalance;
    }

    public int bankTotalDeposited() {
        return bankTotalDeposited;
    }

    public int bankTotalSpent() {
        return bankTotalSpent;
    }

    public boolean isBankExtensionUsed() {
        return bankExtensionUsed;
    }

    public boolean isBankRitualOpen() {
        return bankRitualOpen;
    }

    public boolean isBankSunReserved() {
        return bankSunReserved;
    }

    public Set<String> bankTxIds() {
        return Collections.unmodifiableSet(bankTxIds);
    }

    public Map<String, Integer> bankEscrow() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bankEscrow));
    }

    public void setBankBalance(int v) {
        bankBalance = Math.max(0, v);
    }

    public void setBankTotalDeposited(int v) {
        bankTotalDeposited = Math.max(0, v);
    }

    public void setBankTotalSpent(int v) {
        bankTotalSpent = Math.max(0, v);
    }

    public void setBankExtensionUsed(boolean v) {
        bankExtensionUsed = v;
    }

    public void setBankRitualOpen(boolean v) {
        bankRitualOpen = v;
    }

    public void setBankSunReserved(boolean v) {
        bankSunReserved = v;
    }

    public void addBankTx(String txId) {
        if (txId != null && !txId.isBlank()) {
            bankTxIds.add(txId.trim());
        }
    }

    public void loadBankTxIds(java.util.Collection<String> txIds) {
        if (txIds == null) {
            return;
        }
        for (String tx : txIds) {
            addBankTx(tx);
        }
    }

    public void putBankEscrow(String txId, int amount) {
        if (txId != null && !txId.isBlank() && amount > 0) {
            bankEscrow.put(txId.trim(), amount);
        }
    }

    public Integer removeBankEscrow(String txId) {
        if (txId == null) {
            return null;
        }
        return bankEscrow.remove(txId.trim());
    }

    public void loadBankEscrow(Map<String, Integer> raw) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            if (e.getValue() != null) {
                putBankEscrow(e.getKey(), e.getValue());
            }
        }
    }

    public boolean isSolarFallDone() {
        return solarFallDone;
    }

    public void setSolarFallDone(boolean v) {
        solarFallDone = v;
    }

    public Map<String, String> anchorDeaths() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(anchorDeaths));
    }

    public void putAnchorDeath(java.util.UUID uuid, String packed) {
        if (uuid != null && packed != null && !packed.isBlank()) {
            anchorDeaths.put(uuid.toString(), packed);
        }
    }

    public String takeAnchorDeath(java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return anchorDeaths.remove(uuid.toString());
    }

    /**
     * Bloque 2 — Ancla: lectura sin consumo. Devuelve el destino empaquetado
     * sin borrarlo; el borrado solo ocurre tras validar y resolver el mundo.
     */
    public String peekAnchorDeath(java.util.UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return anchorDeaths.get(uuid.toString());
    }

    public void loadAnchorDeaths(Map<String, String> raw) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                java.util.UUID.fromString(e.getKey().trim());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (e.getValue() != null && !e.getValue().isBlank()) {
                anchorDeaths.putIfAbsent(e.getKey().trim(), e.getValue());
            }
        }
    }

    public boolean isAnchorUsed(java.util.UUID uuid) {
        return uuid != null && anchorUsed.contains(uuid.toString());
    }

    public void markAnchorUsed(java.util.UUID uuid) {
        if (uuid != null) {
            anchorUsed.add(uuid.toString());
        }
    }

    public Set<String> anchorUsed() {
        return Collections.unmodifiableSet(anchorUsed);
    }

    // ---------------------------------------------------------- Bloque 3: Fosa y Chispas

    /** Chispas ganadas por el UUID en esta temporada (tope 6, no lo reabre gastar). */
    public int pitEarnedOf(java.util.UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return Math.max(0, pitEarned.getOrDefault(uuid.toString(), 0));
    }

    /** Chispas gastadas por el UUID en esta temporada. */
    public int pitSpentOf(java.util.UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return Math.max(0, pitSpent.getOrDefault(uuid.toString(), 0));
    }

    /** Saldo de Chispas: ganadas menos gastadas. */
    public int pitBalanceOf(java.util.UUID uuid) {
        return Math.max(0, pitEarnedOf(uuid) - pitSpentOf(uuid));
    }

    /** Vueltas consumidas por el UUID en esta temporada (máx 3). */
    public int revivalsOf(java.util.UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return Math.max(0, revivals.getOrDefault(uuid.toString(), 0));
    }

    /** Último día real (epoch day) con ganancia, -1 si nunca. */
    public long pitLastEarnDayOf(java.util.UUID uuid) {
        if (uuid == null) {
            return -1L;
        }
        return pitLastEarnDay.getOrDefault(uuid.toString(), -1L);
    }

    /** Muerte registrada (epoch millis) para la válvula de 5 días, -1 si no hay. */
    public long seasonDeathTimeOf(java.util.UUID uuid) {
        if (uuid == null) {
            return -1L;
        }
        return seasonDeathTimes.getOrDefault(uuid.toString(), -1L);
    }

    /** Registra 1 Chispa ganada (el tope lo valida PitLedger/SeasonService, aquí solo suma). */
    public void addPitEarned(java.util.UUID uuid, long earnEpochDay) {
        if (uuid == null) {
            return;
        }
        String key = uuid.toString();
        pitEarned.put(key, Math.max(0, pitEarned.getOrDefault(key, 0)) + 1);
        pitLastEarnDay.put(key, earnEpochDay);
    }

    /** Consume Chispas del saldo; sin overdraft (false sin mutar si no alcanza). */
    public boolean spendPit(java.util.UUID uuid, int cost) {
        if (uuid == null || cost <= 0 || pitBalanceOf(uuid) < cost) {
            return false;
        }
        String key = uuid.toString();
        pitSpent.put(key, Math.max(0, pitSpent.getOrDefault(key, 0)) + cost);
        return true;
    }

    /** Devuelve Chispas gastadas (refund idempotente de cancelación: solo resta lo gastado). */
    public void refundPit(java.util.UUID uuid, int cost) {
        if (uuid == null || cost <= 0) {
            return;
        }
        String key = uuid.toString();
        pitSpent.put(key, Math.max(0, pitSpent.getOrDefault(key, 0) - cost));
    }

    /** Suma una vuelta consumida (solo en ÉXITO de la transacción). */
    public void incrementRevivals(java.util.UUID uuid) {
        if (uuid == null) {
            return;
        }
        String key = uuid.toString();
        revivals.put(key, Math.max(0, revivals.getOrDefault(key, 0)) + 1);
    }

    public void noteSeasonDeath(java.util.UUID uuid, long epochMillis) {
        if (uuid != null && epochMillis > 0) {
            seasonDeathTimes.put(uuid.toString(), epochMillis);
        }
    }

    public void clearSeasonDeath(java.util.UUID uuid) {
        if (uuid != null) {
            seasonDeathTimes.remove(uuid.toString());
        }
    }

    /** ¿Hay un intento de vuelta abierto (crash entre cobro y registro)? */
    public boolean hasReviveTx(java.util.UUID uuid) {
        return uuid != null && reviveTxCost.containsKey(uuid.toString());
    }

    /** Coste retenido por el intento abierto, 0 si no hay. */
    public int reviveTxCostOf(java.util.UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return Math.max(0, reviveTxCost.getOrDefault(uuid.toString(), 0));
    }

    /** ¿El intento abierto retuvo el Núcleo de peaje del banco? */
    public boolean reviveTxTookCore(java.util.UUID uuid) {
        return uuid != null && reviveTxCore.contains(uuid.toString());
    }

    /** Muerte registrada al abrir el intento, -1 si no había. */
    public long reviveTxDeathOf(java.util.UUID uuid) {
        if (uuid == null) {
            return -1L;
        }
        return reviveTxDeath.getOrDefault(uuid.toString(), -1L);
    }

    /** Vueltas ya consumidas al abrir el intento (-1 si no hay intento o es legado). */
    public int reviveTxBaseOf(java.util.UUID uuid) {
        if (uuid == null) {
            return -1;
        }
        return reviveTxBase.getOrDefault(uuid.toString(), -1);
    }

    /**
     * Commit idempotente de la vuelta: asegura {@code base+1} sin recontar
     * (repetir el commit tras un crash no suma de más), limpia la muerte y
     * cierra el intento. {@code false} si no había intento abierto.
     */
    public boolean commitReviveTx(java.util.UUID uuid) {
        if (uuid == null || !hasReviveTx(uuid)) {
            return false;
        }
        String key = uuid.toString();
        int base = reviveTxBase.getOrDefault(key, -1);
        if (base >= 0) {
            int cur = Math.max(0, revivals.getOrDefault(key, 0));
            if (cur < base + 1) {
                revivals.put(key, base + 1);
            }
        } else {
            incrementRevivals(uuid);
        }
        seasonDeathTimes.remove(key);
        clearReviveTx(uuid);
        return true;
    }

    /** UUID con intento abierto (vista de solo lectura). */
    public Set<String> reviveTxIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(reviveTxCost.keySet()));
    }

    /**
     * Abre el intento: {@code false} sin mutar si ya hay uno abierto, uuid nulo
     * o coste inválido (idempotente ante doble ritual/restart).
     */
    public boolean beginReviveTx(java.util.UUID uuid, int cost, boolean tookCore, long deathTime) {
        if (uuid == null || cost <= 0 || hasReviveTx(uuid)) {
            return false;
        }
        String key = uuid.toString();
        reviveTxCost.put(key, cost);
        reviveTxDeath.put(key, deathTime);
        reviveTxBase.put(key, Math.max(0, revivals.getOrDefault(key, 0)));
        if (tookCore) {
            reviveTxCore.add(key);
        }
        return true;
    }

    /** Cierra el intento (commit o rollback ya aplicados); {@code true} si había uno. */
    public boolean clearReviveTx(java.util.UUID uuid) {
        if (uuid == null) {
            return false;
        }
        String key = uuid.toString();
        boolean had = reviveTxCost.containsKey(key);
        reviveTxCost.remove(key);
        reviveTxDeath.remove(key);
        reviveTxCore.remove(key);
        reviveTxBase.remove(key);
        return had;
    }

    /** Carga tolerante de tokens {@code uuid=coste:nucleo:muerte} (lo ilegible se omite). */
    public void loadReviveTx(java.util.Collection<String> raw) {
        if (raw == null) {
            return;
        }
        for (String token : raw) {
            if (token == null) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String id = token.substring(0, eq).trim();
            try {
                java.util.UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String[] parts = token.substring(eq + 1).split(":", -1);
            int cost;
            try {
                cost = Integer.parseInt(parts[0].trim());
            } catch (RuntimeException bad) {
                continue;
            }
            if (cost <= 0 || reviveTxCost.containsKey(id)) {
                continue;
            }
            boolean core = parts.length > 1 && parts[1].trim().equals("1");
            long death = -1L;
            if (parts.length > 2) {
                try {
                    death = Long.parseLong(parts[2].trim());
                } catch (RuntimeException bad) {
                    death = -1L;
                }
            }
            int base = -1;
            if (parts.length > 3) {
                try {
                    base = Integer.parseInt(parts[3].trim());
                } catch (RuntimeException bad) {
                    base = -1;
                }
            }
            reviveTxCost.put(id, cost);
            reviveTxDeath.put(id, death);
            if (base >= 0) {
                reviveTxBase.put(id, base);
            }
            if (core) {
                reviveTxCore.add(id);
            }
        }
    }

    /** Tokens deterministas {@code uuid=coste:nucleo:muerte:base} para season-state.yml. */
    public Map<String, String> reviveTxTokens() {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String id : new java.util.TreeSet<>(reviveTxCost.keySet())) {
            int cost = Math.max(0, reviveTxCost.getOrDefault(id, 0));
            String core = reviveTxCore.contains(id) ? "1" : "0";
            long death = reviveTxDeath.getOrDefault(id, -1L);
            int base = reviveTxBase.getOrDefault(id, -1);
            tokens.put(id, cost + ":" + core + ":" + death + ":" + base);
        }
        return Collections.unmodifiableMap(tokens);
    }

    public Map<String, Integer> pitEarned() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pitEarned));
    }

    public Map<String, Integer> pitSpent() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pitSpent));
    }

    public Map<String, Integer> revivals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(revivals));
    }

    public Map<String, Long> pitLastEarnDays() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pitLastEarnDay));
    }

    public Map<String, Long> seasonDeathTimes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(seasonDeathTimes));
    }

    public void loadPitEarned(Map<String, Integer> raw) {
        loadIntMap(pitEarned, raw);
    }

    public void loadPitSpent(Map<String, Integer> raw) {
        loadIntMap(pitSpent, raw);
    }

    public void loadRevivals(Map<String, Integer> raw) {
        loadIntMap(revivals, raw);
    }

    public void loadPitLastEarnDays(Map<String, Long> raw) {
        loadLongMap(pitLastEarnDay, raw);
    }

    public void loadSeasonDeathTimes(Map<String, Long> raw) {
        loadLongMap(seasonDeathTimes, raw);
    }

    private static void loadIntMap(Map<String, Integer> into, Map<String, Integer> raw) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                continue;
            }
            try {
                java.util.UUID.fromString(e.getKey().trim());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            into.putIfAbsent(e.getKey().trim(), e.getValue());
        }
    }

    private static void loadLongMap(Map<String, Long> into, Map<String, Long> raw) {
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                continue;
            }
            try {
                java.util.UUID.fromString(e.getKey().trim());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            into.putIfAbsent(e.getKey().trim(), e.getValue());
        }
    }

    public void loadAnchorUsed(java.util.Collection<String> raw) {
        if (raw == null) {
            return;
        }
        for (String s : raw) {
            try {
                anchorUsed.add(java.util.UUID.fromString(s.trim()).toString());
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public boolean isActAnnounced(int act) {
        return announcedActs.contains(act);
    }

    public boolean isObjectiveAnnounced(String id) {
        return id != null && announcedObjectives.contains(id);
    }

    public boolean isObjectiveCompleted(String id) {
        return id != null && completedObjectives.contains(id);
    }

    public boolean isRewardPaid(String id) {
        return id != null && paidRewards.contains(id);
    }

    /** Idempotente: la segunda vez devuelve {@code false}. */
    public boolean markActAnnounced(int act) {
        if (act < 1 || act > SeasonCalendar.ACT_COUNT) {
            throw new IllegalArgumentException("acto 1-7: " + act);
        }
        return announcedActs.add(act);
    }

    /** Idempotente. */
    public boolean markObjectiveAnnounced(String id) {
        return announcedObjectives.add(requireId(id));
    }

    /** Idempotente. */
    public boolean markObjectiveCompleted(String id) {
        return completedObjectives.add(requireId(id));
    }

    /** Idempotente. */
    public boolean markRewardPaid(String id) {
        return paidRewards.add(requireId(id));
    }

    /** Detalle persistente por objetivo (actor/flags/racha/noches): sobrevive a reinicios. Deriva announced/completed/paid del status (misma tabla que SeasonStore.load) para que guardar/cargar sea identidad. */
    public void setObjectiveDetail(String id, ObjectiveDetail detail) {
        requireId(id);
        if (detail == null) {
            objectiveDetails.remove(id);
            return;
        }
        ObjectiveDetail copy = detail.copy();
        objectiveDetails.put(id, copy);
        String status = copy.status == null ? "" : copy.status.trim();
        if (status.equalsIgnoreCase("ANNOUNCED") || status.equalsIgnoreCase("COMPLETED")
                || status.equalsIgnoreCase("REWARDED")) {
            announcedObjectives.add(id);
        }
        if (status.equalsIgnoreCase("COMPLETED") || status.equalsIgnoreCase("REWARDED")) {
            completedObjectives.add(id);
        }
        if (status.equalsIgnoreCase("REWARDED")) {
            paidRewards.add(id);
        }
    }

    /** Copia del detalle o null si nunca se sincronizó. */
    public ObjectiveDetail objectiveDetail(String id) {
        ObjectiveDetail detail = objectiveDetails.get(requireId(id));
        return detail == null ? null : detail.copy();
    }

    /** Vista de solo lectura de los detalles sincronizados. */
    public Map<String, ObjectiveDetail> objectiveDetails() {
        Map<String, ObjectiveDetail> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectiveDetail> entry : objectiveDetails.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id de objetivo/recompensa obligatorio");
        }
        return id;
    }

    /** Copia defensiva para el integrador. */
    public SeasonState copy() {
        SeasonState c = new SeasonState(seasonId, day, seasonLengthDays);
        c.announcedActs.addAll(announcedActs);
        c.announcedObjectives.addAll(announcedObjectives);
        c.completedObjectives.addAll(completedObjectives);
        c.paidRewards.addAll(paidRewards);
        c.sacrifices.putAll(sacrifices);
        c.bankBalance = bankBalance;
        c.bankTotalDeposited = bankTotalDeposited;
        c.bankTotalSpent = bankTotalSpent;
        c.bankExtensionUsed = bankExtensionUsed;
        c.bankRitualOpen = bankRitualOpen;
        c.bankSunReserved = bankSunReserved;
        c.bankTxIds.addAll(bankTxIds);
        c.bankEscrow.putAll(bankEscrow);
        c.solarFallDone = solarFallDone;
        c.anchorDeaths.putAll(anchorDeaths);
        c.anchorUsed.addAll(anchorUsed);
        c.pitEarned.putAll(pitEarned);
        c.pitSpent.putAll(pitSpent);
        c.revivals.putAll(revivals);
        c.pitLastEarnDay.putAll(pitLastEarnDay);
        c.seasonDeathTimes.putAll(seasonDeathTimes);
        c.reviveTxCost.putAll(reviveTxCost);
        c.reviveTxDeath.putAll(reviveTxDeath);
        c.reviveTxCore.addAll(reviveTxCore);
        c.reviveTxBase.putAll(reviveTxBase);
        c.finaleStatus = finaleStatus;
        c.finaleDay = finaleDay;
        c.finaleActor = finaleActor;
        c.finaleReason = finaleReason;
        c.firstSeenDay.putAll(firstSeenDay);
        c.lifetimeDeaths.putAll(lifetimeDeaths);
        c.finaleSnapshot = finaleSnapshot;
        c.finaleAliveN = finaleAliveN;
        c.finaleAliveM = finaleAliveM;
        c.finaleAliveNames.addAll(finaleAliveNames);
        c.finaleLifetimeDeaths.putAll(finaleLifetimeDeaths);
        c.finaleFirstSeenDays.putAll(finaleFirstSeenDays);
        c.finaleBankDeposited = finaleBankDeposited;
        c.finaleBankSpent = finaleBankSpent;
        c.finaleEvents.addAll(finaleEvents);
        for (Map.Entry<String, ObjectiveDetail> entry : objectiveDetails.entrySet()) {
            c.objectiveDetails.put(entry.getKey(), entry.getValue().copy());
        }
        return c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SeasonState other)) {
            return false;
        }
        return day == other.day
                && seasonLengthDays == other.seasonLengthDays
                && seasonId.equals(other.seasonId)
                && announcedActs.equals(other.announcedActs)
                && announcedObjectives.equals(other.announcedObjectives)
                && completedObjectives.equals(other.completedObjectives)
                && paidRewards.equals(other.paidRewards)
                && objectiveDetails.equals(other.objectiveDetails)
                && sacrifices.equals(other.sacrifices)
                && bankBalance == other.bankBalance
                && bankTotalDeposited == other.bankTotalDeposited
                && bankTotalSpent == other.bankTotalSpent
                && bankExtensionUsed == other.bankExtensionUsed
                && bankRitualOpen == other.bankRitualOpen
                && bankSunReserved == other.bankSunReserved
                && bankTxIds.equals(other.bankTxIds)
                && bankEscrow.equals(other.bankEscrow)
                && solarFallDone == other.solarFallDone
                && anchorDeaths.equals(other.anchorDeaths)
                && anchorUsed.equals(other.anchorUsed)
                && pitEarned.equals(other.pitEarned)
                && pitSpent.equals(other.pitSpent)
                && revivals.equals(other.revivals)
                && pitLastEarnDay.equals(other.pitLastEarnDay)
                && seasonDeathTimes.equals(other.seasonDeathTimes)
                && reviveTxCost.equals(other.reviveTxCost)
                && reviveTxDeath.equals(other.reviveTxDeath)
                && reviveTxCore.equals(other.reviveTxCore)
                && reviveTxBase.equals(other.reviveTxBase)
                && finaleStatus.equals(other.finaleStatus)
                && finaleDay == other.finaleDay
                && finaleActor.equals(other.finaleActor)
                && finaleReason.equals(other.finaleReason)
                && firstSeenDay.equals(other.firstSeenDay)
                && lifetimeDeaths.equals(other.lifetimeDeaths)
                && finaleSnapshot == other.finaleSnapshot
                && finaleAliveN == other.finaleAliveN
                && finaleAliveM == other.finaleAliveM
                && finaleAliveNames.equals(other.finaleAliveNames)
                && finaleLifetimeDeaths.equals(other.finaleLifetimeDeaths)
                && finaleFirstSeenDays.equals(other.finaleFirstSeenDays)
                && finaleBankDeposited == other.finaleBankDeposited
                && finaleBankSpent == other.finaleBankSpent
                && finaleEvents.equals(other.finaleEvents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seasonId, day, seasonLengthDays, announcedActs,
                announcedObjectives, completedObjectives, paidRewards, objectiveDetails,
                sacrifices, bankBalance, bankTotalDeposited, bankTotalSpent,
                bankExtensionUsed, bankRitualOpen, bankSunReserved, bankTxIds,
                bankEscrow, solarFallDone, anchorDeaths, anchorUsed,
                pitEarned, pitSpent, revivals, pitLastEarnDay, seasonDeathTimes,
                reviveTxCost, reviveTxDeath, reviveTxCore, reviveTxBase,
                finaleStatus, finaleDay, finaleActor, finaleReason,
                firstSeenDay, lifetimeDeaths, finaleSnapshot, finaleAliveN,
                finaleAliveM, finaleAliveNames, finaleLifetimeDeaths,
                finaleFirstSeenDays, finaleBankDeposited, finaleBankSpent,
                finaleEvents);
    }

    @Override
    public String toString() {
        return "SeasonState{seasonId='" + seasonId + "', day=" + day
                + ", len=" + seasonLengthDays + ", acts=" + announcedActs + "}";
    }

    /**
     * Detalle persistente de un objetivo: beneficiario (UUID), flags (noche sin
     * dormir / espora), racha de días diezmados y marcas de día. Sin Bukkit.
     */
    public static final class ObjectiveDetail {
        public String status = "PENDING";
        public long announcedAt;
        public long completedAt;
        public long rewardedAt;
        public String actor = "";
        public final List<String> flags = new ArrayList<>();
        public int streak;
        public int lastStreakDay = -1;
        public int lastNightDay = -1;

        public ObjectiveDetail copy() {
            ObjectiveDetail c = new ObjectiveDetail();
            c.status = status;
            c.announcedAt = announcedAt;
            c.completedAt = completedAt;
            c.rewardedAt = rewardedAt;
            c.actor = actor;
            c.flags.addAll(flags);
            Collections.sort(c.flags);
            c.streak = streak;
            c.lastStreakDay = lastStreakDay;
            c.lastNightDay = lastNightDay;
            return c;
        }

        /** Una línea {@code STATUS|anunciado|...} para season-state.yml (determinista). */
        public String format() {
            List<String> sortedFlags = new ArrayList<>(flags);
            Collections.sort(sortedFlags);
            return status + "|" + announcedAt + "|" + completedAt + "|" + rewardedAt + "|"
                    + (actor == null ? "" : actor) + "|" + String.join("+", sortedFlags) + "|"
                    + streak + "|" + lastStreakDay + "|" + lastNightDay;
        }

        /** Tolerante: lo ilegible cae a valores iniciales, nunca lanza. */
        public static ObjectiveDetail parse(String raw) {
            ObjectiveDetail detail = new ObjectiveDetail();
            if (raw == null) {
                return detail;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length > 0 && !parts[0].isBlank()) {
                detail.status = parts[0].trim();
            }
            detail.announcedAt = number(parts, 1);
            detail.completedAt = number(parts, 2);
            detail.rewardedAt = number(parts, 3);
            if (parts.length > 4) {
                detail.actor = parts[4].trim();
            }
            if (parts.length > 5 && !parts[5].isBlank()) {
                for (String flag : parts[5].split("\\+")) {
                    String t = flag.trim();
                    if (!t.isEmpty() && !detail.flags.contains(t)) {
                        detail.flags.add(t);
                    }
                }
            }
            if (parts.length > 6) {
                detail.streak = Math.max(0, (int) number(parts, 6));
            }
            if (parts.length > 7) {
                detail.lastStreakDay = (int) number(parts, 7, -1L);
            }
            if (parts.length > 8) {
                detail.lastNightDay = (int) number(parts, 8, -1L);
            }
            return detail;
        }

        /** Desde el mapa de {@code SeasonObjectives.snapshot()} (tolerante). */
        public static ObjectiveDetail fromMap(Map<?, ?> entry) {
            ObjectiveDetail detail = new ObjectiveDetail();
            if (entry == null) {
                return detail;
            }
            Object status = entry.get("status");
            if (status != null && !status.toString().isBlank()) {
                detail.status = status.toString().trim();
            }
            detail.announcedAt = longOf(entry.get("announcedAt"));
            detail.completedAt = longOf(entry.get("completedAt"));
            detail.rewardedAt = longOf(entry.get("rewardedAt"));
            Object actor = entry.get("actor");
            detail.actor = actor == null ? "" : actor.toString().trim();
            Object flags = entry.get("flags");
            if (flags instanceof Iterable<?> raw) {
                for (Object item : raw) {
                    if (item != null && !item.toString().isBlank()
                            && !detail.flags.contains(item.toString().trim())) {
                        detail.flags.add(item.toString().trim());
                    }
                }
            }
            detail.streak = Math.max(0, (int) longOf(entry.get("streak")));
            detail.lastStreakDay = (int) longOf(entry.get("lastStreakDay"), -1L);
            detail.lastNightDay = (int) longOf(entry.get("lastNightDay"), -1L);
            return detail;
        }

        /** Al mapa de {@code SeasonObjectives.snapshot()} (listo para rehidratar). */
        public Map<String, Object> toMap() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", status);
            entry.put("announcedAt", announcedAt);
            entry.put("completedAt", completedAt);
            entry.put("rewardedAt", rewardedAt);
            entry.put("actor", actor == null ? "" : actor);
            entry.put("flags", new ArrayList<>(flags));
            entry.put("streak", streak);
            entry.put("lastStreakDay", lastStreakDay);
            entry.put("lastNightDay", lastNightDay);
            return entry;
        }

        private static long number(String[] parts, int index) {
            return number(parts, index, 0L);
        }

        private static long number(String[] parts, int index, long fallback) {
            if (parts.length <= index) {
                return fallback;
            }
            try {
                return Long.parseLong(parts[index].trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static long longOf(Object value) {
            return longOf(value, 0L);
        }

        private static long longOf(Object value, long fallback) {
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ObjectiveDetail other)) {
                return false;
            }
            return announcedAt == other.announcedAt
                    && completedAt == other.completedAt
                    && rewardedAt == other.rewardedAt
                    && streak == other.streak
                    && lastStreakDay == other.lastStreakDay
                    && lastNightDay == other.lastNightDay
                    && Objects.equals(status, other.status)
                    && Objects.equals(actor, other.actor)
                    && flags.equals(other.flags);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, announcedAt, completedAt, rewardedAt, actor, flags,
                    streak, lastStreakDay, lastNightDay);
        }
    }
}
