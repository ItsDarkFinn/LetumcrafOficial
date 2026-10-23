package mx.vicko.permadeath.odyssey;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Zonas invisibles de gravedad rota. Se marcan constantemente con partículas
 * moradas, aplican Levitación mientras el jugador está dentro y la retiran en
 * cuanto cruza el límite superior.
 */
public final class OdysseyGravityController implements Listener {
    private static final int TICK_PERIOD = 2;
    private static final int PARTICLE_PERIOD = 10;
    private static final int MAX_ZONES = 20;
    private static final long ZONE_LIFETIME_MS = 90_000L;
    private static final double ZONE_NEAR_PLAYER = 256.0;

    private final JavaPlugin plugin;
    private final OdysseyManager odyssey;
    private final Random random = new Random();
    private final Map<UUID, Zone> zones = new ConcurrentHashMap<>();
    private final Set<UUID> affected = ConcurrentHashMap.newKeySet();
    private BukkitTask tickTask;
    private BukkitTask particleTask;

    public OdysseyGravityController(JavaPlugin plugin, OdysseyManager odyssey) {
        this.plugin = plugin;
        this.odyssey = odyssey;
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_PERIOD);
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drawParticles, 1L, PARTICLE_PERIOD);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        for (UUID playerId : Set.copyOf(affected)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.LEVITATION);
            }
        }
        zones.clear();
        affected.clear();
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        affected.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.LEVITATION);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !odyssey.isOdyssey(event.getTo().getWorld())) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        if (!affected.contains(playerId)) {
            return;
        }
        if (findZone(event.getTo()) == null) {
            clear(event.getPlayer());
        }
    }

    private void tick() {
        if (!odyssey.isReady()) {
            return;
        }
        World world = odyssey.world();
        if (world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        zones.values().removeIf(zone -> now - zone.createdAt() > ZONE_LIFETIME_MS
                || !hasPlayerNear(world, zone));

        for (Player player : world.getPlayers()) {
            if (zones.size() >= MAX_ZONES) {
                break;
            }
            if (random.nextDouble() < 0.04) {
                addZoneNear(player, now);
            }
        }

        Set<UUID> inside = new HashSet<>();
        for (Player player : world.getPlayers()) {
            if (findZone(player.getLocation()) == null) {
                continue;
            }
            inside.add(player.getUniqueId());
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.LEVITATION, 40, 1, true, false, false));
        }

        for (UUID playerId : Set.copyOf(affected)) {
            if (inside.contains(playerId)) {
                continue;
            }
            affected.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.LEVITATION);
            }
        }
        affected.addAll(inside);
    }

    private void drawParticles() {
        if (!odyssey.isReady()) {
            return;
        }
        World world = odyssey.world();
        if (world == null) {
            return;
        }
        for (Zone zone : zones.values()) {
            for (int i = 0; i < 3; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = Math.sqrt(random.nextDouble()) * zone.radius();
                double y = zone.minY() + random.nextDouble() * Math.max(1, zone.maxY() - zone.minY());
                world.spawnParticle(Particle.PORTAL,
                        zone.x() + Math.cos(angle) * radius,
                        y,
                        zone.z() + Math.sin(angle) * radius,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            for (int i = 0; i < 2; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                world.spawnParticle(Particle.DRAGON_BREATH,
                        zone.x() + Math.cos(angle) * zone.radius(),
                        zone.maxY(),
                        zone.z() + Math.sin(angle) * zone.radius(),
                        1, 0.0, 0.0, 0.0, 0.0, 0.0f);
            }
        }
    }

    private void addZoneNear(Player player, long now) {
        World world = player.getWorld();
        if (world == null || !odyssey.isOdyssey(world)) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = 16.0 + random.nextDouble() * 24.0;
        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;

        int minY = (int) Math.round(player.getY() - 12.0 + random.nextInt(24));
        minY = Math.max(world.getMinHeight() + 12, Math.min(world.getMaxHeight() - 40, minY));
        int maxY = Math.min(world.getMaxHeight() - 8, minY + 18 + random.nextInt(18));
        double radius = 4.5 + random.nextDouble() * 4.5;

        UUID id = UUID.randomUUID();
        zones.put(id, new Zone(id, x, z, minY, maxY, radius, now));
    }

    private Zone findZone(Location location) {
        if (location == null) {
            return null;
        }
        for (Zone zone : zones.values()) {
            if (zone.contains(location)) {
                return zone;
            }
        }
        return null;
    }

    private boolean hasPlayerNear(World world, Zone zone) {
        for (Player player : world.getPlayers()) {
            double dx = player.getX() - zone.x();
            double dz = player.getZ() - zone.z();
            if (dx * dx + dz * dz <= ZONE_NEAR_PLAYER * ZONE_NEAR_PLAYER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zona cilíndrica de gravedad. Es un record puro para poder probar su
     * geometría sin levantar un servidor.
     */
    public record Zone(UUID id, double x, double z, int minY, int maxY, double radius, long createdAt) {
        public boolean contains(Location location) {
            if (location == null) {
                return false;
            }
            double dx = location.getX() - x;
            double dz = location.getZ() - z;
            return location.getY() >= minY
                    && location.getY() <= maxY
                    && dx * dx + dz * dz <= radius * radius;
        }
    }
}
