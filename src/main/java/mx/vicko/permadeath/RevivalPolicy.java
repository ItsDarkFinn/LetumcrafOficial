package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bloque 3 — Política de revivals (pura, sin Bukkit ni IO).
 *
 * <p>El coste en Chispas depende del número de vuelta (N = revivals+1, máx 3);
 * los requisitos de ritual dependen del roster (vivos del roster, NO online):
 * ≥3 vivos ritual completo (2 vivos + Sello), 2 vivos ritual mínimo
 * (1 vivo + Sello), ≤1 altar solitario (+1 Núcleo del banco). Todos pagan sus
 * propias Chispas N; el desbloqueo de 8 Núcleos del ritual (B2) sigue vigente.
 * Válvula de 5 días reales muerto con Chispas suficientes para N: altar
 * solitario SIN peaje aunque haya vivos. Cada entrada se valida.
 */
public final class RevivalPolicy {

    /** Camino de vuelta autorizado. */
    public enum Path {
        /** Ritual completo: 2 vivos + Sello (roster ≥3). */
        RITUAL_FULL,
        /** Ritual mínimo: 1 vivo + Sello (roster ==2). */
        RITUAL_MIN,
        /** Altar solitario: +1 Núcleo del banco (roster ≤1). */
        ALTAR_SOLO,
        /** Válvula 5 días: altar solitario sin peaje aunque haya vivos. */
        VALVE_SOLO,
        /** Denegado: sin camino. */
        DENIED
    }

    /** Decisión pura con mensajes de faltantes (sin spam: el llamador decide cuándo avisar). */
    public record Decision(Path path, int cost, boolean allowed, List<String> missing) {
        public Decision {
            missing = Collections.unmodifiableList(new ArrayList<>(missing));
        }
    }

    private RevivalPolicy() {
    }

    /**
     * Evalúa una entrada de vuelta.
     *
     * @param aliveRoster vivos del roster (N, nunca online)
     * @param revivals vueltas ya consumidas por el UUID en la temporada
     * @param chispaBalance saldo de Chispas (ganadas menos gastadas)
     * @param ritualOpen desbloqueo de 8 Núcleos del banco (B2)
     * @param bankBalance Núcleos disponibles en el banco
     * @param daysSinceDeath días reales desde la muerte
     * @param hasSeal el solicitante aporta Sello (solo vías de ritual)
     */
    public static Decision evaluate(int aliveRoster, int revivals, int chispaBalance,
            boolean ritualOpen, int bankBalance, long daysSinceDeath, boolean hasSeal) {
        int cost = PitLedger.nextCost(revivals);
        if (PitLedger.isRevivalMaxed(revivals)) {
            return denied(cost, "3 vueltas agotadas (1+2+3=6): sin 4ª vuelta aunque haya activos.");
        }
        if (chispaBalance < cost) {
            return denied(cost, "faltan Chispas: tienes " + Math.max(0, chispaBalance)
                    + ", la vuelta " + cost + " cuesta " + cost + " (juega La Fosa).");
        }
        // Válvula 5 días: sin peaje de Núcleo (el peaje solo existe en ALTAR_SOLO
        // con roster ≤1). Solo exige Chispas suficientes para el siguiente coste.
        if (PitLedger.valveApplies(daysSinceDeath, chispaBalance, cost)) {
            return allowed(Path.VALVE_SOLO, cost);
        }
        if (aliveRoster >= 3) {
            List<String> missing = new ArrayList<>();
            if (!ritualOpen) {
                missing.add("el grupo no abrió el ritual (8 Núcleos al banco).");
            }
            if (!hasSeal) {
                missing.add("falta el Sello de resurrección.");
            }
            if (!missing.isEmpty()) {
                return denied(cost, missing);
            }
            return allowed(Path.RITUAL_FULL, cost);
        }
        if (aliveRoster == 2) {
            List<String> missing = new ArrayList<>();
            if (!ritualOpen) {
                missing.add("el grupo no abrió el ritual (8 Núcleos al banco).");
            }
            if (!hasSeal) {
                missing.add("falta el Sello de resurrección.");
            }
            if (!missing.isEmpty()) {
                return denied(cost, missing);
            }
            return allowed(Path.RITUAL_MIN, cost);
        }
        if (bankBalance < 1) {
            return denied(cost, "altar solitario: el banco no tiene 1 Núcleo de peaje.");
        }
        return allowed(Path.ALTAR_SOLO, cost);
    }

    /** ¿Debe señalarse fin de temporada a B4? Solo con 0 vivos del roster (nunca online). */
    public static boolean shouldSignalSeasonEnd(int aliveRoster) {
        return aliveRoster <= 0;
    }

    /** Participantes mínimos del ritual reutilizado según el roster (variable, no paralelo). */
    public static int requiredParticipants(int aliveRoster) {
        if (aliveRoster == 2) {
            return 1;
        }
        return 2;
    }

    private static Decision allowed(Path path, int cost) {
        return new Decision(path, cost, true, List.of());
    }

    private static Decision denied(int cost, String missing) {
        return new Decision(Path.DENIED, cost, false, List.of(missing));
    }

    private static Decision denied(int cost, List<String> missing) {
        return new Decision(Path.DENIED, cost, false, missing);
    }
}

