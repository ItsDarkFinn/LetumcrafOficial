package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Trabajo periódico deliberadamente espaciado: no escanea chunks sin jugadores. */
public final class PhaseTasks {
    private final PermadeathPlugin plugin;
    private final Map<UUID, LockedSlot> lockedSlots = new HashMap<>();
    private final Map<UUID, Long> batAttackCooldowns = new HashMap<>();
    private final BossBar phaseBar;
    private long seconds;
    private int lastObservedDay;

    public PhaseTasks(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.phaseBar = Bukkit.createBossBar("LetumcrafOficial", BarColor.RED, BarStyle.SOLID);
        this.lastObservedDay = plugin.days().getCurrentDay();
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        seconds++;
        int day = plugin.days().getCurrentDay();
        if (day != lastObservedDay) {
            announceDay(day);
            lastObservedDay = day;
        }
        updatePhaseBar(day);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            applyPlayerPhases(player);
            if (plugin.isPhaseActive(20)) attackNearbyBats(player);
        }
        if (seconds % 5 == 0) for (World world : plugin.getServer().getWorlds()) applyWorldPhases(world);
        if (plugin.isPhaseActive(100) && seconds % 600 == 0) lockHotbarSlots();
    }

    private void announceDay(int day) {
        String message;
        for (int phase : List.of(10, 20, 25, 35, 40, 50, 60, 70, 80, 90, 100)) {
            if (day == phase) {
                message = "§c§l¡DÍA " + phase + "! §r§f" + phaseName(phase);
                Bukkit.broadcastMessage("§8[§cLetumcrafOficial§8] " + message);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendTitle("§c§lDÍA " + phase, "§f" + phaseName(phase), 10, 80, 20);
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, .55f, 1.2f);
                }
                return;
            }
        }
        Bukkit.broadcastMessage("§8[§cLetumcrafOficial§8] §fComienza el día real §e" + day + "§f.");
    }

    private void updatePhaseBar(int day) {
        int current = 0;
        int next = 101;
        String title = "Vanilla";
        for (int phase : List.of(10, 20, 25, 35, 40, 50, 60, 70, 80, 90, 100)) {
            if (day >= phase && plugin.isPhaseActive(phase)) { current = phase; title = phaseName(phase); }
            else if (day < phase && plugin.getConfig().getBoolean("phases." + phase + ".enabled", true)) { next = phase; break; }
        }
        double progress = next == 101 ? 1.0 : Math.max(0, Math.min(1, (double) (day - current) / (next - current)));
        phaseBar.setProgress(progress);
        phaseBar.setTitle("§cLetumcraft §8• §fDía " + day + " §8• §e" + title
                + (plugin.days().isPaused() ? " §8(§cPAUSADO§8)" : ""));
        for (Player player : Bukkit.getOnlinePlayers()) phaseBar.addPlayer(player);
        for (Player player : new ArrayList<>(phaseBar.getPlayers())) if (!player.isOnline()) phaseBar.removePlayer(player);
    }

    private String phaseName(int phase) {
        return switch (phase) {
            case 10 -> "Modo UHC";
            case 20 -> "Insomnio, asedios, Pale Warden y murciélagos";
            case 25 -> "Peligros subterráneos";
            case 35 -> "El End y el Dragón";
            case 40 -> "Invasión del Overworld";
            case 50 -> "Clima y agua hostiles";
            case 60 -> "Mobs y monturas peligrosas";
            case 70 -> "Supervivencia extrema";
            case 80 -> "Cazadores y emboscadas";
            case 90 -> "Hambruna global";
            case 100 -> "La última fase";
            default -> "Vanilla";
        };
    }

    private void applyPlayerPhases(Player player) {
        if (player.isDead()) return;
        World world = player.getWorld();
        Block feet = player.getLocation().getBlock();

        if (plugin.isPhaseActive(10)) scareAnimals(player);
        if (plugin.isPhaseActive(20)) {
            if (seconds % 20 == 0 && chance(0.22)) playFalseSound(player);
            if (seconds % 8 == 0) extinguishNearbyTorch(player);
        }
        if (plugin.isPhaseActive(35) && world.getEnvironment() == World.Environment.THE_END) {
            if (player.getLocation().getY() > 80) player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false, false));
            angerEndermenWhenStaringAtDragon(player);
        }
        if (plugin.isPhaseActive(40) && isDesertOrSavanna(feet) && feet.getType() == Material.WATER) player.damage(1.0);
        if (plugin.isPhaseActive(50)) applyClimatePhase(player, feet);
        if (plugin.isPhaseActive(60)) applyWitchHell(player);
        if (plugin.isPhaseActive(70)) applyLateGameStats(player);
        if (plugin.isPhaseActive(80)) makeHostilesHunt(player);
        if (plugin.isPhaseActive(90)) applyFaminePhase(player, feet);
        if (plugin.isPhaseActive(100)) applyDay100(player);
        restoreExpiredLock(player);
    }

    private void applyWorldPhases(World world) {
        if (plugin.isPhaseActive(20)) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Monster monster) destroyTorchBelow(monster);
                if (entity instanceof Zombie zombie && seconds % 2 == 0) breachBaseBlocks(zombie);
            }
        }
        if (plugin.isPhaseActive(25)) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof FallingBlock falling && (falling.getBlockData().getMaterial() == Material.SAND || falling.getBlockData().getMaterial() == Material.GRAVEL)) {
                    Vector velocity = falling.getVelocity();
                    if (velocity.getY() < 0) falling.setVelocity(new Vector(velocity.getX(), velocity.getY() * 2, velocity.getZ()));
                }
            }
        }
        if (plugin.isPhaseActive(35) && world.getEnvironment() == World.Environment.THE_END) {
            hardenEndCrystals(world);
            if (seconds % 8 == 0) dragonAttack(world);
        }
        if (plugin.isPhaseActive(40) && world.getEnvironment() == World.Environment.NORMAL) {
            world.setStorm(true);
            world.setWeatherDuration(20 * 60);
            if (seconds % 90 == 0) spawnSurfaceInvader(world);
        }
        if (plugin.isPhaseActive(100) && world.getEnvironment() == World.Environment.NORMAL) {
            world.setStorm(true);
            world.setThundering(true);
            world.setWeatherDuration(20 * 60);
            world.setThunderDuration(20 * 60);
        }
    }

    private void scareAnimals(Player player) {
        for (Entity entity : player.getNearbyEntities(8, 4, 8)) {
            if (!(entity instanceof Animals animal) || animal instanceof Monster) continue;
            Vector away = animal.getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(.65);
            away.setY(Math.max(.1, away.getY()));
            animal.setVelocity(away);
        }
    }

    private void playFalseSound(Player player) {
        Sound[] sounds = {Sound.BLOCK_STONE_BREAK, Sound.ENTITY_CREEPER_PRIMED, Sound.ENTITY_ZOMBIE_STEP};
        Vector offset = new Vector(random(-7, 7), random(-1, 2), random(-7, 7));
        player.playSound(player.getLocation().add(offset), sounds[ThreadLocalRandom.current().nextInt(sounds.length)], 1.0f, 1.0f);
    }

    private void extinguishNearbyTorch(Player player) {
        List<Block> candidates = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) {
            Block block = player.getLocation().getBlock().getRelative(x, y, z);
            if (block.getType() == Material.TORCH || block.getType() == Material.WALL_TORCH) candidates.add(block);
        }
        if (!candidates.isEmpty() && chance(.10)) candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).setType(Material.AIR);
    }

    private void angerEndermenWhenStaringAtDragon(Player player) {
        for (Entity entity : player.getWorld().getEntities()) {
            if (!(entity instanceof EnderDragon dragon) || dragon.getLocation().distanceSquared(player.getLocation()) > 2500) continue;
            Vector sight = player.getEyeLocation().getDirection().normalize();
            Vector toward = dragon.getLocation().add(0, 5, 0).toVector().subtract(player.getEyeLocation().toVector()).normalize();
            if (sight.dot(toward) > .96) for (Entity nearby : player.getNearbyEntities(30, 30, 30)) if (nearby instanceof Enderman enderman) enderman.setTarget(player);
        }
    }

    private void applyClimatePhase(Player player, Block feet) {
        World world = player.getWorld();
        boolean exposed = world.getHighestBlockYAt(player.getLocation()) <= player.getLocation().getBlockY();
        if (world.hasStorm() && exposed) player.damage(.5);
        if (feet.getType() == Material.WATER) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 3));
        }
        if (player.isInWater()) player.setRemainingAir(Math.max(0, player.getRemainingAir() - 45));
        if (isSnowy(feet) && exposed) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
            player.damage(.5);
        }
    }

    private void applyWitchHell(Player player) {
        for (Entity nearby : player.getNearbyEntities(12, 6, 12)) {
            if (nearby instanceof Witch && chance(.12)) {
                player.damage(3.0);
                Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
                if (below.getRelative(0, 1, 0).getType().isAir()) below.getRelative(0, 1, 0).setType(Material.FIRE);
            }
        }
    }

    private void applyLateGameStats(Player player) {
        if (player.getAttribute(Attribute.MAX_HEALTH) != null && player.getAttribute(Attribute.MAX_HEALTH).getBaseValue() != 14.0) {
            player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(14.0);
            player.setHealth(Math.min(player.getHealth(), 14.0));
        }
        if (player.isSprinting() && seconds % 2 == 0) player.setFoodLevel(Math.max(0, player.getFoodLevel() - 2));
        boolean full = true;
        for (ItemStack stack : player.getInventory().getStorageContents()) if (stack == null || stack.getType().isAir()) { full = false; break; }
        if (full) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0, false, false, false));
    }

    private void makeHostilesHunt(Player player) {
        for (Entity nearby : player.getNearbyEntities(50, 32, 50)) {
            if (nearby instanceof Enderman enderman) enderman.setTarget(player);
            if (nearby instanceof Skeleton skeleton && nearby.getLocation().distanceSquared(player.getLocation()) <= 2500) skeleton.setTarget(player);
            if (nearby instanceof Creeper creeper) creeper.setSilent(true);
        }
    }

    private void applyFaminePhase(Player player, Block feet) {
        if (player.getLocation().getY() < 0) player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60, 1, false, false, false));
        for (Entity nearby : player.getNearbyEntities(2, 2, 2)) if (nearby instanceof Animals && chance(.14)) player.damage(2.0);
        if (seconds % 20 == 0 && chance(.004)) {
            Block below = feet.getRelative(0, -1, 0);
            if (below.getType().isSolid() && below.getType() != Material.BEDROCK) below.setType(chance(.5) ? Material.MAGMA_BLOCK : Material.NETHERRACK);
        }
    }

    private void applyDay100(Player player) {
        if (seconds % 120 == 0) {
            player.getWorld().strikeLightning(player.getLocation());
            if (chance(.35)) player.getWorld().spawnEntity(player.getLocation().add(random(12, 24), 0, random(12, 24)), EntityType.WITHER);
        }
        if (seconds % 300 == 0) {
            int minY = player.getWorld().getMinHeight();
            Block bedrock = player.getWorld().getBlockAt(player.getLocation().getBlockX(), minY, player.getLocation().getBlockZ());
            if (bedrock.getType() == Material.BEDROCK) bedrock.setType(Material.AIR);
        }
        for (Player other : player.getWorld().getPlayers()) if (!other.equals(player) && other.getLocation().distanceSquared(player.getLocation()) < 100) player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, false, false));
    }

    private void lockHotbarSlots() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (lockedSlots.containsKey(player.getUniqueId())) continue;
            int slot = ThreadLocalRandom.current().nextInt(9);
            lockedSlots.put(player.getUniqueId(), new LockedSlot(slot, player.getInventory().getItem(slot), seconds + 180));
            player.getInventory().setItem(slot, new ItemStack(Material.BEDROCK));
            player.sendMessage("§8La soledad bloqueó un espacio de tu barra rápida.");
        }
    }

    private void restoreExpiredLock(Player player) {
        LockedSlot lock = lockedSlots.get(player.getUniqueId());
        if (lock == null || seconds < lock.until) return;
        if (player.getInventory().getItem(lock.slot) != null && player.getInventory().getItem(lock.slot).getType() == Material.BEDROCK) player.getInventory().setItem(lock.slot, lock.original);
        lockedSlots.remove(player.getUniqueId());
    }

    private void hardenEndCrystals(World world) {
        for (Entity entity : world.getEntities()) if (entity instanceof EnderCrystal crystal) {
            Block base = crystal.getLocation().getBlock();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 3; y++) {
                if (Math.abs(x) == 1 || Math.abs(z) == 1) { Block bar = base.getRelative(x, y, z); if (bar.getType().isAir()) bar.setType(Material.IRON_BARS); }
            }
        }
    }

    private void dragonAttack(World world) {
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof EnderDragon dragon)) continue;
            Player target = nearestPlayer(dragon, 96);
            if (target == null) continue;
            Fireball fireball = (Fireball) world.spawnEntity(dragon.getLocation().add(0, 5, 0), EntityType.FIREBALL);
            fireball.setShooter(dragon);
            fireball.setYield(3.0F);
            fireball.setVelocity(target.getEyeLocation().toVector().subtract(fireball.getLocation().toVector()).normalize().multiply(1.25));
            var egg = world.dropItem(target.getLocation(), new ItemStack(Material.DRAGON_EGG));
            egg.setPickupDelay(200);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (egg.isValid()) egg.remove();
                for (int i = 0; i < 6; i++) world.spawnEntity(target.getLocation().add(random(-2, 2), 0, random(-2, 2)), EntityType.ENDERMITE);
            }, 100L);
        }
    }

    private void destroyTorchBelow(Monster mob) {
        Block feet = mob.getLocation().getBlock();
        for (Block block : List.of(feet, feet.getRelative(0, -1, 0))) {
            if (block.getType() == Material.TORCH || block.getType() == Material.WALL_TORCH) block.setType(Material.AIR);
        }
    }

    private void breachBaseBlocks(Zombie zombie) {
        Block center = zombie.getLocation().getBlock();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 1; y++) {
            Block block = center.getRelative(x, y, z);
            String name = block.getType().name();
            if (name.endsWith("DOOR") || name.contains("GLASS")) block.breakNaturally();
        }
    }

    private void spawnSurfaceInvader(World world) {
        if (world.getTime() < 13000 || world.getTime() > 23000 || world.getPlayers().isEmpty()) return;
        Player target = world.getPlayers().get(ThreadLocalRandom.current().nextInt(world.getPlayers().size()));
        EntityType type = chance(.5) ? EntityType.GHAST : EntityType.PIGLIN_BRUTE;
        int x = target.getLocation().getBlockX() + random(18, 35);
        int z = target.getLocation().getBlockZ() + random(18, 35);
        int y = world.getHighestBlockYAt(x, z) + 3;
        world.spawnEntity(new org.bukkit.Location(world, x, y, z), type);
    }

    private void attackNearbyBats(Player player) {
        if (seconds < batAttackCooldowns.getOrDefault(player.getUniqueId(), 0L)) return;
        for (Entity nearby : player.getNearbyEntities(1.25, 1.25, 1.25)) {
            if (!(nearby instanceof Bat bat)) continue;
            DamageSource source = DamageSource.builder(DamageType.MAGIC)
                    .withCausingEntity(bat)
                    .withDirectEntity(bat)
                    .build();
            player.damage(4.0, source); // 2 corazones, ignora armadura
            batAttackCooldowns.put(player.getUniqueId(), seconds + 2);
            return;
        }
    }

    private Player nearestPlayer(LivingEntity entity, double range) {
        Player closest = null;
        double distance = range * range;
        for (Player player : entity.getWorld().getPlayers()) {
            double candidate = player.getLocation().distanceSquared(entity.getLocation());
            if (!player.isDead() && candidate < distance) { closest = player; distance = candidate; }
        }
        return closest;
    }

    private boolean isDesertOrSavanna(Block block) { String biome = block.getBiome().name(); return biome.contains("DESERT") || biome.contains("SAVANNA"); }
    private boolean isSnowy(Block block) { String biome = block.getBiome().name(); return biome.contains("SNOW") || biome.contains("FROZEN") || biome.contains("ICE") || biome.contains("GROVE") || biome.contains("JAGGED") || biome.contains("PEAKS"); }
    private boolean chance(double probability) { return ThreadLocalRandom.current().nextDouble() < probability; }
    private int random(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }

    private record LockedSlot(int slot, ItemStack original, long until) { }
}
