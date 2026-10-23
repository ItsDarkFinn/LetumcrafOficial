package mx.vicko.permadeath.odyssey;

import java.util.Random;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Controla la fauna exclusiva de La Falla: Phantoms de Empuje, Shulkers
 * Fantasma y Endermans Erráticos. Los spawns naturales se cancelan en
 * {@link OdysseyListener}; este controlador solo crea las variantes con tag.
 */
public final class OdysseySpawnController {
    private static final int SPAWN_PERIOD_TICKS = 100;
    private static final int ENDERMAN_PERIOD_TICKS = 40;
    private static final int MAX_PER_TYPE_PER_PLAYER = 2;
    private static final double COUNT_RADIUS = 48.0;
    private static final double ENDERMAN_TELEPORT_DISTANCE = 5.0;
    private static final Set<Material> ISLAND_MATERIALS = Set.of(
            Material.OBSIDIAN,
            Material.GLASS,
            Material.WHITE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.COMMAND_BLOCK);

    private final JavaPlugin plugin;
    private final OdysseyManager odyssey;
    private final Random random = new Random();
    private BukkitTask spawnTask;
    private BukkitTask endermanTask;

    public OdysseySpawnController(JavaPlugin plugin, OdysseyManager odyssey) {
        this.plugin = plugin;
        this.odyssey = odyssey;
    }

