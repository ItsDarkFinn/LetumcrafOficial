package mx.vicko.permadeath;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/** Deja una cabeza con la skin del jugador sobre una valla en un espacio seguro cercano. */
public final class DeathLocationMarker {
    private static final int DEFAULT_SEARCH_RADIUS = 4;
    private final PermadeathPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey playerIdKey;

    public DeathLocationMarker(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "death_marker");
        this.playerIdKey = new NamespacedKey(plugin, "death_marker_player");
    }

    public void place(Player player, Location deathLocation) {
        if (!plugin.getConfig().getBoolean("death-marker.enabled", true)) return;

        int radius = Math.max(0, Math.min(16, plugin.getConfig().getInt(
                "death-marker.search-radius", DEFAULT_SEARCH_RADIUS)));
        Location base = findSafeBase(deathLocation, radius);
        if (base == null) {
            plugin.getLogger().warning("No se encontró espacio seguro para el marcador de "
                    + player.getName() + " en " + format(deathLocation) + ". No se modificó ningún bloque.");
            return;
        }

        Block fence = base.getBlock();
        Block head = base.clone().add(0, 1, 0).getBlock();
        fence.setType(Material.OAK_FENCE, false);
        head.setType(Material.PLAYER_HEAD, false);
        if (!(head.getState() instanceof Skull skull)) {
            head.setType(Material.AIR, false);
            fence.setType(Material.AIR, false);
            plugin.getLogger().warning("No se pudo crear la cabeza de " + player.getName() + ".");
            return;
        }

        skull.setOwningPlayer(player);
        skull.setRotation(facing(player.getLocation().getYaw()));
        skull.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        skull.getPersistentDataContainer().set(playerIdKey, PersistentDataType.STRING, player.getUniqueId().toString());
        if (!skull.update(true, false)) {
            head.setType(Material.AIR, false);
            fence.setType(Material.AIR, false);
            plugin.getLogger().warning("No se pudo guardar la cabeza de " + player.getName() + ".");
            return;
        }

        plugin.getLogger().info("Marcador de muerte de " + player.getName() + " colocado en " + format(base) + ".");
    }

    private Location findSafeBase(Location deathLocation, int radius) {
        World world = deathLocation.getWorld();
        if (world == null) return null;
        int centerX = deathLocation.getBlockX();
        int centerY = deathLocation.getBlockY();
        int centerZ = deathLocation.getBlockZ();
        int[] verticalOffsets = {0, -1, 1, -2, 2};

        for (int ring = 0; ring <= radius; ring++) {
            for (int dy : verticalOffsets) {
                int y = centerY + dy;
                if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        Block base = world.getBlockAt(centerX + dx, y, centerZ + dz);
                        Block head = base.getRelative(BlockFace.UP);
                        Block support = base.getRelative(BlockFace.DOWN);
                        if (!base.isEmpty() || !head.isEmpty() || base.isLiquid() || head.isLiquid()) continue;
                        if (!support.getType().isSolid() || unsafeSupport(support.getType())) continue;
                        return base.getLocation();
                    }
                }
            }
        }
        return null;
    }

    private boolean unsafeSupport(Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE || material == Material.FIRE
                || material == Material.SOUL_FIRE || material == Material.LAVA;
    }

    private BlockFace facing(float yaw) {
        int quadrant = Math.floorMod(Math.round(yaw / 90.0f), 4);
        return switch (quadrant) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private String format(Location location) {
        return location.getWorld().getName() + " (" + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }
}
