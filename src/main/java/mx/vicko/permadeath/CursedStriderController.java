package mx.vicko.permadeath;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Rare, visibly marked striders become lethal by contact from day ten. */
public final class CursedStriderController implements Listener {
    private final PermadeathPlugin plugin;
    private BukkitTask task;

    public CursedStriderController(PermadeathPlugin plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkContacts, 10L, 10L);
    }

    public void shutdown() { if (task != null) { task.cancel(); task = null; } }

    /** Bloque 4 — tras el sellado los striders malditos no marcan ni matan. */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private int effectiveStriderStart() {
        int start = plugin.getConfig().getInt("random-events.nether.cursed-striders.start-day", 10);
        return plugin.seasonService() != null ? plugin.seasonService().mapLegacyDay(start) : start;
    }

    @EventHandler(ignoreCancelled = true)
    public void onStriderSpawn(CreatureSpawnEvent event) {
        if (seasonEnded()) return;
        if (!(event.getEntity() instanceof Strider strider)
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !plugin.getConfig().getBoolean("random-events.nether.cursed-striders.enabled", true)
                || plugin.days().getCurrentDay() < effectiveStriderStart()) return;
        double chance = Math.max(0, Math.min(1, plugin.getConfig().getDouble("random-events.nether.cursed-striders.chance", 0.10)));
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        strider.getPersistentDataContainer().set(plugin.cursedStriderKey(), PersistentDataType.BYTE, (byte) 1);
        strider.setGlowing(true);
        strider.setCustomName("§c§lStrider maldito");
        strider.setCustomNameVisible(true);
        strider.setPersistent(true);
    }

    private void checkContacts() {
        if (seasonEnded()) return;
        if (!plugin.getConfig().getBoolean("random-events.nether.cursed-striders.enabled", true)
                || plugin.days().getCurrentDay() < effectiveStriderStart()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || plugin.deaths().isDead(player.getUniqueId())) continue;
            if (player.getWorld().getEnvironment() != World.Environment.NETHER) continue;
            for (var entity : player.getNearbyEntities(1.35, 1.35, 1.35)) {
                if (entity instanceof Strider strider
                        && strider.getPersistentDataContainer().getOrDefault(plugin.cursedStriderKey(), PersistentDataType.BYTE, (byte) 0) == 1) {
                    player.sendTitle("§4§l¡STRIDER MALDITO!", "§cTe alcanzó", 0, 20, 5);
                    player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, player.getLocation().add(0, 1, 0), 20, .3, .5, .3, .02);
                    player.setHealth(0.0);
                    strider.remove();
                    break;
                }
            }
        }
    }
}
