package mx.vicko.permadeath;

/**
 * Calendario puro de temporada (sin Bukkit).
 *
 * <p>Fuente: {@code docs/plan-temporada.md} bloque 1.1-1.2 y actos con limites
 * en fases 10/35/40/60/80/100.
 *
 * <p>Formula: {@code phaseDay(fase) = max(1, round(fase * length / 100.0))},
 * truncada al ultimo dia. Longitudes validas 30-45 (default 40); longitud 100
 * solo por compatibilidad y reproduce los umbrales viejos ({@code phaseDay = fase}).
 */
public final class SeasonCalendar {

    public static final int MIN_SEASON_LENGTH_DAYS = 30;
    public static final int MAX_SEASON_LENGTH_DAYS = 45;
    public static final int DEFAULT_SEASON_LENGTH_DAYS = 40;
    public static final int COMPAT_SEASON_LENGTH_DAYS = 100;

    /** Fases soportadas (11), en orden. */
    public static final int[] SUPPORTED_PHASES = {10, 20, 25, 35, 40, 50, 60, 70, 80, 90, 100};

    /** Fases que marcan el inicio de los actos II, III, IV, V, VI y VII. */
    public static final int ACT2_START_PHASE = 10;
    public static final int ACT3_START_PHASE = 35;
    public static final int ACT4_START_PHASE = 40;
    public static final int ACT5_START_PHASE = 60;
    public static final int ACT6_START_PHASE = 80;
    public static final int ACT7_START_PHASE = 100;

    public static final int ACT_COUNT = 7;

    /** Vivos bajo los cuales los efectos de fase se suspenden (narrativa sigue). */
    public static final int SUSPEND_BELOW_ALIVE = 4;

    private SeasonCalendar() {
    }

    /**
     * Normaliza la longitud: 100 se conserva (compatibilidad); cualquier otro
     * valor fuera de 30-45 se recorta a ese rango. Nunca lanza por falta de config.
     */
    public static int normalizeSeasonLength(int seasonLengthDays) {
        if (seasonLengthDays == COMPAT_SEASON_LENGTH_DAYS) {
            return COMPAT_SEASON_LENGTH_DAYS;
        }
        if (seasonLengthDays < MIN_SEASON_LENGTH_DAYS) {
            return MIN_SEASON_LENGTH_DAYS;
        }
        if (seasonLengthDays > MAX_SEASON_LENGTH_DAYS) {
            return MAX_SEASON_LENGTH_DAYS;
        }
        return seasonLengthDays;
    }

    /** Dia (1-based) en que se desbloquea {@code phase} para la longitud dada. */
    public static int phaseDay(int phase, int seasonLengthDays) {
        int len = normalizeSeasonLength(seasonLengthDays);
        int day = (int) Math.max(1L, Math.round(phase * (len / 100.0)));
        return Math.min(day, len);
    }

    /** True si en {@code day} la fase ya esta desbloqueada. */
    public static boolean isPhaseUnlocked(int phase, int day, int seasonLengthDays) {
        int len = normalizeSeasonLength(seasonLengthDays);
        return Math.max(1, day) >= phaseDay(phase, len);
    }

    /**
     * Acto (1-7) para un dia dado. Limites derivados de
     * phaseDay(10/35/40/60/80/100); con 40 reproduce I 1-3, II 4-13, III 14-15,
     * IV 16-23, V 24-31, VI 32-39, VII 40.
     */
    public static int actForDay(int day, int seasonLengthDays) {
        int len = normalizeSeasonLength(seasonLengthDays);
        int d = Math.min(Math.max(1, day), len);
        if (d < phaseDay(ACT2_START_PHASE, len)) {
            return 1;
        }
        if (d < phaseDay(ACT3_START_PHASE, len)) {
            return 2;
        }
        if (d < phaseDay(ACT4_START_PHASE, len)) {
            return 3;
        }
        if (d < phaseDay(ACT5_START_PHASE, len)) {
            return 4;
        }
        if (d < phaseDay(ACT6_START_PHASE, len)) {
            return 5;
        }
        if (d < phaseDay(ACT7_START_PHASE, len)) {
            return 6;
        }
        return 7;
    }

    /** Primer dia del acto (1-7). Acto fuera de rango se recorta a 1-7. */
    public static int actStartDay(int act, int seasonLengthDays) {
        int len = normalizeSeasonLength(seasonLengthDays);
        int a = Math.min(Math.max(1, act), ACT_COUNT);
        switch (a) {
            case 1:
                return 1;
            case 2:
                return phaseDay(ACT2_START_PHASE, len);
            case 3:
                return phaseDay(ACT3_START_PHASE, len);
            case 4:
                return phaseDay(ACT4_START_PHASE, len);
            case 5:
                return phaseDay(ACT5_START_PHASE, len);
            case 6:
                return phaseDay(ACT6_START_PHASE, len);
            default:
                return phaseDay(ACT7_START_PHASE, len);
        }
    }

    /** Ultimo dia del acto (1-7); nunca anterior a su inicio. */
    public static int actEndDay(int act, int seasonLengthDays) {
        int len = normalizeSeasonLength(seasonLengthDays);
        int a = Math.min(Math.max(1, act), ACT_COUNT);
        if (a >= ACT_COUNT) {
            return len;
        }
        int end = actStartDay(a + 1, len) - 1;
        int start = actStartDay(a, len);
        if (end < start) {
            return start;
        }
        return Math.min(end, len);
    }

    /**
     * Efectos de fase suspendidos con menos de 4 vivos. Solo suspende efectos;
     * bossbar/calendario/desbloqueo narrativo siguen.
     */
    public static boolean arePhaseEffectsSuspended(int aliveCount) {
        return aliveCount < SUSPEND_BELOW_ALIVE;
    }
}
