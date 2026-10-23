package mx.vicko.permadeath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Persistencia de {@link SeasonState} en {@code season-state.yml} propio
 * (nunca {@code config.yml} como BD). Sin Bukkit: {@link Path}/{@link Files}.
 *
 * <p>Contrato: archivo faltante o vacio = temporada fresca (no bloquea al
 * admin); {@code seasonId} distinto al pedido = temporada fresca del id pedido
 * tras archivar el anterior con {@link #archiveIfSeasonChanged} (no mezcla
 * datos, no borra historial); contenido ilegible = {@link CorruptStateException}
 * diagnosticada sin tocar el archivo (el servicio sella la escritura con
 * {@link WriteGate} hasta un reload correcto). Guardados seguros via temporal
 * + movida atomica; serializacion determinista (listas ordenadas) para
 * idempotencia byte a byte.
 */
public final class SeasonStore {

    public static final String STATE_FILE_NAME = "season-state.yml";

    private final Path stateFile;

    /**
     * @param dataFolderOrFile carpeta de datos (se resuelve
     *     {@code season-state.yml} dentro) o ruta directa al archivo
     *     (si termina en {@code .yml}).
     */
    public SeasonStore(Path dataFolderOrFile) {
        if (dataFolderOrFile == null) {
            throw new IllegalArgumentException("ruta de datos obligatoria");
        }
        if (dataFolderOrFile.getFileName() != null
                && dataFolderOrFile.getFileName().toString().endsWith(".yml")) {
            this.stateFile = dataFolderOrFile;
        } else {
            this.stateFile = dataFolderOrFile.resolve(STATE_FILE_NAME);
        }
    }

