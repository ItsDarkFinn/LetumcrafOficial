package mx.vicko.permadeath;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Registro permanente por UUID; sigue funcionando aunque el jugador cambie de nombre. */
public final class DeathRegistry {
    private final PermadeathPlugin plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();

    public DeathRegistry(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dead-players.yml");
    }

    public void load() {
        if (!file.exists()) return;
        try {
            data.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("No se pudo leer dead-players.yml: " + exception.getMessage());
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
        if (target == null || hasBeenRevived(target.uuid())) return false;
        String revivedPath = "revived." + target.uuid();
        data.set(revivedPath + ".name", target.name());
        data.set(revivedPath + ".timestamp", Instant.now().toString());
        data.set("pending-revival." + target.uuid(), true);
        data.set("dead." + target.uuid(), null);
        save();
        return true;
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
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("No se pudo guardar el registro de permadeath: " + exception.getMessage());
        }
    }

    public record DeadPlayer(String uuid, String name, String cause, String timestamp) { }
}
