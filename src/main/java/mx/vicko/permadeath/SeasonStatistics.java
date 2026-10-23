package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bloque 4 — Estadísticas del final (puro, sin Bukkit ni IO).
 *
 * <p>Se alimenta SOLO por hooks de eventos (join, muerte, depósito, gasto,
 * objetivo, sacrificio), nunca por escaneos periódicos ni por hash de la
 * lista viva de {@code dead-players.yml} (esa lista pierde historial al
 * revivir: el contador de por vida solo crece aquí).
 */
public final class SeasonStatistics {

    private SeasonStatistics() {
    }

    /** Días sobrevividos: desde el primer visto (join) hasta el día final, inclusivo. */
    public static int daysSurvived(int firstSeenDay, int finalDay) {
        int start = Math.max(1, firstSeenDay);
        if (start > finalDay) {
            return 0;
        }
        return finalDay - start + 1;
    }

    /**
     * Líneas del final para {@code /pd final} y broadcast (chat/title/libro).
     * Accesible a todo el roster, incluidos muertos: no requiere estar vivo.
     */
    public static List<String> buildLines(String seasonId, String status, int finalDay,
            String actor, String reason, int alive, int total,
            int deposited, int spent, Map<String, Integer> deathsByUuid,
            Map<String, String> sacrifices, List<String> events) {
        return buildLines(seasonId, status, finalDay, actor, reason, alive, total,
                deposited, spent, deathsByUuid, sacrifices, events,
                Map.of(), List.of());
    }

    /**
     * Líneas del final con días sobrevividos y nombres de quien llegó vivo.
     * {@code firstSeenDays} mapea UUID → primer día visto; {@code aliveNames}
     * trae los nombres ya resueltos de los vivos (vacío = solo conteo).
     */
    public static List<String> buildLines(String seasonId, String status, int finalDay,
            String actor, String reason, int alive, int total,
            int deposited, int spent, Map<String, Integer> deathsByUuid,
            Map<String, String> sacrifices, List<String> events,
            Map<String, Integer> firstSeenDays, List<String> aliveNames) {
        List<String> out = new ArrayList<>();
        boolean victory = SeasonFinale.Status.VICTORY.name().equals(status);
        out.add(victory ? "§6☀ El Sol se enciende — VICTORIA"
                : "§8☾ El Sol se apaga — DERROTA");
        out.add("§7Temporada §f" + seasonId + " §8· §7fin día §f" + finalDay
                + " §8· §7actor §f" + (actor == null || actor.isBlank() ? "—" : actor));
        if (reason != null && !reason.isBlank()) {
            out.add("§7" + reason);
        }
        out.add("§7Vivos al final: §a" + alive + "§7/§f" + total);
        List<String> names = aliveNames == null ? List.of() : aliveNames;
        out.add("§7Quién llegó vivo (§a" + alive + "§7): §a"
                + (names.isEmpty() ? "—" : String.join("§7, §a", names)));
        out.add("§7Días sobrevividos §8(fin día " + finalDay + "):");
        if (firstSeenDays == null || firstSeenDays.isEmpty()) {
            out.add("§8 - sin registro");
        } else {
            List<String> ids = new ArrayList<>(firstSeenDays.keySet());
            Collections.sort(ids);
            for (String id : ids) {
                int first = firstSeenDays.getOrDefault(id, finalDay);
                out.add("§8 - §f" + id + "§7: " + daysSurvived(first, finalDay) + " días");
            }
        }
        out.add("§7Núcleos: §e" + deposited + " reunidos§7, §c" + spent + " gastados");
        int totalDeaths = 0;
        for (int n : deathsByUuid.values()) {
            totalDeaths += Math.max(0, n);
        }
        out.add("§7Muertes de por vida: §c" + totalDeaths
                + " §7en §f" + deathsByUuid.size() + " §7jugadores");
        for (Map.Entry<String, Integer> e : deathsByUuid.entrySet()) {
            out.add("§8 - §f" + e.getKey() + "§7: " + e.getValue() + " muertes");
        }
        out.add("§7Sacrificios (" + sacrifices.size() + "):");
        for (Map.Entry<String, String> e : sacrifices.entrySet()) {
            out.add("§8 - §f" + e.getKey() + "§7: " + e.getValue());
        }
        out.add("§7Eventos completados (" + events.size() + "): "
                + (events.isEmpty() ? "ninguno" : String.join(", ", events)));
        return out;
    }
}