    public static SeasonStore forFile(Path stateFile) {
        if (stateFile == null) {
            throw new IllegalArgumentException("stateFile obligatorio");
        }
        return new SeasonStore(stateFile.getParent() == null
                ? stateFile
                : stateFile);
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * Carga el estado. Nunca devuelve null. No crea ni repara archivos:
     * solo lee; el admin no queda bloqueado por falta de config.
     */
    public SeasonState load(String seasonId) throws IOException {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId obligatorio para cargar");
        }
        if (!Files.exists(stateFile) || Files.isDirectory(stateFile)) {
            return new SeasonState(seasonId);
        }
        String text = Files.readString(stateFile, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new SeasonState(seasonId);
        }
        Parsed parsed = parse(text, stateFile);
        if (!parsed.seasonId.equals(seasonId)) {
            return new SeasonState(seasonId);
        }
        SeasonState state = new SeasonState(parsed.seasonId, parsed.day, parsed.seasonLengthDays);
        for (int act : parsed.announcedActs) {
            state.markActAnnounced(act);
        }
        for (String id : parsed.announcedObjectives) {
            state.markObjectiveAnnounced(id);
        }
        for (String id : parsed.completedObjectives) {
            state.markObjectiveCompleted(id);
        }
        for (String id : parsed.paidRewards) {
            state.markRewardPaid(id);
        }
        for (Map.Entry<String, String> detail : parsed.objectiveDetails.entrySet()) {
            SeasonState.ObjectiveDetail parsedDetail =
                    SeasonState.ObjectiveDetail.parse(detail.getValue());
            // setObjectiveDetail deriva announced/completed/paid del status (invariante unica).
            state.setObjectiveDetail(detail.getKey(), parsedDetail);
        }
        state.setBankBalance(parsed.bankBalance);
        state.setBankTotalDeposited(parsed.bankTotalDeposited);
        state.setBankTotalSpent(parsed.bankTotalSpent);
        state.setBankExtensionUsed(parsed.bankExtensionUsed);
        state.setBankRitualOpen(parsed.bankRitualOpen);
        state.setBankSunReserved(parsed.bankSunReserved);
        state.loadBankTxIds(parsed.bankTx);
        java.util.Map<String, Integer> escrow = new LinkedHashMap<>();
        for (String token : parsed.bankEscrow) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                escrow.put(token.substring(0, eq).trim(),
                        Math.max(0, Integer.parseInt(token.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        state.loadBankEscrow(escrow);
        java.util.Map<String, String> sac = new LinkedHashMap<>();
        for (String token : parsed.sacrifices) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            sac.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
        }
        state.loadSacrifices(sac);
        state.setSolarFallDone(parsed.solarFallDone);
        java.util.Map<String, String> deaths = new LinkedHashMap<>();
        for (String token : parsed.anchorDeaths) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            deaths.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
        }
        state.loadAnchorDeaths(deaths);
        state.loadAnchorUsed(parsed.anchorUsed);
        state.loadPitEarned(intEntries(parsed.pitEarned));
        state.loadPitSpent(intEntries(parsed.pitSpent));
        state.loadRevivals(intEntries(parsed.revivals));
        state.loadPitLastEarnDays(longEntries(parsed.pitLastEarn));
        state.loadSeasonDeathTimes(longEntries(parsed.deathTimes));
        state.loadReviveTx(parsed.reviveTx);
        state.loadFinale(parsed.finaleStatus, parsed.finaleDay, parsed.finaleActor, parsed.finaleReason);
        state.loadFirstSeen(intEntries(parsed.firstSeen));
        state.loadLifetimeDeaths(intEntries(parsed.lifetimeDeaths));
        state.loadFinaleSnapshot(parsed.finaleSnapshot, parsed.finaleAliveN, parsed.finaleAliveM,
                parsed.finaleAliveNames, intEntries(parsed.finaleLifetimeDeaths),
                intEntries(parsed.finaleFirstSeenDays), parsed.finaleBankDeposited,
                parsed.finaleBankSpent, parsed.finaleEvents);
        return state;
    }

    /**
     * Preserva el historial ante cambio de {@code season.id}: si el archivo
     * existe, no esta vacio y pertenece a otra temporada, lo archiva junto al
     * original ({@code season-state-&lt;id&gt;.bak.yml}, con sufijo si colisiona)
     * y devuelve su ruta. Sin archivo, vacio o mismo id = vacio (nada que hacer).
     * Archivo corrupto = {@link CorruptStateException} sin mover nada (el
     * llamador debe sellar la escritura, nunca inicializar encima).
     */
    public Optional<Path> archiveIfSeasonChanged(String seasonId) throws IOException {
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId obligatorio para archivar");
        }
        if (!Files.exists(stateFile) || Files.isDirectory(stateFile)) {
            return Optional.empty();
        }
        String text = Files.readString(stateFile, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return Optional.empty();
        }
        Parsed parsed = parse(text, stateFile);
        if (parsed.seasonId.equals(seasonId)) {
            return Optional.empty();
        }
        String safe = parsed.seasonId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "previa";
        }
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        Path backup = stateFile.resolveSibling("season-state-" + safe + ".bak.yml");
        int suffix = 2;
        while (Files.exists(backup)) {
            backup = stateFile.resolveSibling("season-state-" + safe + ".bak" + suffix + ".yml");
            suffix++;
        }
        Files.move(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(backup);
    }

