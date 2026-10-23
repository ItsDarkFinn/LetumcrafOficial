package mx.vicko.permadeath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bloque 2 — Banco Eclipse colectivo (puro, sin Bukkit ni IO).
 *
 * <p>Fondo colectivo en Núcleos auténticos; sin inventarios individuales y sin Chispas
 * (las Chispas son personales, nunca entran al banco: no hay depósito ni conversión).
 *
 * <p>Compras aprobadas, precios fijos:
 * <ul>
 *   <li>12 Núcleos → extensión única de 3 días, tope 45 días totales (rechaza 44+3&gt;45 sin cobrar).</li>
 *   <li>8 Núcleos → abre el ritual de resurrección global persistente (no paga Chispas del muerto).</li>
 *   <li>30 Núcleos → reserva/pago del Sol del último día (contrato para B4: reserva en escrow, pago idempotente).</li>
 * </ul>
 *
 * <p>Reglas duras: el saldo nunca es negativo; sin overdraft (fondos insuficientes = {@code false}
 * sin mutación); sin repetición de tx (los {@code txId} exitosos son idempotentes: repetirlos devuelve
 * éxito sin cobrar dos veces); sin clamp tras cobrar (primero se valida el tope, luego se cobra).
 */
public final class EclipseBank {

    public static final int EXTENSION_COST = 12;
    public static final int EXTENSION_DAYS = 3;
    public static final int MAX_SEASON_LENGTH = 45;
    public static final int RITUAL_COST = 8;
    public static final int SUN_COST = 30;

    private int balance;
    private int totalDeposited;
    private int totalSpent;
    private boolean extensionUsed;
    private boolean ritualOpen;
    private boolean sunReserved;
    private final Set<String> processedTx = new LinkedHashSet<>();
    private final Map<String, Integer> escrow = new LinkedHashMap<>();

    public int balance() {
        return balance;
    }

    public int totalDeposited() {
        return totalDeposited;
    }

    public int totalSpent() {
        return totalSpent;
    }

    public boolean isExtensionUsed() {
        return extensionUsed;
    }

    public boolean isRitualOpen() {
        return ritualOpen;
    }

    public boolean isSunReserved() {
        return sunReserved;
    }

