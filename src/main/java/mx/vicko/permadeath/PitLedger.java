package mx.vicko.permadeath;

/**
 * Bloque 3 — La Fosa: libro de Chispas (puro, sin Bukkit ni IO).
 *
 * <p>Contrato vigente: 1 Chispa por run, tope 1 por día real, tope 6 Chispas
 * GANADAS por UUID por temporada. El tope es sobre ganadas, no sobre saldo:
 * gastar no reabre cupo. Máximo 3 vueltas porque 1+2+3=6; la 4ª vuelta queda
 * bloqueada aunque haya activos adicionales. Tiempo inyectado (epoch millis)
 * para que reinicios y moneda no dupliquen.
 */
public final class PitLedger {

    /** Tope de Chispas ganadas por UUID por temporada. */
    public static final int MAX_EARNED_PER_SEASON = 6;
    /** Ganancia por run completado. */
    public static final int EARN_PER_RUN = 1;
    /** Tope de ganancia por día real. */
    public static final int MAX_EARN_PER_DAY = 1;
    /** Máximo de vueltas por jugador por temporada. */
    public static final int MAX_REVIVALS = 3;
    /** Duración de run configurable: 10–15 min, default 12. */
    public static final int MIN_RUN_MINUTES = 10;
    public static final int MAX_RUN_MINUTES = 15;
    public static final int DEFAULT_RUN_MINUTES = 12;
    /** Válvula: 5 días reales muerto con Chispas suficientes. */
    public static final long VALVE_DAYS = 5L;
    public static final long MILLIS_PER_DAY = 86_400_000L;

    private PitLedger() {
    }

    /** Duración de run saneada: fuera de 10–15 cae al default 12 (nunca destruye datos). */
    public static int clampRunMinutes(int configured) {
        if (configured < MIN_RUN_MINUTES || configured > MAX_RUN_MINUTES) {
            return DEFAULT_RUN_MINUTES;
        }
        return configured;
    }

    /** Día real (epoch day) para el tope diario. */
    public static long epochDay(long epochMillis) {
        return Math.floorDiv(epochMillis, MILLIS_PER_DAY);
    }

    /** Coste de la siguiente vuelta: revivals+1 (N<=3). */
    public static int nextCost(int revivals) {
        return revivals + 1;
    }

    /** {@code true} si ya agotó las 3 vueltas. */
    public static boolean isRevivalMaxed(int revivals) {
        return revivals >= MAX_REVIVALS;
    }

    /**
     * ¿Puede ganar una Chispa ahora? Exige cupo de temporada (ganadas &lt; 6)
     * y no haber ganado ya hoy (día real distinto al último).
     */
    public static boolean canEarn(int earnedTotal, long lastEarnEpochDay, long todayEpochDay) {
        if (earnedTotal >= MAX_EARNED_PER_SEASON) {
            return false;
        }
        return lastEarnEpochDay != todayEpochDay;
    }

    /** Saldo disponible: ganadas menos gastadas (nunca negativo en la práctica). */
    public static int balance(int earnedTotal, int spentTotal) {
        return Math.max(0, earnedTotal - Math.max(0, spentTotal));
    }

    /** Días reales transcurridos desde la muerte (para la válvula de 5 días). */
    public static long daysSinceDeath(long deathEpochMillis, long nowEpochMillis) {
        if (deathEpochMillis <= 0 || nowEpochMillis < deathEpochMillis) {
            return 0;
        }
        return (nowEpochMillis - deathEpochMillis) / MILLIS_PER_DAY;
    }

    /** ¿Aplica la válvula de 5 días? Requiere Chispas suficientes para el siguiente coste. */
    public static boolean valveApplies(long daysSinceDeath, int chispaBalance, int nextCost) {
        return daysSinceDeath >= VALVE_DAYS && chispaBalance >= nextCost;
    }
}
