package mx.vicko.permadeath.odyssey;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Terracota blanca efímera. El estado vive en un mapa concurrente y cada
 * bloque usa una única tarea de repetición de 10 ticks; no se crea una tarea
 * por jugador ni se recorre el mundo cada tick.
 */
public final class OdysseyEphemeralBlocks implements Listener {
    private static final int STEP_TICKS = 10;
    private static final int MAX_ACTIVE = 768;

    private final JavaPlugin plugin;
    private final OdysseyManager odyssey;
    private final Map<BlockKey, BukkitTask> active = new ConcurrentHashMap<>();

    public OdysseyEphemeralBlocks(JavaPlugin plugin, OdysseyManager odyssey) {
        this.plugin = plugin;
        this.odyssey = odyssey;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !odyssey.isOdyssey(event.getTo().getWorld())) {
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        Block below = event.getTo().clone().subtract(0.0, 1.0, 0.0).getBlock();
        if (below.getType() == Material.WHITE_TERRACOTTA) {
            start(below);
        }
    }

    /**
     * Inicia el ciclo blanco -> amarillo -> rojo -> aire. Es idempotente: si
     * el bloque ya tiene una tarea activa no se duplica.
     */
    public void start(Block block) {
        if (block == null || !odyssey.isOdyssey(block.getWorld())) {
            return;
        }
        if (active.size() >= MAX_ACTIVE) {
            return;
        }

        BlockKey key = BlockKey.from(block);
        if (active.containsKey(key)) {
            return;
        }

        BukkitTask task = new org.bukkit.scheduler.BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (!plugin.isEnabled() || !odyssey.isOdyssey(block.getWorld())) {
                    finish(key);
                    return;
                }
                if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                    finish(key);
                    return;
                }

                switch (step++) {
                    case 0 -> block.getWorld().playSound(
                            block.getLocation().add(0.5, 0.5, 0.5),
                            Sound.UI_BUTTON_CLICK, 0.8f, 1.35f);
                    case 1 -> {
                        if (block.getType() != Material.WHITE_TERRACOTTA) {
                            finish(key);
                            return;
                        }
                        block.setType(Material.YELLOW_TERRACOTTA, false);
                    }
                    case 2 -> {
                        if (block.getType() != Material.YELLOW_TERRACOTTA) {
                            finish(key);
                            return;
                        }
                        block.setType(Material.RED_TERRACOTTA, false);
                    }
                    default -> {
                        if (block.getType() == Material.RED_TERRACOTTA) {
                            block.setType(Material.AIR, false);
                        }
                        finish(key);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, STEP_TICKS);

        BukkitTask previous = active.putIfAbsent(key, task);
        if (previous != null) {
            task.cancel();
        }
    }

    public void cancel(Block block) {
        if (block == null) {
            return;
        }
        BukkitTask task = active.remove(BlockKey.from(block));
        if (task != null) {
            task.cancel();
        }
    }

    public void stop() {
        for (BukkitTask task : active.values()) {
            task.cancel();
        }
        active.clear();
    }

    public int activeCount() {
        return active.size();
    }

    private void finish(BlockKey key) {
        BukkitTask task = active.remove(key);
        if (task != null) {
            task.cancel();
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
