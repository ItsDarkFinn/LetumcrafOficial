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
        // La Falla de la Odisea gestiona su propio ban permanente y no debe
        // contaminar la temporada, el marcador de muerte ni el permadeath
        // global de los mundos vanilla.
        if (plugin.odyssey() != null && plugin.odyssey().isOdyssey(event.getEntity().getWorld())) {
            return;
        }
        // Bloque 4 — tras el sellado no se registra, no se cuenta, no se expulsa
        // ni se anuncia: la foto del final queda congelada y el muerto ve /pd final.
        try {
            if (plugin.seasonService() != null && plugin.seasonService().isSeasonEnded()) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
        Player player = event.getEntity();
        var deathLocation = player.getLocation().clone();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.deathMarker().place(player, deathLocation), 1L);
        if (!plugin.getConfig().getBoolean("permadeath.enabled", true)
                || player.hasPermission("permadeath.bypassdeath")) return;

        String reason = plugin.getConfig().getString("permadeath.ban-reason", "Has muerto.");
        String cause = event.getDeathMessage() == null ? "muerte" : ChatColor.stripColor(event.getDeathMessage());
        plugin.deaths().record(player.getUniqueId(), player.getName(), cause);
        try {
            if (plugin.seasonService() != null && plugin.seasonService().isSeasonActive()) {
                plugin.seasonService().noteSeasonDeath(player.getUniqueId());
            }
        } catch (RuntimeException ignored) {
        }
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
        if (!plugin.getConfig().getBoolean("permadeath.enabled", true)) {
              return;
          }
          if (!plugin.deaths().isDead(event.getPlayer().getUniqueId())) {
              return;
          }
        // Bloque 3: en temporada, el muerto elegible entra SOLO a La Fosa
        // (PitController lo teleporta y contiene); fuera de temporada/Fosa, ban legado.
        try {
            if (plugin.seasonService() != null && plugin.seasonService().isSeasonActive()
                    && plugin.seasonService().isPitEligible(event.getPlayer().getUniqueId())) {
                return;
            }
            // Bloque 4 — tras el final, el roster (incluidos muertos) entra a ver
            // estadísticas; no se mantiene muerte activa por error ni se patea.
            if (plugin.seasonService() != null && plugin.seasonService().isSeasonEnded()) {
                return;
            }
        } catch (RuntimeException ignored) {
        }
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                color(plugin.getConfig().getString("permadeath.ban-reason", "Has muerto.")));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
