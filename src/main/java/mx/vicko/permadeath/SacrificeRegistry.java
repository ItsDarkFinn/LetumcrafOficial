package mx.vicko.permadeath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bloque 2 — Registro puro de sacrificios (sin Bukkit ni IO).
 *
 * <p>Irreversible por UUID: la primera elección gana; las siguientes se rechazan
 * (devuelven {@code false}) aunque el jugador muera, reviva o reinicie.
 * La durabilidad la da {@code season-state.yml} vía {@link SeasonStore};
 * este registro solo guarda memoria y se rehidrata con {@link #fromStringMap}.
 */
public final class SacrificeRegistry {

    private final Map<UUID, SacrificeType> choices = new LinkedHashMap<>();

    /** Primera elección: {@code true}. Si el UUID ya eligió: {@code false} (sin cambios). */
    public boolean choose(UUID uuid, SacrificeType type) {
        if (uuid == null || type == null) {
            return false;
        }
        if (choices.containsKey(uuid)) {
            return false;
        }
        choices.put(uuid, type);
        return true;
    }

    public Optional<SacrificeType> get(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(choices.get(uuid));
    }

    public boolean has(UUID uuid) {
        return uuid != null && choices.containsKey(uuid);
    }

    public int size() {
        return choices.size();
    }

    public Map<UUID, SacrificeType> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(choices));
    }

    /** Foto serializable {@code uuid-string -> TYPE-id} (ordenada por UUID para idempotencia). */
    public Map<String, String> toStringMap() {
        Map<String, String> out = new LinkedHashMap<>();
        choices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey().toString(), e.getValue().id()));
        return out;
    }

    /** Rehidrata sin validar rarezas: entradas ilegibles se omiten, nunca lanzan. */
    public static SacrificeRegistry fromStringMap(Map<String, String> raw) {
        SacrificeRegistry registry = new SacrificeRegistry();
        if (raw == null) {
            return registry;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey().trim());
                SacrificeType type = SacrificeType.fromId(entry.getValue());
                if (type != null) {
                    registry.choices.putIfAbsent(uuid, type);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return registry;
    }
}
