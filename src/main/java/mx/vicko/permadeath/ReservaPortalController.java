package mx.vicko.permadeath;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.scheduler.BukkitTask;

/** Creates and manages the physical, day-gated entrance to the Reserve. */
public final class ReservaPortalController implements Listener {
    private static final String ROOT = "reserve.portal";
    private static final int DOOR_X = 2;
    private static final double TRIGGER_DEPTH = 0.72;

    private final PermadeathPlugin plugin;
    private final ReservaPodrida reserve;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> pendingTeleports = new HashSet<>();
    private final BukkitTask setupTask;

    public ReservaPortalController(PermadeathPlugin plugin, ReservaPodrida reserve) {
        this.plugin = plugin;
        this.reserve = reserve;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        setupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::ensureEntrance, 40L, 200L);
    }

    public void shutdown() {
        setupTask.cancel();
        cooldowns.clear();
        pendingTeleports.clear();
    }

    public void ensureReserveExit(World world) {
        if (!plugin.getConfig().getBoolean(ROOT + ".exit-generated", false)) {
            int x = 0;
            int z = 7;
            int y = ReservaPodrida.getReserveFloorHeight(x, z);
            buildEntrance(world, x, y, z);
            plugin.getConfig().set(ROOT + ".exit-x", x);
            plugin.getConfig().set(ROOT + ".exit-y", y);
            plugin.getConfig().set(ROOT + ".exit-z", z);
            plugin.getConfig().set(ROOT + ".exit-generated", true);
            plugin.saveConfig();
        }
        setPortalSurface(world, plugin.getConfig().getInt(ROOT + ".exit-x"),
                plugin.getConfig().getInt(ROOT + ".exit-y"), plugin.getConfig().getInt(ROOT + ".exit-z"));
    }

    private void ensureEntrance() {
        if (!plugin.getConfig().getBoolean(ROOT + ".enabled", true)) return;
        int unlockDay = plugin.getConfig().getInt("reserve.unlock-day", ReservaPodrida.UNLOCK_DAY);
        int effectiveUnlock = plugin.seasonService() != null ? plugin.seasonService().effectiveUnlockDay(unlockDay) : unlockDay;
        boolean unlocked = plugin.days().getCurrentDay() >= effectiveUnlock;
        String worldName = plugin.getConfig().getString(ROOT + ".world");
        World world = worldName == null ? Bukkit.getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null) : Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("No se encontró el mundo normal para construir la entrada de la Reserva.");
            return;
        }
        if (!plugin.getConfig().getBoolean(ROOT + ".generated", false)) {
            if (!unlocked) return;
            Location site = findSite(world);
            if (site == null) {
                plugin.getLogger().warning("No se encontró un espacio natural y plano cerca del spawn para la ruina de la Reserva. No se modificó el mundo.");
                return;
            }
            plugin.getConfig().set(ROOT + ".world", world.getName());
            plugin.getConfig().set(ROOT + ".x", site.getBlockX());
            plugin.getConfig().set(ROOT + ".y", site.getBlockY());
            plugin.getConfig().set(ROOT + ".z", site.getBlockZ());
            buildEntrance(world, site.getBlockX(), site.getBlockY(), site.getBlockZ());
            plugin.getConfig().set(ROOT + ".generated", true);
            plugin.getConfig().set(ROOT + ".visible", true);
            plugin.getConfig().set(ROOT + ".structure-repaired", true);
            plugin.saveConfig();
            plugin.getLogger().info("Ruina de entrada a la Reserva revelada en el día " + unlockDay + ".");
            announceEntrance(world, site.getBlockX(), site.getBlockY(), site.getBlockZ());
            return;
        }

        int x = plugin.getConfig().getInt(ROOT + ".x");
        int y = plugin.getConfig().getInt(ROOT + ".y");
        int z = plugin.getConfig().getInt(ROOT + ".z");
        boolean visible = plugin.getConfig().getBoolean(ROOT + ".visible", true);
        if (!unlocked) {
            if (visible) {
                hideEntrance(world, x, y, z);
                plugin.getConfig().set(ROOT + ".visible", false);
                plugin.getConfig().set(ROOT + ".structure-repaired", false);
                plugin.getConfig().set(ROOT + ".announced", false);
                plugin.saveConfig();
                plugin.getLogger().info("La ruina de la Reserva quedó oculta hasta el día " + unlockDay + ".");
            }
            return;
        }
        if (!visible) {
            buildEntrance(world, x, y, z);
            plugin.getConfig().set(ROOT + ".visible", true);
            plugin.getConfig().set(ROOT + ".structure-repaired", true);
            plugin.saveConfig();
            plugin.getLogger().info("La ruina de entrada a la Reserva apareció al desbloquearse el día " + unlockDay + ".");
            announceEntrance(world, x, y, z);
        } else if (!plugin.getConfig().getBoolean(ROOT + ".structure-repaired", false)) {
            repairEntrance(world, x, y, z);
            plugin.getConfig().set(ROOT + ".structure-repaired", true);
            plugin.saveConfig();
        }
    }

    /**
     * Announces a (re)appeared entrance exactly once per unlock cycle: chat + title,
     * only after the structure was actually built. The flag resets when the ruin hides.
     */
    private void announceEntrance(World world, int x, int y, int z) {
        if (plugin.getConfig().getBoolean(ROOT + ".announced", false)) return;
        plugin.getConfig().set(ROOT + ".announced", true);
        plugin.saveConfig();
        String chat = color("&2Una ruina antigua ha emergido (" + x + ", " + y + ", " + z
                + "). &aLa Reserva Podrida aguarda.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(chat);
            player.sendTitle(color("&2La Reserva Podrida"), color("&7Una entrada antigua ha aparecido"), 10, 70, 20);
        }
    }

    private void repairEntrance(World world, int x, int y, int z) {
        Material[] lintel = {Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                Material.CRACKED_STONE_BRICKS, Material.MUD_BRICKS};
        for (int dx = -1; dx <= 2; dx++) set(world, x + dx, y + 5, z, lintel[Math.floorMod(dx + 1, lintel.length)]);
        setPortalSurface(world, x, y, z);
    }

    private void hideEntrance(World world, int x, int y, int z) {
        int sampleY = findSurfaceY(world, x + 8, z);
        Material ground = sampleY == Integer.MIN_VALUE ? world.getBlockAt(x, y, z).getType()
                : world.getBlockAt(x + 8, sampleY, z).getType();
        if (!isNaturalGround(ground)) ground = Material.SAND;
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -2; dz <= 5; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (!isRuinMaterial(block.getType())) continue;
                    block.setType(dy == 0 ? ground : Material.AIR, false);
                }
            }
        }
    }

    private boolean isRuinMaterial(Material material) {
        return material == Material.NETHER_PORTAL || material == Material.MOSSY_STONE_BRICKS
                || material == Material.CRACKED_STONE_BRICKS || material == Material.STONE_BRICKS
                || material == Material.MUD_BRICKS;
    }

    private Location findSite(World world) {
        Location spawn = world.getSpawnLocation();
        int radius = Math.max(32, plugin.getConfig().getInt(ROOT + ".search-radius-blocks", 128));
        for (int distance = 24; distance <= radius; distance += 8) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * Math.PI / 8.0;
                int x = spawn.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
                int z = spawn.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
                int y = findSurfaceY(world, x, z);
                if (y == Integer.MIN_VALUE) continue;
                if (isSafeSite(world, x, y, z)) return new Location(world, x, y, z);
            }
        }
        return null;
    }

    private boolean isSafeSite(World world, int centerX, int centerY, int centerZ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = centerX - 3; x <= centerX + 4; x++) {
            for (int z = centerZ - 3; z <= centerZ + 3; z++) {
                int y = findSurfaceY(world, x, z);
                if (y == Integer.MIN_VALUE) return false;
                if (Math.abs(y - centerY) > 2) return false;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                Material ground = world.getBlockAt(x, y, z).getType();
                if (!isNaturalGround(ground)) return false;
                for (int clearY = y + 1; clearY <= y + 6; clearY++) {
                    Material type = world.getBlockAt(x, clearY, z).getType();
                    if (!type.isAir() && type != Material.SHORT_GRASS && type != Material.TALL_GRASS
                            && type != Material.FERN && type != Material.LARGE_FERN && type != Material.DEAD_BUSH) return false;
                }
            }
        }
        return maxY - minY <= 2;
    }

    private int findSurfaceY(World world, int x, int z) {
        int top = world.getHighestBlockYAt(x, z);
        for (int y = top; y >= top - 4; y--) {
            if (isNaturalGround(world.getBlockAt(x, y, z).getType())) return y;
        }
        return Integer.MIN_VALUE;
    }

    private boolean isNaturalGround(Material type) {
        return switch (type) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM,
                    SAND, RED_SAND, STONE, ANDESITE, GRANITE, DIORITE, TUFF -> true;
            default -> false;
        };
    }

    private void buildEntrance(World world, int x, int y, int z) {
        Material[] ruin = {Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.STONE_BRICKS, Material.MUD_BRICKS};
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) > 1 || Math.abs(dz) > 1) {
                    if ((dx * 13 + dz * 7) % 3 == 0) set(world, x + dx, y, z + dz, ruin[Math.floorMod(dx * 3 + dz, ruin.length)]);
                }
            }
        }
        // Two weathered side pillars and a broken lintel frame an open doorway.
        for (int dy = 1; dy <= 4; dy++) {
            set(world, x - 1, y + dy, z, ruin[Math.floorMod(dy, ruin.length)]);
            set(world, x + 2, y + dy, z, ruin[Math.floorMod(dy + 1, ruin.length)]);
            if (dy == 1 || dy == 3 || dy == 4) {
                set(world, x - 2, y + dy, z, ruin[Math.floorMod(dy + 2, ruin.length)]);
                set(world, x + 3, y + dy, z, ruin[Math.floorMod(dy + 3, ruin.length)]);
            }
        }
        for (int dx = -1; dx <= 2; dx++) set(world, x + dx, y + 5, z, ruin[Math.floorMod(dx + 5, ruin.length)]);
        // A vanilla portal block provides the familiar animated purple surface; movement is still routed by this plugin.
        setPortalSurface(world, x, y, z);
        // A short, broken approach helps players discover the opening without a waypoint.
        for (int step = 1; step <= 5; step++) {
            if (step % 2 == 1) set(world, x, y, z + step, step % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
        }
    }

    private void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
    }

    private void setPortalSurface(World world, int x, int y, int z) {
        for (int dx = 0; dx < DOOR_X; dx++) {
            for (int dy = 1; dy <= 3; dy++) set(world, x + dx, y + dy, z, Material.NETHER_PORTAL);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRuinBlockBreak(BlockBreakEvent event) {
        if (isProtectedRuinBlock(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&7Las ruinas antiguas no se pueden destruir."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedRuinBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedRuinBlock);
    }

    private boolean isProtectedRuinBlock(Block block) {
        Material material = block.getType();
        if (material != Material.NETHER_PORTAL && material != Material.MOSSY_STONE_BRICKS
                && material != Material.CRACKED_STONE_BRICKS && material != Material.STONE_BRICKS
                && material != Material.MUD_BRICKS) return false;
        Location location = block.getLocation();
        if (plugin.getConfig().getBoolean(ROOT + ".generated", false)
                && plugin.getConfig().getBoolean(ROOT + ".visible", true)) {
            World entranceWorld = Bukkit.getWorld(plugin.getConfig().getString(ROOT + ".world", ""));
            if (insideRuinBounds(location, entranceWorld, plugin.getConfig().getInt(ROOT + ".x"),
                    plugin.getConfig().getInt(ROOT + ".y"), plugin.getConfig().getInt(ROOT + ".z"))) return true;
        }
        if (plugin.getConfig().getBoolean(ROOT + ".exit-generated", false)) {
            World reserveWorld = Bukkit.getWorld(ReservaPodrida.WORLD_NAME);
            return insideRuinBounds(location, reserveWorld, plugin.getConfig().getInt(ROOT + ".exit-x"),
                    plugin.getConfig().getInt(ROOT + ".exit-y"), plugin.getConfig().getInt(ROOT + ".exit-z"));
        }
        return false;
    }

    private boolean insideRuinBounds(Location location, World world, int x, int y, int z) {
        return world != null && location.getWorld() == world
                && location.getBlockX() >= x - 3 && location.getBlockX() <= x + 4
                && location.getBlockY() >= y && location.getBlockY() <= y + 5
                && location.getBlockZ() >= z - 2 && location.getBlockZ() <= z + 5;
    }

    private boolean insideDoor(Location location, World expectedWorld, int x, int y, int z) {
        if (location.getWorld() != expectedWorld) return false;
        return location.getBlockX() >= x && location.getBlockX() < x + DOOR_X
                && location.getBlockY() >= y + 1 && location.getBlockY() <= y + 3
                && Math.abs(location.getZ() - (z + 0.5)) <= TRIGGER_DEPTH;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        // Same-block steps still cross the door plane: only ignore pure look-around.
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Player player = event.getPlayer();
        if (pendingTeleports.contains(player.getUniqueId()) || isOnCooldown(player)) return;
        if (reserve.isReserve(player.getWorld())) {
            // The generated world's return arch is placed beside its spawn on first entry.
            if (isReserveExit(to) && !isReserveExit(from)) {
                pendingTeleports.add(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        String error = reserve.exit(player);
                        if (error != null) {
                            player.sendMessage(color(error));
                            setCooldown(player, 500L);
                        } else {
                            player.sendMessage(color("&aSaliste de la Reserva Podrida."));
                            setCooldown(player, 2500L);
                        }
                    } finally {
                        pendingTeleports.remove(player.getUniqueId());
                    }
                });
            }
            return;
        }
        if (!plugin.getConfig().getBoolean(ROOT + ".generated", false)
                || !plugin.getConfig().getBoolean(ROOT + ".visible", true)) return;
        if (!plugin.getConfig().getBoolean("reserve.enabled", true)) return;
        World entranceWorld = Bukkit.getWorld(plugin.getConfig().getString(ROOT + ".world", ""));
        int x = plugin.getConfig().getInt(ROOT + ".x");
        int y = plugin.getConfig().getInt(ROOT + ".y");
        int z = plugin.getConfig().getInt(ROOT + ".z");
        if (!insideDoor(to, entranceWorld, x, y, z) || insideDoor(from, entranceWorld, x, y, z)) return;
        int unlockDay = plugin.getConfig().getInt("reserve.unlock-day", ReservaPodrida.UNLOCK_DAY);
        int effectiveUnlock = plugin.seasonService() != null ? plugin.seasonService().effectiveUnlockDay(unlockDay) : unlockDay;
        if (plugin.days().getCurrentDay() < effectiveUnlock && !player.hasPermission("permadeath.admin")) {
            player.sendMessage(color("&cLa entrada permanece sellada. Se desbloquea el día " + effectiveUnlock + "."));
            setCooldown(player, 2500L);
            return;
        }
        Location returnPoint = new Location(entranceWorld, x + 0.5, y + 1.0, z + 3.5, 180.0f, 0.0f);
        pendingTeleports.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (player.isOnline()) {
                    String error = reserve.enter(player, returnPoint);
                    if (error != null) {
                        player.sendMessage(color(error));
                        setCooldown(player, 500L);
                    } else setCooldown(player, 2500L);
                }
            } finally {
                pendingTeleports.remove(player.getUniqueId());
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onVanillaPortal(PlayerPortalEvent event) {
        // Cancel vanilla derivation for either custom end before foreign transforms or damage apply.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (isCustomPortal(from) || to != null && isCustomPortal(to)) event.setCancelled(true);
    }

    /**
     * Pure classifier for custom Reserve portals (overworld entrance volume and Reserve
     * exit volume). No side effects: safe to call from other listeners (DifficultyListener
     * resolves it by reflection for its Nether/End guards).
     */
    public boolean isCustomPortal(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (reserve.isReserve(location.getWorld())) return isReserveExit(location);
        if (!plugin.getConfig().getBoolean(ROOT + ".generated", false)
                || !plugin.getConfig().getBoolean(ROOT + ".visible", true)) return false;
        World world = Bukkit.getWorld(plugin.getConfig().getString(ROOT + ".world", ""));
        return insideDoor(location, world, plugin.getConfig().getInt(ROOT + ".x"),
                plugin.getConfig().getInt(ROOT + ".y"), plugin.getConfig().getInt(ROOT + ".z"));
    }

    private boolean isCustomDoor(Location location) {
        return isCustomPortal(location);
    }

    private boolean isReserveExit(Location location) {
        if (location == null || !reserve.isReserve(location.getWorld())) return false;
        if (!plugin.getConfig().getBoolean("reserve.portal.exit-generated", false)) return false;
        int x = plugin.getConfig().getInt("reserve.portal.exit-x");
        int y = plugin.getConfig().getInt("reserve.portal.exit-y");
        int z = plugin.getConfig().getInt("reserve.portal.exit-z");
        return location.getBlockX() >= x && location.getBlockX() < x + 2
                && location.getBlockY() >= y + 1 && location.getBlockY() <= y + 3
                && Math.abs(location.getZ() - (z + 0.5)) <= TRIGGER_DEPTH;
    }

    private boolean isOnCooldown(Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void setCooldown(Player player, long millis) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + millis);
    }

    private String color(String input) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', input);
    }
}
