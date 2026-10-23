package mx.vicko.permadeath;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Registro permanente por UUID; sigue funcionando aunque el jugador cambie de nombre. */
public final class DeathRegistry {
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final Logger logger;

    public DeathRegistry(PermadeathPlugin plugin) {
        this(plugin.getDataFolder(), plugin.getLogger());
    }

    DeathRegistry(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "dead-players.yml");
        this.logger = logger;
    }

    public void load() {
        if (!file.exists()) return;
        try {
            data.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            logger.severe("No se pudo leer dead-players.yml: " + exception.getMessage());
        }
    }

    public boolean isDead(UUID uuid) {
        return data.contains("dead." + uuid);
    }

    public void record(UUID uuid, String name, String cause) {
        String path = "dead." + uuid;
        data.set(path + ".name", name);
        data.set(path + ".cause", cause);
        data.set(path + ".timestamp", Instant.now().toString());
        save();
    }

    public boolean revive(String name) {
        DeadPlayer target = findDead(name);
        if (target == null) return false;
        try {
            return revive(UUID.fromString(target.uuid()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Vía normal: exactamente una resurrección por UUID en toda la partida. */
    public boolean revive(UUID uuid) {
        if (isDead(uuid) == false) return false;
        if (hasBeenRevived(uuid)) return false;
        DeadPlayer target = findDead(uuid);
        if (target == null) return false;
        String revivedPath = "revived." + uuid;
        data.set(revivedPath + ".name", target.name());
        data.set(revivedPath + ".timestamp", Instant.now().toString());
        data.set("pending-revival." + uuid, true);
        data.set("dead." + uuid, null);
        save();
        return true;
    }

    /**
     * Bloque 3 — Vía de temporada: la guarda de {@code revived.*} queda como
     * historial, NO como bloqueo (el tope 1+2+3=6 lo impone {@code revivals.*}
     * del ledger de temporada via RevivalPolicy). Cada entrada debe validarse
     * con la política antes de llamar aquí; este método solo registra.
     * Idempotente ante doble ritual/restart: si ya no está eliminado, false sin mutar.
     */
    public boolean reviveSeasonal(UUID uuid) {
        if (uuid == null || !isDead(uuid)) {
            return false;
        }
        DeadPlayer target = findDead(uuid);
        if (target == null) {
            return false;
        }
        String revivedPath = "revived." + uuid;
        if (!data.contains(revivedPath + ".name")) {
            data.set(revivedPath + ".name", target.name());
            data.set(revivedPath + ".timestamp", Instant.now().toString());
        }
        data.set("pending-revival." + uuid, true);
        data.set("dead." + uuid, null);
        save();
        return true;
    }

    /**
     * Bloque 3 — igual que {@link #reviveSeasonal(UUID)} pero reporta si el
     * registro quedó durable en disco. El escrow solo hace commit si esto
     * devuelve {@code true}; si el disco falla, la vuelta se deniega con refund.
     */
    public boolean reviveSeasonalChecked(UUID uuid) {
        if (uuid == null || !isDead(uuid)) {
            return false;
        }
        DeadPlayer target = findDead(uuid);
        if (target == null) {
            return false;
        }
        String revivedPath = "revived." + uuid;
        if (!data.contains(revivedPath + ".name")) {
            data.set(revivedPath + ".name", target.name());
            data.set(revivedPath + ".timestamp", Instant.now().toString());
        }
        data.set("pending-revival." + uuid, true);
        data.set("dead." + uuid, null);
        return saveChecked();
    }

    /**
     * Vía forzada administrativa: restaura aunque el UUID ya consumiera su
     * resurrección normal. Conserva el historial (no borra {@code revived.*}),
     * por lo que la siguiente muerte sigue bloqueada para vías normales.
     */
    public boolean reviveForced(UUID uuid) {
        DeadPlayer target = findDead(uuid);
        if (target == null) return false;
        if (!hasBeenRevived(uuid)) {
            String revivedPath = "revived." + uuid;
            data.set(revivedPath + ".name", target.name());
            data.set(revivedPath + ".timestamp", Instant.now().toString());
            data.set(revivedPath + ".forced", true);
        }
        data.set("pending-revival." + uuid, true);
        data.set("dead." + uuid, null);
        save();
        return true;
    }

    public boolean reviveForced(String name) {
        DeadPlayer target = findDead(name);
        if (target == null) return false;
        try {
            return reviveForced(UUID.fromString(target.uuid()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public DeadPlayer findDead(String name) {
        ConfigurationSection section = data.getConfigurationSection("dead");
        if (section == null) return null;
        for (String key : section.getKeys(false)) {
            String path = "dead." + key;
            if (name.equalsIgnoreCase(data.getString(path + ".name", "")) || key.equalsIgnoreCase(name)) {
                return new DeadPlayer(key, data.getString(path + ".name", "desconocido"),
                        data.getString(path + ".cause", "sin dato"), data.getString(path + ".timestamp", ""));
            }
        }
        return null;
    }

    public DeadPlayer findDead(UUID uuid) {
        String path = "dead." + uuid;
        if (!data.contains(path)) return null;
        return new DeadPlayer(uuid.toString(), data.getString(path + ".name", "desconocido"),
                data.getString(path + ".cause", "sin dato"), data.getString(path + ".timestamp", ""));
    }

    public boolean hasBeenRevived(UUID uuid) {
        return hasBeenRevived(uuid.toString());
    }

    public boolean hasBeenRevived(String uuid) {
        return data.contains("revived." + uuid);
    }

    public boolean takePendingRevival(UUID uuid) {
        String path = "pending-revival." + uuid;
        if (!data.getBoolean(path, false)) return false;
        data.set(path, null);
        save();
        return true;
    }

    public List<DeadPlayer> list() {
        ConfigurationSection section = data.getConfigurationSection("dead");
        if (section == null) return List.of();
        List<DeadPlayer> result = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String path = "dead." + id;
            result.add(new DeadPlayer(id, data.getString(path + ".name", "desconocido"),
                    data.getString(path + ".cause", "sin dato"), data.getString(path + ".timestamp", "")));
        }
        result.sort(Comparator.comparing(DeadPlayer::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void save() {
        saveChecked();
    }

    /** Guarda y reporta el éxito (el escrow de B3 no confirma vueltas sin disco durable). */
    boolean saveChecked() {
        try {
            data.save(file);
            return true;
        } catch (IOException exception) {
            logger.severe("No se pudo guardar el registro de permadeath: " + exception.getMessage());
            return false;
        }
    }

    public record DeadPlayer(String uuid, String name, String cause, String timestamp) { }
}
