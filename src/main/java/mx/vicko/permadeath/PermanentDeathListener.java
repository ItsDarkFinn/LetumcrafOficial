package mx.vicko.permadeath;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerLoginEvent;

public final class PermanentDeathListener implements Listener {
    private final PermadeathPlugin plugin;

    public PermanentDeathListener(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var deathLocation = player.getLocation().clone();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.deathMarker().place(player, deathLocation), 1L);
        if (!plugin.getConfig().getBoolean("permadeath.enabled", true)
                || player.hasPermission("permadeath.bypassdeath")) return;

        String reason = plugin.getConfig().getString("permadeath.ban-reason", "Has muerto.");
        String cause = event.getDeathMessage() == null ? "muerte" : ChatColor.stripColor(event.getDeathMessage());
        plugin.deaths().record(player.getUniqueId(), player.getName(), cause);
        String title = ChatColor.RED.toString() + ChatColor.BOLD + "Letumcraft";
        String subtitle = ChatColor.WHITE + player.getName() + " ha muerto";
        plugin.getServer().getOnlinePlayers().forEach(viewer ->
                viewer.sendTitle(title, subtitle, 10, 80, 20));
        plugin.getServer().broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                + "&c" + player.getName() + " murió y quedó fuera de la partida."));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.kickPlayer(color(reason)), 2L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        if (plugin.getConfig().getBoolean("permadeath.enabled", true)
                && plugin.deaths().isDead(event.getPlayer().getUniqueId())) {
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    color(plugin.getConfig().getString("permadeath.ban-reason", "Has muerto.")));
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