    public void start() {
        spawnTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::spawnTick, SPAWN_PERIOD_TICKS, SPAWN_PERIOD_TICKS);
        endermanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::endermanTick, ENDERMAN_PERIOD_TICKS, ENDERMAN_PERIOD_TICKS);
    }

    public void stop() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (endermanTask != null) {
            endermanTask.cancel();
            endermanTask = null;
        }
    }

    private void spawnTick() {
        if (!odyssey.isReady()) {
            return;
        }
        World world = odyssey.world();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || random.nextDouble() > 0.75) {
                continue;
            }
            switch (random.nextInt(3)) {
                case 0 -> spawnPhantom(world, player);
                case 1 -> spawnShulker(world, player);
                default -> spawnEnderman(world, player);
            }
        }
    }

    private void spawnPhantom(World world, Player player) {
        if (countNear(world, player, Phantom.class, OdysseyTags.PHANTOM) >= MAX_PER_TYPE_PER_PLAYER) {
            return;
        }
        Location location = player.getLocation().clone().add(
                random.nextInt(13) - 6,
                9 + random.nextInt(5),
                random.nextInt(13) - 6);
        location.setY(Math.max(world.getMinHeight() + 4,
                Math.min(world.getMaxHeight() - 4, location.getY())));
        spawn(world, location, Phantom.class, phantom -> {
            phantom.setSize(3);
            phantom.setShouldBurnInDay(false);
            phantom.addScoreboardTag(OdysseyTags.PHANTOM);
            phantom.customName(Component.text("Phantom de Empuje"));
            phantom.setCustomNameVisible(false);
            phantom.setPersistent(false);
        });
    }

    private void spawnShulker(World world, Player player) {
        if (countNear(world, player, Shulker.class, OdysseyTags.SHULKER) >= MAX_PER_TYPE_PER_PLAYER) {
            return;
        }
        Location anchor = findShulkerAnchor(world, player);
        if (anchor == null) {
            return;
        }
        spawn(world, anchor, Shulker.class, shulker -> {
            try {
                // UP = pegada al bloque que tiene encima; el cuerpo queda
                // colgando debajo de la isla.
                shulker.setAttachedFace(BlockFace.UP);
            } catch (IllegalArgumentException ignored) {
            }
            shulker.setInvisible(true);
            shulker.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION,
                    0, true, false, false));
            shulker.addScoreboardTag(OdysseyTags.SHULKER);
            shulker.customName(Component.text("Shulker Fantasma"));
            shulker.setCustomNameVisible(false);
            shulker.setPersistent(false);
        });
    }

    private void spawnEnderman(World world, Player player) {
        if (countNear(world, player, Enderman.class, OdysseyTags.ENDERMAN) >= MAX_PER_TYPE_PER_PLAYER) {
            return;
        }
        Location location = findGroundNear(world, player);
        if (location == null) {
            return;
        }
        spawn(world, location, Enderman.class, enderman -> {
            enderman.addScoreboardTag(OdysseyTags.ENDERMAN);
            enderman.customName(Component.text("Enderman Errante"));
            enderman.setCustomNameVisible(false);
            enderman.setPersistent(false);
        });
    }

    private void endermanTick() {
        if (!odyssey.isReady()) {
            return;
        }
        World world = odyssey.world();
        if (world == null) {
            return;
        }
        for (Enderman enderman : world.getEntitiesByClass(Enderman.class)) {
            if (!enderman.getScoreboardTags().contains(OdysseyTags.ENDERMAN)
                    || enderman.isDead()) {
                continue;
            }
            Player target = nearestPlayer(world, enderman.getLocation());
            if (target == null) {
                continue;
            }

            Location destination = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double x = target.getX() + Math.cos(angle) * ENDERMAN_TELEPORT_DISTANCE;
                double z = target.getZ() + Math.sin(angle) * ENDERMAN_TELEPORT_DISTANCE;
                double y = target.getY() + random.nextInt(3) - 1.0;
                destination = new Location(world, x, y, z,
                        enderman.getLocation().getYaw(), enderman.getLocation().getPitch());
                if (isTeleportDestinationSafe(destination)) {
                    break;
                }
            }
            if (destination != null) {
                enderman.teleport(destination);
            }
        }
    }

    private Player nearestPlayer(World world, Location location) {
        Player nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= 64.0 * 64.0 && distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private Location findShulkerAnchor(World world, Player player) {
        int baseX = player.getLocation().getBlockX();
        int baseZ = player.getLocation().getBlockZ();
        int startY = Math.min(world.getMaxHeight() - 2, player.getLocation().getBlockY() + 12);
        // Solo se busca una isla cercana: evita recorrer toda la columna de
        // vacío cuando el jugador está lejos de cualquier plataforma.
        int minY = Math.max(world.getMinHeight() + 2, player.getLocation().getBlockY() - 48);

        for (int attempt = 0; attempt < 10; attempt++) {
            int x = baseX + random.nextInt(17) - 8;
            int z = baseZ + random.nextInt(17) - 8;
            for (int y = startY; y >= minY; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (!ISLAND_MATERIALS.contains(block.getType())) {
                    continue;
                }
                if (world.getBlockAt(x, y - 1, z).isPassable()
                        && world.getBlockAt(x, y - 2, z).isPassable()) {
                    return new Location(world, x + 0.5, y - 1.0, z + 0.5);
                }
            }
        }
        return null;
    }

    private Location findGroundNear(World world, Player player) {
        int baseY = player.getLocation().getBlockY();
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = player.getLocation().getBlockX() + random.nextInt(17) - 8;
            int z = player.getLocation().getBlockZ() + random.nextInt(17) - 8;
            for (int dy = 1; dy >= -4; dy--) {
                Location candidate = new Location(world, x + 0.5, baseY + dy, z + 0.5);
                Block feet = candidate.getBlock();
                Block head = candidate.clone().add(0.0, 1.0, 0.0).getBlock();
                Block below = candidate.clone().subtract(0.0, 1.0, 0.0).getBlock();
                if (feet.isPassable() && head.isPassable()
                        && !below.isPassable() && !below.getType().isAir()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private int countNear(World world, Player player, Class<? extends Entity> type, String tag) {
        return world.getNearbyEntities(player.getLocation(), COUNT_RADIUS, COUNT_RADIUS, COUNT_RADIUS,
                        entity -> type.isInstance(entity) && entity.getScoreboardTags().contains(tag))
                .size();
    }

    private boolean isTeleportDestinationSafe(Location location) {
        return location.getBlock().isPassable()
                && location.clone().add(0.0, 1.0, 0.0).getBlock().isPassable();
    }

    private <T extends org.bukkit.entity.LivingEntity> void spawn(World world, Location location,
                                                                  Class<T> type,
                                                                  java.util.function.Consumer<? super T> consumer) {
        try {
            world.spawn(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM, false, consumer);
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("No se pudo generar " + type.getSimpleName()
                    + " en La Falla: " + exception.getMessage());
        }
    }
}
