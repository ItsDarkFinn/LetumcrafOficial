package mx.vicko.permadeath;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Persiste y aplica temporalmente la pérdida de cinco corazones por abandonar un ritual. */
public final class RitualPenaltyManager implements Listener {
    private static final double MAX_HEALTH_REDUCTION = 10.0;
    private final PermadeathPlugin plugin;
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final Map<UUID, Long> expiryTimes = new LinkedHashMap<>();
    private final NamespacedKey modifierKey;

    public RitualPenaltyManager(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ritual-penalties.yml");
        this.modifierKey = new NamespacedKey(plugin, "ritual_leaver_penalty");
    }

    public void register() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) refresh(player, false);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expireEntries, 20L, 20L);
    }

    public void penalize(UUID playerId, String playerName) {
        long seconds = Math.max(1L, plugin.getConfig().getLong(
                "resurrection-ritual.leaver-penalty-seconds", 3600L));
        expiryTimes.put(playerId, System.currentTimeMillis() + seconds * 1000L);
        save();
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            refresh(player, false);
            player.sendMessage(ChatColor.RED + "Abandonaste el ritual: tu vida máxima se reduce en 5 corazones durante "
                    + formatDuration(seconds) + ".");
        } else {
            plugin.getLogger().info("Penalización de ritual aplicada a " + playerName + " al volver a conectarse.");
        }
    }

    public void clearOnlineModifiers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) removeModifier(player);
    }

    private void refresh(Player player, boolean announce) {
        removeModifier(player);
        Long expiresAt = expiryTimes.get(player.getUniqueId());
        if (expiresAt == null) return;
        if (expiresAt <= System.currentTimeMillis()) {
            expiryTimes.remove(player.getUniqueId());
            save();
            return;
        }
        addModifier(player);
        if (announce) {
            long remaining = Math.max(1L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
            player.sendMessage(ChatColor.RED + "La penalización del ritual sigue activa por "
                    + formatDuration(remaining) + ".");
        }
    }

    private void addModifier(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        attribute.removeModifier(modifierKey);
        attribute.addTransientModifier(new AttributeModifier(
                modifierKey, -MAX_HEALTH_REDUCTION, AttributeModifier.Operation.ADD_NUMBER));
        if (player.getHealth() > attribute.getValue()) player.setHealth(attribute.getValue());
    }

    private void removeModifier(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) attribute.removeModifier(modifierKey);
    }

    private void expireEntries() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<UUID, Long> entry : Map.copyOf(expiryTimes).entrySet()) {
            if (entry.getValue() > now) continue;
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) removeModifier(player);
            expiryTimes.remove(entry.getKey());
            changed = true;
        }
        if (changed) save();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            data.load(file);
            for (String key : data.getKeys(false)) {
                try {
                    expiryTimes.put(UUID.fromString(key), data.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("UUID inválido en ritual-penalties.yml: " + key);
                }
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            plugin.getLogger().severe("No se pudieron cargar las penalizaciones del ritual: " + exception.getMessage());
        }
    }

    private void save() {
        for (Map.Entry<UUID, Long> entry : expiryTimes.entrySet()) {
            data.set(entry.getKey().toString(), entry.getValue());
        }
        for (String key : data.getKeys(false)) {
            try {
                if (!expiryTimes.containsKey(UUID.fromString(key))) data.set(key, null);
            } catch (IllegalArgumentException ignored) {
                data.set(key, null);
            }
        }
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("No se pudieron guardar las penalizaciones del ritual: " + exception.getMessage());
        }
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + (seconds == 1 ? " segundo" : " segundos");
        if (seconds >= 3600 && seconds % 3600 < 60) {
            long hours = seconds / 3600;
            return hours + (hours == 1 ? " hora" : " horas");
        }
        long minutes = Math.max(1L, (seconds + 59L) / 60L);
        return minutes == 1 ? "1 minuto" : minutes + " minutos";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeModifier(event.getPlayer());
    }
}