    /** Guardado seguro: escribe temporal en el mismo directorio y mueve atomico. */
    public void save(SeasonState state) throws IOException {
        if (state == null) {
            throw new IllegalArgumentException("state obligatorio");
        }
        Path parent = stateFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String text = serialize(state);
        Path tmp = stateFile.resolveSibling(
                stateFile.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(tmp, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Excepcion diagnosticada: incluye ruta y motivo; el archivo se deja intacto. */
    public static final class CorruptStateException extends IOException {
        public CorruptStateException(String message) {
            super(message);
        }
    }

    /**
     * Politica pura de bloqueo de escritura (sin Bukkit): el servicio la sella
     * al encontrar el archivo corrupto/ilegible y solo la libera tras una carga
     * correcta (reload explicito). Sellada = ningun {@code save} debe intentarse.
     */
    public static final class WriteGate {
        private boolean sealed;
        private String reason = "";

        public void seal(String reason) {
            this.sealed = true;
            this.reason = reason == null ? "" : reason;
        }

        public void unseal() {
            this.sealed = false;
            this.reason = "";
        }

        public boolean isSealed() {
            return sealed;
        }

        public String reason() {
            return reason;
        }
    }

    // ---- serializacion determinista (listas inline ordenadas) ----

    static String serialize(SeasonState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("seasonId: ").append(escape(state.seasonId())).append('\n');
        sb.append("day: ").append(state.day()).append('\n');
        sb.append("seasonLengthDays: ").append(state.seasonLengthDays()).append('\n');
        sb.append("announcedActs: ").append(intList(new TreeSet<>(state.announcedActs()))).append('\n');
        sb.append("announcedObjectives: ").append(strList(new TreeSet<>(state.announcedObjectives()))).append('\n');
        sb.append("completedObjectives: ").append(strList(new TreeSet<>(state.completedObjectives()))).append('\n');
        sb.append("paidRewards: ").append(strList(new TreeSet<>(state.paidRewards()))).append('\n');
        sb.append("bankBalance: ").append(state.bankBalance()).append('\n');
        sb.append("bankTotalDeposited: ").append(state.bankTotalDeposited()).append('\n');
        sb.append("bankTotalSpent: ").append(state.bankTotalSpent()).append('\n');
        sb.append("bankExtensionUsed: ").append(state.isBankExtensionUsed()).append('\n');
        sb.append("bankRitualOpen: ").append(state.isBankRitualOpen()).append('\n');
        sb.append("bankSunReserved: ").append(state.isBankSunReserved()).append('\n');
        sb.append("bankTx: ").append(strList(new TreeSet<>(state.bankTxIds()))).append('\n');
        java.util.Map<String, Integer> escrow = new java.util.TreeMap<>(state.bankEscrow());
        java.util.TreeSet<String> escrowTokens = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, Integer> e : escrow.entrySet()) {
            escrowTokens.add(e.getKey() + "=" + e.getValue());
        }
        sb.append("bankEscrow: ").append(strList(escrowTokens)).append('\n');
        java.util.TreeSet<String> sacTokens = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(state.sacrifices()).entrySet()) {
            sacTokens.add(e.getKey() + "=" + e.getValue());
        }
        sb.append("sacrifices: ").append(strList(sacTokens)).append('\n');
        sb.append("solarFallDone: ").append(state.isSolarFallDone()).append('\n');
        java.util.TreeSet<String> anchorTokens = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(state.anchorDeaths()).entrySet()) {
            anchorTokens.add(e.getKey() + "=" + e.getValue());
        }
        sb.append("anchorDeaths: ").append(strList(anchorTokens)).append('\n');
        sb.append("anchorUsed: ").append(strList(new TreeSet<>(state.anchorUsed()))).append('\n');
        sb.append("pitEarned: ").append(strList(intTokens(state.pitEarned()))).append('\n');
        sb.append("pitSpent: ").append(strList(intTokens(state.pitSpent()))).append('\n');
        sb.append("revivals: ").append(strList(intTokens(state.revivals()))).append('\n');
        sb.append("pitLastEarn: ").append(strList(longTokens(state.pitLastEarnDays()))).append('\n');
        sb.append("deathTimes: ").append(strList(longTokens(state.seasonDeathTimes()))).append('\n');
        java.util.TreeSet<String> reviveTokens = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, String> e
                : new java.util.TreeMap<>(state.reviveTxTokens()).entrySet()) {
            reviveTokens.add(e.getKey() + "=" + e.getValue());
        }
        sb.append("reviveTx: ").append(strList(reviveTokens)).append('\n');
        sb.append("finaleStatus: ").append(escape(state.finaleStatus())).append('\n');
        sb.append("finaleDay: ").append(state.finaleDay()).append('\n');
        sb.append("finaleActor: ").append(escape(state.finaleActor())).append('\n');
        sb.append("finaleReason: ").append(escape(state.finaleReason())).append('\n');
        sb.append("firstSeen: ").append(strList(intTokens(state.firstSeenDays()))).append('\n');
        sb.append("lifetimeDeaths: ").append(strList(intTokens(state.lifetimeDeaths()))).append('\n');
        sb.append("finaleSnapshot: ").append(state.hasFinaleSnapshot()).append('\n');
        sb.append("finaleAliveN: ").append(state.finaleAliveN()).append('\n');
        sb.append("finaleAliveM: ").append(state.finaleAliveM()).append('\n');
        sb.append("finaleAliveNames: ").append(strList(new java.util.TreeSet<>(state.finaleAliveNames()))).append('\n');
        sb.append("finaleLifetimeDeaths: ").append(strList(intTokens(state.finaleLifetimeDeaths()))).append('\n');
        sb.append("finaleFirstSeenDays: ").append(strList(intTokens(state.finaleFirstSeenDays()))).append('\n');
        sb.append("finaleBankDeposited: ").append(state.finaleBankDeposited()).append('\n');
        sb.append("finaleBankSpent: ").append(state.finaleBankSpent()).append('\n');
        sb.append("finaleEvents: ").append(strList(new java.util.TreeSet<>(state.finaleEvents()))).append('\n');
        for (Map.Entry<String, SeasonState.ObjectiveDetail> detail
                : new java.util.TreeMap<>(state.objectiveDetails()).entrySet()) {
            sb.append("objective.").append(detail.getKey()).append(": ")
                    .append(detail.getValue() == null ? "" : detail.getValue().format()).append('\n');
        }
        return sb.toString();
    }

    private static String intList(TreeSet<Integer> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int v : values) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(v);
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String strList(TreeSet<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(escape(v));
            first = false;
        }
        return sb.append(']').toString();
    }

    static java.util.TreeSet<String> intTokens(java.util.Map<String, Integer> entries) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, Integer> e : new java.util.TreeMap<>(entries).entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    static java.util.TreeSet<String> longTokens(java.util.Map<String, Long> entries) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (java.util.Map.Entry<String, Long> e : new java.util.TreeMap<>(entries).entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    static java.util.Map<String, Integer> intEntries(java.util.List<String> tokens) {
        java.util.Map<String, Integer> out = new LinkedHashMap<>();
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(token.substring(0, eq).trim(),
                        Math.max(0, Integer.parseInt(token.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static java.util.Map<String, Long> longEntries(java.util.List<String> tokens) {
        java.util.Map<String, Long> out = new LinkedHashMap<>();
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.put(token.substring(0, eq).trim(),
                        Math.max(0L, Long.parseLong(token.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static String escape(String scalar) {
        if (scalar.matches("[A-Za-z0-9_./-]+")) {
            return scalar;
        }
        return "'" + scalar.replace("'", "''") + "'";
    }

    static String unescape(String scalar) {
        String t = scalar.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1).replace("''", "'");
        }
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    // ---- parseo minimo YAML (inline [...] + bloques "- item") ----

    private static final class Parsed {
        String seasonId;
        int day = 1;
        int seasonLengthDays = SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS;
        final List<Integer> announcedActs = new ArrayList<>();
        final List<String> announcedObjectives = new ArrayList<>();
        final List<String> completedObjectives = new ArrayList<>();
        final List<String> paidRewards = new ArrayList<>();
        final Map<String, String> objectiveDetails = new LinkedHashMap<>();
        int bankBalance;
        int bankTotalDeposited;
        int bankTotalSpent;
        boolean bankExtensionUsed;
        boolean bankRitualOpen;
        boolean bankSunReserved;
        final List<String> bankTx = new ArrayList<>();
        final List<String> bankEscrow = new ArrayList<>();
        final List<String> sacrifices = new ArrayList<>();
        boolean solarFallDone;
        final List<String> anchorDeaths = new ArrayList<>();
        final List<String> anchorUsed = new ArrayList<>();
        final List<String> pitEarned = new ArrayList<>();
        final List<String> pitSpent = new ArrayList<>();
        final List<String> revivals = new ArrayList<>();
        final List<String> pitLastEarn = new ArrayList<>();
        final List<String> deathTimes = new ArrayList<>();
        final List<String> reviveTx = new ArrayList<>();
        String finaleStatus = "RUNNING";
        int finaleDay = 0;
        String finaleActor = "";
        String finaleReason = "";
        final List<String> firstSeen = new ArrayList<>();
        final List<String> lifetimeDeaths = new ArrayList<>();
        boolean finaleSnapshot = false;
        int finaleAliveN = 0;
        int finaleAliveM = 0;
        final List<String> finaleAliveNames = new ArrayList<>();
        final List<String> finaleLifetimeDeaths = new ArrayList<>();
        final List<String> finaleFirstSeenDays = new ArrayList<>();
        int finaleBankDeposited = 0;
        int finaleBankSpent = 0;
        final List<String> finaleEvents = new ArrayList<>();
    }

    static Parsed parse(String text, Path file) throws CorruptStateException {
        Map<String, String> scalars = new LinkedHashMap<>();
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        String currentKey = null;
        boolean anyMapping = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (currentKey == null) {
                    throw new CorruptStateException(
                            "season-state corrupto en " + file + ": item '- ...' sin clave");
                }
                blocks.computeIfAbsent(currentKey, k -> new ArrayList<>())
                        .add(unescape(line.substring(2)));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new CorruptStateException(
                        "season-state corrupto en " + file + ": linea sin 'clave: valor' -> '" + line + "'");
            }
            currentKey = line.substring(0, colon).trim();
            scalars.put(currentKey, line.substring(colon + 1).trim());
            anyMapping = true;
        }
        if (!anyMapping) {
            throw new CorruptStateException(
                    "season-state corrupto en " + file + ": sin pares 'clave: valor'");
        }
        Parsed p = new Parsed();
        String sid = scalars.get("seasonId");
        if (sid == null || unescape(sid).isBlank()) {
            throw new CorruptStateException(
                    "season-state corrupto en " + file + ": falta 'seasonId'");
        }
        p.seasonId = unescape(sid);
        p.day = parseInt(scalars.get("day"), 1, file, "day");
        p.seasonLengthDays = parseInt(scalars.get("seasonLengthDays"),
                SeasonCalendar.DEFAULT_SEASON_LENGTH_DAYS, file, "seasonLengthDays");
        p.announcedActs.addAll(parseIntList(scalars.get("announcedActs"),
                blocks.get("announcedActs"), file));
        p.announcedObjectives.addAll(parseStrList(scalars.get("announcedObjectives"),
                blocks.get("announcedObjectives")));
        p.completedObjectives.addAll(parseStrList(scalars.get("completedObjectives"),
                blocks.get("completedObjectives")));
        p.paidRewards.addAll(parseStrList(scalars.get("paidRewards"),
                blocks.get("paidRewards")));
        p.bankBalance = Math.max(0, parseInt(scalars.get("bankBalance"), 0, file, "bankBalance"));
        p.bankTotalDeposited = Math.max(0, parseInt(scalars.get("bankTotalDeposited"), 0, file, "bankTotalDeposited"));
        p.bankTotalSpent = Math.max(0, parseInt(scalars.get("bankTotalSpent"), 0, file, "bankTotalSpent"));
        p.bankExtensionUsed = parseBool(scalars.get("bankExtensionUsed"));
        p.bankRitualOpen = parseBool(scalars.get("bankRitualOpen"));
        p.bankSunReserved = parseBool(scalars.get("bankSunReserved"));
        p.bankTx.addAll(parseStrList(scalars.get("bankTx"), blocks.get("bankTx")));
        p.bankEscrow.addAll(parseStrList(scalars.get("bankEscrow"), blocks.get("bankEscrow")));
        p.sacrifices.addAll(parseStrList(scalars.get("sacrifices"), blocks.get("sacrifices")));
        p.solarFallDone = parseBool(scalars.get("solarFallDone"));
        p.anchorDeaths.addAll(parseStrList(scalars.get("anchorDeaths"), blocks.get("anchorDeaths")));
        p.anchorUsed.addAll(parseStrList(scalars.get("anchorUsed"), blocks.get("anchorUsed")));
        p.pitEarned.addAll(parseStrList(scalars.get("pitEarned"), blocks.get("pitEarned")));
        p.pitSpent.addAll(parseStrList(scalars.get("pitSpent"), blocks.get("pitSpent")));
        p.revivals.addAll(parseStrList(scalars.get("revivals"), blocks.get("revivals")));
        p.pitLastEarn.addAll(parseStrList(scalars.get("pitLastEarn"), blocks.get("pitLastEarn")));
        p.deathTimes.addAll(parseStrList(scalars.get("deathTimes"), blocks.get("deathTimes")));
        p.reviveTx.addAll(parseStrList(scalars.get("reviveTx"), blocks.get("reviveTx")));
        String rawFinale = scalars.get("finaleStatus");
        p.finaleStatus = rawFinale == null ? "RUNNING" : unescape(rawFinale).trim();
        p.finaleDay = parseInt(scalars.get("finaleDay"), 0, file, "finaleDay");
        String rawActor = scalars.get("finaleActor");
        p.finaleActor = rawActor == null ? "" : unescape(rawActor);
        String rawReason = scalars.get("finaleReason");
        p.finaleReason = rawReason == null ? "" : unescape(rawReason);
        p.firstSeen.addAll(parseStrList(scalars.get("firstSeen"), blocks.get("firstSeen")));
        p.lifetimeDeaths.addAll(parseStrList(scalars.get("lifetimeDeaths"), blocks.get("lifetimeDeaths")));
        p.finaleSnapshot = parseBool(scalars.get("finaleSnapshot"));
        p.finaleAliveN = Math.max(0, parseInt(scalars.get("finaleAliveN"), 0, file, "finaleAliveN"));
        p.finaleAliveM = Math.max(0, parseInt(scalars.get("finaleAliveM"), 0, file, "finaleAliveM"));
        p.finaleAliveNames.addAll(parseStrList(scalars.get("finaleAliveNames"), blocks.get("finaleAliveNames")));
        p.finaleLifetimeDeaths.addAll(parseStrList(scalars.get("finaleLifetimeDeaths"), blocks.get("finaleLifetimeDeaths")));
        p.finaleFirstSeenDays.addAll(parseStrList(scalars.get("finaleFirstSeenDays"), blocks.get("finaleFirstSeenDays")));
        p.finaleBankDeposited = Math.max(0, parseInt(scalars.get("finaleBankDeposited"), 0, file, "finaleBankDeposited"));
        p.finaleBankSpent = Math.max(0, parseInt(scalars.get("finaleBankSpent"), 0, file, "finaleBankSpent"));
        p.finaleEvents.addAll(parseStrList(scalars.get("finaleEvents"), blocks.get("finaleEvents")));
        for (Map.Entry<String, String> scalar : scalars.entrySet()) {
            if (scalar.getKey() != null && scalar.getKey().startsWith("objective.")) {
                String id = scalar.getKey().substring("objective.".length()).trim();
                if (!id.isEmpty()) {
                    p.objectiveDetails.put(id, scalar.getValue() == null ? "" : scalar.getValue().trim());
                }
            }
        }
        return p;
    }

    private static boolean parseBool(String value) {
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    private static int parseInt(String value, int def, Path file, String key)
            throws CorruptStateException {
        if (value == null || value.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new CorruptStateException(
                    "season-state corrupto en " + file + ": '" + key + "' no es entero -> '" + value + "'");
        }
    }

    private static List<Integer> parseIntList(String inline, List<String> block, Path file)
            throws CorruptStateException {
        List<String> raw = inlineItems(inline, block);
        List<Integer> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                throw new CorruptStateException(
                        "season-state corrupto en " + file + ": lista entera con '" + s + "'");
            }
        }
        return out;
    }

    private static List<String> parseStrList(String inline, List<String> block) {
        List<String> out = new ArrayList<>();
        for (String s : inlineItems(inline, block)) {
            String t = unescape(s).trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> inlineItems(String inline, List<String> block) {
        if (inline != null && inline.startsWith("[") && inline.endsWith("]")) {
            String inner = inline.substring(1, inline.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (inSingle) {
                    if (c == '\'') {
                        if (i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                            current.append("''");
                            i++;
                        } else {
                            inSingle = false;
                            current.append(c);
                        }
                    } else {
                        current.append(c);
                    }
                } else if (inDouble) {
                    if (c == '"') {
                        inDouble = false;
                    }
                    current.append(c);
                } else if (c == '\'') {
                    inSingle = true;
                    current.append(c);
                } else if (c == '"') {
                    inDouble = true;
                    current.append(c);
                } else if (c == ',') {
                    String t = current.toString().trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            String last = current.toString().trim();
            if (!last.isEmpty()) {
                out.add(last);
            }
            return out;
        }
        if ((inline == null || inline.isEmpty()) && block != null) {
            return block;
        }
        if (inline == null || inline.isEmpty()) {
            return List.of();
        }
        return List.of(inline);
    }
}
