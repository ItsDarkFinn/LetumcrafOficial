package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** Evento ambiental localizado del Nether: ceniza, oscuridad breve y oleadas voladoras. */
public final class NetherAshstormEvent {
    private static final int START_DAY = 20;
    private static final int FINAL_DAY = 110;
    private static final int DEFAULT_DURATION_SECONDS = 300;
    private static final int DEFAULT_WAVE_INTERVAL_SECONDS = 30;
    private static final double DEFAULT_RADIUS = 80.0;
    private static final int DEFAULT_MAX_ACTIVE_MOBS = 20;

    private final PermadeathPlugin plugin;
    private BukkitTask schedulerTask;
    private BukkitTask stormTask;
    private ActiveStorm activeStorm;
    private int lastAttemptDay = -1;

    public NetherAshstormEvent(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAutomaticStart, 20L, 1200L);
    }

    public void shutdown() {
        if (schedulerTask != null) { schedulerTask.cancel(); schedulerTask = null; }
        if (activeStorm != null) finish(false);
    }

    /** Bloque 4 — tras el sellado no hay Tormenta de ceniza (ni auto ni en curso). */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String status() {
        if (activeStorm == null) {
            return "No hay Tormenta de ceniza activa. Día " + plugin.days().getCurrentDay() + ".";
        }
        long seconds = Math.max(0L, (activeStorm.endsAtMillis - System.currentTimeMillis() + 999L) / 1000L);
        return "Tormenta de ceniza " + (activeStorm.manual ? "(prueba) " : "") + "activa en "
                + activeStorm.world.getName() + "; quedan " + seconds + " segundos.";
    }

    public String startManual(Player player) {
        if (player.getWorld().getEnvironment() != World.Environment.NETHER) {
            return "Debes estar en el Nether para iniciar la prueba de la tormenta.";
        }
        if (activeStorm != null) return "Ya hay una Tormenta de ceniza activa.";
        begin(player.getWorld(), player.getLocation(), true);
        return "Tormenta de ceniza de prueba iniciada durante " + durationSeconds() + " segundos.";
    }

    public String cancel() {
        if (activeStorm == null) return "No hay Tormenta de ceniza activa.";
        finish(true);
        return "Tormenta de ceniza cancelada.";
    }

    private void checkAutomaticStart() {
        if (seasonEnded()) return;
        if (activeStorm != null || !plugin.getConfig().getBoolean("random-events.enabled", true)
                || !plugin.getConfig().getBoolean("random-events.nether.ashstorm.enabled", true)) return;
        int day = plugin.days().getCurrentDay();
        int startDay = Math.max(1, plugin.getConfig().getInt("random-events.nether.start-day", START_DAY));
        if (plugin.seasonService() != null) startDay = plugin.seasonService().mapLegacyDay(startDay);
        if (day < startDay || day > FINAL_DAY || lastAttemptDay == day) return;
        List<Player> candidates = eligibleNetherPlayers();
        if (candidates.isEmpty()) return;
        // Gate único de temporada (automático); /pd evento ceniza iniciar lo omite (prueba manual).
        try {
            SeasonService seasons = plugin.seasonService();
            if (seasons != null && !seasons.passesGateForPlayers(candidates)) {
                lastAttemptDay = day;
                return;
            }
        } catch (RuntimeException ignored) {
        }

        int lastEventDay = plugin.getConfig().getInt("random-events.nether.last-event-day", 0);
        int cooldown = Math.max(1, plugin.getConfig().getInt("random-events.nether.cooldown-days", 3));
        if (lastEventDay > 0 && day - lastEventDay < cooldown) return;
        lastAttemptDay = day;
        double chance = probability("random-events.nether.ashstorm.daily-chance", 0.15);
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;

        Player anchor = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        plugin.getConfig().set("random-events.nether.last-event-day", day);
        plugin.saveConfig();
        begin(anchor.getWorld(), anchor.getLocation(), false);
    }

    private void begin(World world, Location center, boolean manual) {
        activeStorm = new ActiveStorm(world, center, System.currentTimeMillis() + durationSeconds() * 1000L,
                manual, waveIntervalSeconds());
        String message = "§6§lLETUMCRAFT §7— §8Una Tormenta de ceniza cubre el Nether."
                + " §7¡Prepárense para los blazes y ghasts!";
        for (Player player : affectedPlayers()) {
            player.sendMessage(message);
            player.sendTitle("§8Tormenta de ceniza", "§7La visibilidad cae en el Nether", 10, 50, 10);
        }
        spawnWave();
        stormTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickStorm, 1L, 20L);
    }

    private void tickStorm() {
        if (activeStorm == null) return;
        // Bloque 4 — tormenta en curso al sellar: se disipa sin más daño.
        if (seasonEnded()) { finish(false); return; }
        if (System.currentTimeMillis() >= activeStorm.endsAtMillis) {
            finish(true);
            return;
        }
        activeStorm.elapsedSeconds++;
        for (Player player : affectedPlayers()) {
            Location at = player.getLocation().add(0, 2.5, 0);
            activeStorm.world.spawnParticle(Particle.ASH, at, 20, 5.0, 2.5, 5.0, 0.01);
            if (activeStorm.elapsedSeconds % darknessIntervalSeconds() == 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                        darknessDurationSeconds() * 20, 0, true, false, false));
            }
        }
        if (activeStorm.elapsedSeconds >= activeStorm.nextWaveAtSeconds) {
            spawnWave();
            activeStorm.nextWaveAtSeconds += waveIntervalSeconds();
        }
    }

    private void spawnWave() {
        if (activeStorm == null) return;
        List<Player> players = affectedPlayers();
        if (players.isEmpty()) return;
        pruneMobs();
        int maxActive = maxActiveMobs();
        int available = Math.max(0, maxActive - activeStorm.mobIds.size());
        if (available == 0) return;

        Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        int blazes = Math.min(available, Math.max(1,
                plugin.getConfig().getInt("random-events.nether.ashstorm.blazes-per-wave", 2)));
        for (int i = 0; i < blazes; i++) spawnHostile(EntityType.BLAZE, target, false);
        int ghasts = Math.max(0, plugin.getConfig().getInt("random-events.nether.ashstorm.ghasts-per-wave", 1));
        for (int i = 0; i < ghasts && activeStorm.mobIds.size() < maxActive; i++) {
            spawnHostile(EntityType.GHAST, target, true);
        }
        for (Player player : players) player.sendMessage("§8La ceniza se arremolina… nuevas criaturas se acercan.");
    }

    private void spawnHostile(EntityType type, Player target, boolean airborne) {
        if (activeStorm == null || activeStorm.mobIds.size() >= maxActiveMobs()) return;
        Location spawn = airborne ? findAirSpawn(target) : findGroundSpawn(target);
        if (spawn == null) return;
        Entity entity = activeStorm.world.spawnEntity(spawn, type);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            return;
        }
        activeStorm.mobIds.add(living.getUniqueId());
        if (living instanceof Mob mob) mob.setTarget(target);
    }

    private Location findGroundSpawn(Player target) {
        World world = target.getWorld();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            int radius = 18 + ThreadLocalRandom.current().nextInt(11);
            int x = target.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = target.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            int targetY = target.getLocation().getBlockY();
            int lowestY = Math.max(world.getMinHeight() + 1, targetY - 12);
            int highestY = Math.min(world.getMaxHeight() - 3, targetY + 4);
            for (int y = highestY; y >= lowestY; y--) {
                var ground = world.getBlockAt(x, y, z);
                var feet = ground.getRelative(0, 1, 0);
                var head = ground.getRelative(0, 2, 0);
                if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isEmpty() || !head.isEmpty()) continue;
                return feet.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    private Location findAirSpawn(Player target) {
        World world = target.getWorld();
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            int radius = 30 + ThreadLocalRandom.current().nextInt(16);
            int x = target.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = target.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = Math.min(world.getMaxHeight() - 4,
                    target.getLocation().getBlockY() + 8 + ThreadLocalRandom.current().nextInt(13));
            if (y < world.getMinHeight() + 4) continue;
            boolean clear = true;
            for (int dx = -2; dx <= 2 && clear; dx++) {
                for (int dy = -2; dy <= 2 && clear; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (!world.getBlockAt(x + dx, y + dy, z + dz).isEmpty()) {
                            clear = false;
                            break;
                        }
                    }
                }
            }
            if (clear) return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void pruneMobs() {
        activeStorm.mobIds.removeIf(id -> {
            Entity entity = activeStorm.world.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
    }

    private void finish(boolean announce) {
        ActiveStorm storm = activeStorm;
        if (storm == null) return;
        activeStorm = null;
        if (stormTask != null) stormTask.cancel();
        for (UUID id : storm.mobIds) {
            Entity entity = storm.world.getEntity(id);
            if (entity != null && entity.isValid()) entity.remove();
        }
        if (announce) {
            for (Player player : affectedPlayers(storm)) {
                player.sendMessage("§7La tormenta de ceniza se disipa. Las criaturas restantes se desvanecen.");
                player.sendTitle("§7La tormenta terminó", "§fEl Nether vuelve a calmarse", 5, 40, 10);
            }
            Bukkit.broadcastMessage("§6§lLETUMCRAFT §7— La Tormenta de ceniza terminó.");
        }
    }

    private List<Player> affectedPlayers() {
        return activeStorm == null ? List.of() : affectedPlayers(activeStorm);
    }

    private List<Player> affectedPlayers(ActiveStorm storm) {
        double radius = Math.max(16.0, Math.min(256.0,
                plugin.getConfig().getDouble("random-events.nether.ashstorm.effect-radius", DEFAULT_RADIUS)));
        double radiusSquared = radius * radius;
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(storm.world) && isAlive(player)
                    && player.getLocation().distanceSquared(storm.center) <= radiusSquared) result.add(player);
        }
        return result;
    }

    private List<Player> eligibleNetherPlayers() {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isAlive(player) && player.getWorld().getEnvironment() == World.Environment.NETHER) result.add(player);
        }
        return result;
    }

    private boolean isAlive(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.deaths().isDead(player.getUniqueId());
    }

    private int durationSeconds() {
        return Math.max(60, Math.min(900,
                plugin.getConfig().getInt("random-events.nether.ashstorm.duration-seconds", DEFAULT_DURATION_SECONDS)));
    }

    private int waveIntervalSeconds() {
        return Math.max(20, Math.min(180,
                plugin.getConfig().getInt("random-events.nether.ashstorm.wave-interval-seconds", DEFAULT_WAVE_INTERVAL_SECONDS)));
    }

    private int darknessIntervalSeconds() {
        return Math.max(10, Math.min(120,
                plugin.getConfig().getInt("random-events.nether.ashstorm.darkness-interval-seconds", 15)));
    }

    private int darknessDurationSeconds() {
        return Math.max(1, Math.min(14,
                plugin.getConfig().getInt("random-events.nether.ashstorm.darkness-duration-seconds", 10)));
    }

    private int maxActiveMobs() {
        return Math.max(2, Math.min(16,
                plugin.getConfig().getInt("random-events.nether.ashstorm.max-active-mobs", DEFAULT_MAX_ACTIVE_MOBS)));
    }

    private double probability(String path, double fallback) {
        return Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble(path, fallback)));
    }

    private static final class ActiveStorm {
        private final World world;
        private final Location center;
        private final long endsAtMillis;
        private final boolean manual;
        private final List<UUID> mobIds = new ArrayList<>();
        private int elapsedSeconds;
        private int nextWaveAtSeconds;

        private ActiveStorm(World world, Location center, long endsAtMillis, boolean manual, int firstWaveDelaySeconds) {
            this.world = world;
            this.center = center.clone();
            this.endsAtMillis = endsAtMillis;
            this.manual = manual;
            this.nextWaveAtSeconds = firstWaveDelaySeconds;
        }
    }
}
