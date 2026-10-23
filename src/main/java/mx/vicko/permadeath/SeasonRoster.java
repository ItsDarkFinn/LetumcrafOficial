package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roster cerrado de temporada. Puro, sin Bukkit.
 *
 * <p>La lista explicita de UUID es la fuente de verdad cuando existe:
 * {@code M} = tamano real de la lista. Sin lista se usa un fallback numerico
 * (default 16) solo para calculos, sin inventar UUID ni autorrellenar con
 * nuevos jugadores (roster cerrado). {@code N} = roster menos eliminados;
 * nunca se cuentan conectados para vivos/precio.
 */
public final class SeasonRoster {

    public static final int FALLBACK_ROSTER_SIZE = 16;
    public static final int DEFAULT_GATE_PERCENT = 40;
    public static final int MIN_GATE_ONLINE = 2;
    public static final int SUSPEND_BELOW_ALIVE = 4;

    private final List<UUID> members;

    /** Copia defensiva, sin nulos ni duplicados, preservando orden. */
    public SeasonRoster(Collection<UUID> members) {
        if (members == null || members.isEmpty()) {
            this.members = List.of();
        } else {
            LinkedHashSet<UUID> dedup = new LinkedHashSet<>();
            for (UUID id : members) {
                if (id != null) {
                    dedup.add(id);
                }
            }
            this.members = List.copyOf(dedup);
        }
    }

    public static SeasonRoster empty() {
        return new SeasonRoster(List.of());
    }

    /** Miembros del roster (vista inmodificable, fuente de verdad). */
    public List<UUID> members() {
        return members;
    }

    /** M real cuando hay lista; 0 si no hay lista (usar {@link #effectiveSize}). */
    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean isMember(UUID id) {
        return id != null && members.contains(id);
    }

    /**
     * Roster cerrado: solo un UUID ya listado seria admitido. Nunca
     * autoincluye gente nueva.
     */
    public boolean wouldAdmit(UUID id) {
        return isMember(id);
    }

    /** M efectivo: tamano real, o {@code fallback} si no hay lista. */
    public int effectiveSize(int fallback) {
        if (!members.isEmpty()) {
            return members.size();
        }
        return Math.max(1, fallback);
    }

    /** M efectivo con fallback 16. */
    public int effectiveSize() {
        return effectiveSize(FALLBACK_ROSTER_SIZE);
    }

    /**
     * N = roster menos eliminados. Los UUID eliminados que no estan en el
     * roster no restan. Sin lista no hay fuente de verdad y devuelve 0
     * (no inventa vivos).
     */
    public int aliveCount(Set<UUID> eliminated) {
        if (members.isEmpty()) {
            return 0;
        }
        if (eliminated == null || eliminated.isEmpty()) {
            return members.size();
        }
        int n = 0;
        for (UUID id : members) {
            if (!eliminated.contains(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Gate unico online: {@code max(2, ceil(M*percent/100), configuredMinOnline)}.
     * {@code configuredMinOnline} (p. ej. {@code minimum-online-players}) prevalece
     * si es mayor; documentado: el default heredado 15 domina al 7 calculado
     * para 16 jugadores al 40%.
     */
    public static int requiredOnline(int effectiveM, int gatePercent, int configuredMinOnline) {
        int m = Math.max(1, effectiveM);
        int p = Math.min(100, Math.max(0, gatePercent));
        int cfg = Math.max(0, configuredMinOnline);
        int byPercent = (int) Math.ceil(m * (p / 100.0));
        return Math.max(MIN_GATE_ONLINE, Math.max(byPercent, cfg));
    }

    /** Gate con M efectivo de este roster. */
    public int requiredOnline(int gatePercent, int configuredMinOnline) {
        return requiredOnline(effectiveSize(), gatePercent, configuredMinOnline);
    }

    /**
     * Online elegibles para el gate: interseccion de conectados con el roster,
     * excluyendo eliminados. Nunca cuenta outsiders; sin lista devuelve 0.
     */
    public int eligibleOnlineCount(Collection<UUID> online, Set<UUID> eliminated) {
        if (online == null || online.isEmpty() || members.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (UUID id : online) {
            if (id != null && members.contains(id)
                    && (eliminated == null || !eliminated.contains(id))) {
                n++;
            }
        }
        return n;
    }

    /** Efectos suspendidos con N &lt; 4 (bossbar/calendario siguen). */
    public static boolean areEffectsSuspended(int aliveN) {
        return aliveN < SUSPEND_BELOW_ALIVE;
    }

    /** Copia de UUIDs para el integrador (defensiva). */
    public List<UUID> copyMembers() {
        return new ArrayList<>(members);
    }
}
