package mx.vicko.permadeath;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Gives Pale Garden Creakings the Pale Warden's custom attacks while retaining their native model. */
public final class PaleWardenController implements Listener {
    private static final double GAZE_RANGE = 20.0;
    private static final double GAZE_DOT = 0.94;
    private static final int ROOT_COOLDOWN_TICKS = 240;
    private static final int PULSE_COOLDOWN_TICKS = 420;

    private final PermadeathPlugin plugin;
    private final org.bukkit.NamespacedKey markerKey;
    private final org.bukkit.NamespacedKey heartWorldKey;
    private final org.bukkit.NamespacedKey heartXKey;
    private final org.bukkit.NamespacedKey heartYKey;
    private final org.bukkit.NamespacedKey heartZKey;
    private final Map<UUID, WardenState> states = new HashMap<>();
    private final Set<UUID> paleCreakings = new HashSet<>();
    private long ticks;

    public PaleWardenController(PermadeathPlugin plugin) {
        this.plugin = plugin;
        markerKey = new org.bukkit.NamespacedKey(plugin, "pale_warden");
        heartWorldKey = new org.bukkit.NamespacedKey(plugin, "pale_warden_heart_world");
        heartXKey = new org.bukkit.NamespacedKey(plugin, "pale_warden_heart_x");
        heartYKey = new org.bukkit.NamespacedKey(plugin, "pale_warden_heart_y");
        heartZKey = new org.bukkit.NamespacedKey(plugin, "pale_warden_heart_z");
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                // Remove Pale Wardens created by 0.2.2; the Creaking Heart can spawn its Creaking again.
                if (entity instanceof Warden warden && isMarked(warden)) {
                    warden.remove();
                } else if (entity instanceof LivingEntity living && isPaleCreaking(living)) {
                    paleCreakings.add(entity.getUniqueId());
                }
            }
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && isPaleCreaking(living)) {
                paleCreakings.add(entity.getUniqueId());
            }
            // A legacy Pale Warden may have been unloaded when the plugin was upgraded.
            if (entity instanceof Warden warden && isMarked(warden)) warden.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreakingSpawn(CreatureSpawnEvent event) {
        if (!plugin.isPhaseActive(20)
                || !plugin.getConfig().getBoolean("phases.20.pale-warden.enabled", true)
                || event.getEntityType() != EntityType.CREAKING
                || event.getLocation().getWorld().getEnvironment() != World.Environment.NORMAL
                || event.getLocation().getBlock().getBiome() != Biome.PALE_GARDEN) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.DEFAULT
                && reason != CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        long time = event.getLocation().getWorld().getTime();
        if (time < 13000 || time > 23000) return;

        Location heart = findNearestHeart(event.getLocation(), 8);
        if (heart == null) return;
        LivingEntity creaking = event.getEntity();
        if (hasLivingCreakingForHeart(heart)) {
            // Prevent a second heart-linked entity from receiving duplicate custom attacks.
            event.setCancelled(true);
            return;
        }

        // Keep the actual Creaking: vanilla already freezes its movement while a player watches it.
        creaking.setCustomName(ChatColor.GRAY + "Pale Warden");
        creaking.setCustomNameVisible(true);
        if (creaking instanceof Mob mob) mob.setRemoveWhenFarAway(false);
        creaking.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        writeHeartLocation(creaking.getPersistentDataContainer(), heart);
        paleCreakings.add(creaking.getUniqueId());
        states.put(creaking.getUniqueId(), new WardenState(ticks + ROOT_COOLDOWN_TICKS, ticks + PULSE_COOLDOWN_TICKS));
        creaking.getWorld().playSound(creaking.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.7f);
        creaking.getWorld().spawnParticle(Particle.CLOUD, creaking.getLocation().add(0, 1, 0), 18, .45, .7, .45, .02);
    }

    private void tick() {
        ticks += 2;
        if (!plugin.isPhaseActive(20)
                || !plugin.getConfig().getBoolean("phases.20.pale-warden.enabled", true)) {
            removeTrackedCreakings();
            return;
        }
        for (Iterator<UUID> iterator = paleCreakings.iterator(); iterator.hasNext();) {
            UUID id = iterator.next();
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob creaking) || !creaking.isValid() || creaking.getType() != EntityType.CREAKING) {
                states.remove(id);
                iterator.remove();
                continue;
            }
            Location heart = readHeartLocation(creaking.getPersistentDataContainer());
            if (heart == null || heart.getBlock().getType() != Material.CREAKING_HEART) {
                creaking.remove();
                states.remove(id);
                iterator.remove();
                continue;
            }

            Player target = nearestPlayer(creaking, 24);
            if (target == null) continue;
            WardenState state = states.computeIfAbsent(id, ignored -> new WardenState(ticks + 80, ticks + 160));
            if (ticks >= state.nextRootTick) {
                state.nextRootTick = ticks + ROOT_COOLDOWN_TICKS;
                rootSnare(creaking, target);
            }
            if (ticks >= state.nextPulseTick) {
                state.nextPulseTick = ticks + PULSE_COOLDOWN_TICKS;
                heartPulse(creaking);
            }
        }
    }

    private void removeTrackedCreakings() {
        for (UUID id : paleCreakings) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) living.remove();
            states.remove(id);
        }
        paleCreakings.clear();
    }

    private void rootSnare(Mob creaking, Player target) {
        Location marked = target.getLocation().getBlock().getLocation().add(.5, .15, .5);
        World world = target.getWorld();
        world.playSound(marked, Sound.BLOCK_ROOTED_DIRT_BREAK, .8f, .75f);
        world.spawnParticle(Particle.CRIT, marked.clone().add(0, .6, 0), 16, .45, .1, .45, .02);
        target.sendMessage(ChatColor.GRAY + "Las raíces pálidas se agitan bajo tus pies...");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!target.isOnline() || !target.getWorld().equals(world) || !creaking.isValid()) return;
            if (target.getLocation().distanceSquared(marked) <= 4.0) {
                target.damage(2.0, creaking);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, true, true));
                world.spawnParticle(Particle.CRIT, marked.clone().add(0, .8, 0), 24, .6, .45, .6, .04);
            }
        }, 30L);
    }

    private void heartPulse(Mob creaking) {
        Location center = creaking.getLocation().add(0, 1, 0);
        World world = creaking.getWorld();
        world.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, .55f);
        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0, 0, 0, 0);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= 64) {
                player.sendMessage(ChatColor.DARK_GRAY + "El corazón pálido empieza a resonar...");
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!creaking.isValid()) return;
            Location origin = creaking.getLocation().add(0, 1, 0);
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(origin) > 64 || !creaking.hasLineOfSight(player)) continue;
                Vector knockback = player.getLocation().toVector().subtract(origin.toVector()).normalize().multiply(.65);
                knockback.setY(.22);
                player.setVelocity(knockback);
                player.damage(4.0, creaking);
            }
            world.spawnParticle(Particle.SONIC_BOOM, origin, 1, 0, 0, 0, 0);
            world.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, .7f, 1.25f);
        }, 35L);
    }

    private Player nearestPlayer(Mob creaking, double range) {
        Player nearest = null;
        double best = range * range;
        for (Player player : creaking.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(creaking.getLocation());
            if (!player.isDead() && distance < best) {
                nearest = player;
                best = distance;
            }
        }
        return nearest;
    }

    private Location findNearestHeart(Location origin, int radius) {
        Block center = origin.getBlock();
        Location nearest = null;
        double best = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            Block block = center.getRelative(x, y, z);
            if (block.getType() != Material.CREAKING_HEART) continue;
            double distance = block.getLocation().distanceSquared(origin);
            if (distance < best) {
                best = distance;
                nearest = block.getLocation();
            }
        }
        return nearest;
    }

    private boolean hasLivingCreakingForHeart(Location heart) {
        for (Entity entity : heart.getWorld().getNearbyEntities(heart, 16, 16, 16)) {
            if (entity instanceof LivingEntity living && isPaleCreaking(living)
                    && sameHeart(heart, readHeartLocation(living.getPersistentDataContainer()))) return true;
        }
        return false;
    }

    private boolean isMarked(LivingEntity entity) {
        Byte marker = entity.getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isPaleCreaking(LivingEntity entity) {
        return entity.getType() == EntityType.CREAKING && isMarked(entity);
    }

    private void writeHeartLocation(PersistentDataContainer data, Location heart) {
        data.set(heartWorldKey, PersistentDataType.STRING, heart.getWorld().getUID().toString());
        data.set(heartXKey, PersistentDataType.INTEGER, heart.getBlockX());
        data.set(heartYKey, PersistentDataType.INTEGER, heart.getBlockY());
        data.set(heartZKey, PersistentDataType.INTEGER, heart.getBlockZ());
    }

    private Location readHeartLocation(PersistentDataContainer data) {
        String worldId = data.get(heartWorldKey, PersistentDataType.STRING);
        Integer x = data.get(heartXKey, PersistentDataType.INTEGER);
        Integer y = data.get(heartYKey, PersistentDataType.INTEGER);
        Integer z = data.get(heartZKey, PersistentDataType.INTEGER);
        if (worldId == null || x == null || y == null || z == null) return null;
        World world = Bukkit.getWorld(UUID.fromString(worldId));
        return world == null ? null : new Location(world, x, y, z);
    }

    private boolean sameHeart(Location first, Location second) {
        return second != null && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private static final class WardenState {
        private long nextRootTick;
        private long nextPulseTick;

        private WardenState(long nextRootTick, long nextPulseTick) {
            this.nextRootTick = nextRootTick;
            this.nextPulseTick = nextPulseTick;
        }
    }
}