    public Set<String> processedTx() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(processedTx));
    }

    public Map<String, Integer> escrow() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(escrow));
    }

    /** Depósito de Núcleos auténticos. Cantidad &lt;= 0 o negativa = {@code false} sin mutación. */
    public boolean deposit(int cores) {
        if (cores <= 0) {
            return false;
        }
        balance += cores;
        totalDeposited += cores;
        return true;
    }

    public boolean canAfford(int cost) {
        return cost > 0 && balance >= cost;
    }

    /**
     * Compra 1: extensión única +3 días. Idempotente por {@code txId}.
     * Rechaza (sin cobrar) si ya se usó, si no cabe en el tope 45 o si no hay fondos.
     *
     * @return {@code true} si la extensión queda aplicada (incluido replay del mismo tx).
     */
    public boolean purchaseExtension(String txId, int currentLengthDays) {
        String tx = requireTx(txId);
        if (tx == null) {
            return false;
        }
        if (processedTx.contains(tx)) {
            return extensionUsed;
        }
        if (extensionUsed) {
            return false;
        }
        if (currentLengthDays + EXTENSION_DAYS > MAX_SEASON_LENGTH) {
            return false;
        }
        if (balance < EXTENSION_COST) {
            return false;
        }
        balance -= EXTENSION_COST;
        totalSpent += EXTENSION_COST;
        extensionUsed = true;
        processedTx.add(tx);
        return true;
    }

    /** Nueva longitud tras la extensión (pura, sin mutar). */
    public static int extendedLength(int currentLengthDays) {
        return currentLengthDays + EXTENSION_DAYS;
    }

    /** Compra 2: abre el ritual global. Idempotente por {@code txId}; una sola vez. */
    public boolean purchaseRitual(String txId) {
        String tx = requireTx(txId);
        if (tx == null) {
            return false;
        }
        if (processedTx.contains(tx)) {
            return ritualOpen;
        }
        if (ritualOpen) {
            return false;
        }
        if (balance < RITUAL_COST) {
            return false;
        }
        balance -= RITUAL_COST;
        totalSpent += RITUAL_COST;
        ritualOpen = true;
        processedTx.add(tx);
        return true;
    }

    /**
     * Compra 3a: reserva 30 Núcleos en escrow para el Sol (contrato B4).
     * Mueve 30 del saldo al escrow del {@code txId}; idempotente por tx.
     * Solo una reserva pendiente: con escrow ajeno pendiente se rechaza sin mutar.
     */
    public boolean reserveSun(String txId) {
        String tx = requireTx(txId);
        if (tx == null) {
            return false;
        }
        if (processedTx.contains(tx)) {
            return sunReserved;
        }
        if (sunReserved) {
            return false;
        }
        if (escrow.containsKey(tx)) {
            return true;
        }
        if (!escrow.isEmpty()) {
            return false;
        }
        if (balance < SUN_COST) {
            return false;
        }
        balance -= SUN_COST;
        escrow.put(tx, SUN_COST);
        return true;
    }

    /**
     * Compra 3b: confirma la reserva (pago). Consume el escrow del {@code txId}
     * y marca el Sol reservado. Idempotente: repetir el mismo tx = {@code true} sin cobrar de más;
     * otro tx tras el pago = {@code false} sin cobrar ni colgar escrow.
     */
    public boolean confirmSun(String txId) {
        String tx = requireTx(txId);
        if (tx == null) {
            return false;
        }
        if (processedTx.contains(tx)) {
            return sunReserved;
        }
        if (sunReserved) {
            return false;
        }
        Integer held = escrow.remove(tx);
        if (held == null) {
            return false;
        }
        totalSpent += held;
        sunReserved = true;
        processedTx.add(tx);
        return true;
    }

    /** Cancela una reserva: devuelve el escrow al saldo. Inexistente = {@code false}. */
    public boolean cancelSun(String txId) {
        String tx = requireTx(txId);
        if (tx == null) {
            return false;
        }
        Integer held = escrow.remove(tx);
        if (held == null) {
            return false;
        }
        balance += held;
        return true;
    }

    /** Consumo interno de 1 Núcleo (p. ej. Ancla del grupo). Sin overdraft. */
    public boolean consume(int cores) {
        if (cores <= 0 || balance < cores) {
            return false;
        }
        balance -= cores;
        totalSpent += cores;
        return true;
    }

    private static String requireTx(String txId) {
        if (txId == null || txId.isBlank()) {
            return null;
        }
        return txId.trim();
    }

    /** Foto serializable para {@code season-state.yml} (determinista). */
    public Map<String, String> toStringMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("balance", Integer.toString(balance));
        out.put("totalDeposited", Integer.toString(totalDeposited));
        out.put("totalSpent", Integer.toString(totalSpent));
        out.put("extensionUsed", Boolean.toString(extensionUsed));
        out.put("ritualOpen", Boolean.toString(ritualOpen));
        out.put("sunReserved", Boolean.toString(sunReserved));
        return out;
    }

    /** Rehidrata de forma tolerante (lo ilegible cae a 0/false, nunca lanza). */
    public static EclipseBank fromParts(int balance, int totalDeposited, int totalSpent,
            boolean extensionUsed, boolean ritualOpen, boolean sunReserved,
            Set<String> txIds, Map<String, Integer> escrowHold) {
        EclipseBank bank = new EclipseBank();
        bank.balance = Math.max(0, balance);
        bank.totalDeposited = Math.max(0, totalDeposited);
        bank.totalSpent = Math.max(0, totalSpent);
        bank.extensionUsed = extensionUsed;
        bank.ritualOpen = ritualOpen;
        bank.sunReserved = sunReserved;
        if (txIds != null) {
            for (String tx : txIds) {
                if (tx != null && !tx.isBlank()) {
                    bank.processedTx.add(tx.trim());
                }
            }
        }
        if (escrowHold != null) {
            for (Map.Entry<String, Integer> e : escrowHold.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() > 0) {
                    bank.escrow.put(e.getKey().trim(), e.getValue());
                }
            }
        }
        return bank;
    }
}
