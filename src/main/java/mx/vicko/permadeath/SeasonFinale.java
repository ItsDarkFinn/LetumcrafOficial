package mx.vicko.permadeath;

/**
 * Bloque 4 — Final de temporada (puro, sin Bukkit ni IO).
 *
 * <p>Fecha real del fin = longitud efectiva (base 30-45 + única extensión de
 * 3 días si se usó, tope 45; compat 100 conserva 100). La fase 100 existente
 * representa el apagado; al día final el grupo con 30 Núcleos en el Banco
 * enciende el Sol (victoria) y sin ellos es derrota. Una sola transición
 * RUNNING → VICTORY/DEFEAT; {@code /pd dia} hacia atrás o reinicios no
 * reabren ni duplican (el estado persiste en {@code season-state.yml}).
 *
 * <p>El pago del Sol usa el escrow idempotente del Banco con un tx
 * determinista {@code finale-sun:&lt;seasonId&gt;:&lt;finalDay&gt;}: repetirlo
 * tras un reinicio no cobra dos veces.
 */
public final class SeasonFinale {

    /** Estado persistente del final (una sola vez). */
    public enum Status {
        RUNNING,
        VICTORY,
        DEFEAT
    }

    private SeasonFinale() {
    }

    /**
     * Día final real: base + 3 si la extensión única se usó (tope 45).
     * Compat 100 conserva 100 (sin extensión).
     */
    public static int finalDay(int baseLengthDays, boolean extensionUsed) {
        if (baseLengthDays == SeasonCalendar.COMPAT_SEASON_LENGTH_DAYS) {
            return SeasonCalendar.COMPAT_SEASON_LENGTH_DAYS;
        }
        int base = SeasonCalendar.normalizeSeasonLength(baseLengthDays);
        if (extensionUsed) {
            return SeasonCalendar.normalizeSeasonLength(base + EclipseBank.EXTENSION_DAYS);
        }
        return base;
    }

    /** Tx determinista del Sol del final (idempotente entre reinicios). */
    public static String finaleTxId(String seasonId, int finalDay) {
        String sid = seasonId == null || seasonId.isBlank() ? "temporada-1" : seasonId.trim();
        return "finale-sun:" + sid + ":" + finalDay;
    }

    /**
     * Decisión pura del final (legado: 0 vivos sella sin mirar online).
     *
     * <p>Compatibilidad: equivale a {@code decide(day, finalDay, sunReserved, 0, 0)}
     * (nadie conectado). El flujo real de temporada ({@code SeasonService.checkFinale})
     * debe usar {@link #decide(int, int, boolean, int, int)} con el roster y el online
     * reales: la extinción temprana solo sella con 0 vivos Y nadie conectado
     * (plan 3.2/3.3: el último muerto online en La Fosa mantiene RUNNING).
     *
     * @param day día real actual
     * @param finalDay día final real (base + extensión)
     * @param sunReserved Sol reservado/pagado (30 Núcleos)
     * @param zeroAlive señal B3: 0 vivos del roster
     * @return VICTORY si día final con Sol; DEFEAT si día final sin Sol o
     *         extinción temprana; RUNNING en otro caso.
     */
    public static Status decide(int day, int finalDay, boolean sunReserved, boolean zeroAlive) {
        return decide(day, finalDay, sunReserved, zeroAlive ? 0 : 1, 0);
    }

    /**
     * Decisión pura del final con roster y online separados (el coste/gate N
     * sigue siendo roster, nunca online; aquí el online solo frena el sellado
     * prematuro por extinción).
     *
     * <p>Regla B4: antes del plazo, la extinción (0 vivos del roster) solo sella
     * DEFEAT si además no hay nadie conectado ({@code onlineCount <= 0}). Con
     * alguien online (el último muerto jugando La Fosa, válvula de 5 días)
     * devuelve RUNNING y la temporada sigue. El día final cierra siempre con el
     * resultado del banco, haya o no conectados.
     *
     * @param day día real actual
     * @param finalDay día final real (base + extensión)
     * @param sunReserved Sol reservado/pagado (30 Núcleos)
     * @param aliveN vivos del roster (N, nunca online)
     * @param onlineCount conectados ahora (cualquier jugador)
     * @return VICTORY si día final con Sol; DEFEAT si día final sin Sol o
     *         extinción con nadie conectado; RUNNING en otro caso.
     */
    public static Status decide(int day, int finalDay, boolean sunReserved, int aliveN, int onlineCount) {
        if (day >= finalDay) {
            return sunReserved ? Status.VICTORY : Status.DEFEAT;
        }
        if (aliveN <= 0 && onlineCount <= 0) {
            return Status.DEFEAT;
        }
        return Status.RUNNING;
    }

    /** ¿Ya terminó? (VICTORY o DEFEAT, nunca vuelve a RUNNING). */
    public static boolean isEnded(String finaleStatus) {
        return Status.VICTORY.name().equals(finaleStatus)
                || Status.DEFEAT.name().equals(finaleStatus);
    }

    /** ¿Toca evaluar el final? Solo en día final y aún en RUNNING. */
    public static boolean canTrigger(int day, int finalDay, String finaleStatus) {
        return !isEnded(finaleStatus) && day >= finalDay;
    }

    /**
     * Enciende el Sol con fondos del Banco (idempotente por tx determinista).
     * Si ya está reservado devuelve {@code true} sin cobrar; si no hay fondos
     * devuelve {@code false} sin mutar; repetir el mismo tx no cobra dos veces.
     */
    public static boolean tryLightSun(EclipseBank bank, String seasonId, int finalDay) {
        if (bank == null) {
            return false;
        }
        if (bank.isSunReserved()) {
            return true;
        }
        String tx = finaleTxId(seasonId, finalDay);
        if (!bank.reserveSun(tx)) {
            // Replay del mismo tx ya en escrow cuenta como éxito pendiente.
            return bank.escrow().containsKey(tx);
        }
        return bank.confirmSun(tx);
    }

    /**
     * Confirma una reserva del Sol ya pagada al escrow (flujo normal en dos
     * pasos: {@code /pd banco comprar sol} deja 30 Núcleos en escrow y la
     * confirmación es otro comando). Al día final, una reserva pendiente
     * equivale al pago: se confirma una sola vez (idempotente por tx) en vez
     * de declarar DERROTA con los fondos retenidos. Sin escrow pendiente
     * devuelve {@code false} sin mutar.
     */
    public static boolean confirmPendingSun(EclipseBank bank) {
        if (bank == null) {
            return false;
        }
        if (bank.isSunReserved()) {
            return true;
        }
        for (String tx : new java.util.ArrayList<>(bank.escrow().keySet())) {
            if (bank.confirmSun(tx)) {
                return true;
            }
        }
        return bank.isSunReserved();
    }

    /** Normaliza un estado leído del disco (ilegible cae a RUNNING, nunca null). */
    public static String normalizeStatus(String raw) {
        if (Status.VICTORY.name().equals(raw) || Status.DEFEAT.name().equals(raw)) {
            return raw;
        }
        return Status.RUNNING.name();
    }
}
