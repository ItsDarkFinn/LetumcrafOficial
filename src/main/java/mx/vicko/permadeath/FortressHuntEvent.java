package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Daily Nether expedition at a nearby fortress, with three guard waves and a locked reward chest. */
public final class FortressHuntEvent implements Listener {
    static final int TOTAL_WAVES = 3;
    static final double TITLE_RADIUS_BLOCKS = 20.0;
    static final long WAVE_DELAY_TICKS = 40L;

    /** Explicit hunt state: the chest stays locked in COMBAT and WAITING, and opens only in REWARD. */
    enum HuntPhase { IDLE, COMBAT, WAITING, REWARD }

    /**
     * Pure session logic (no Bukkit calls) so wave/wait/completion transitions are unit-testable
     * without a server. Production code drives one instance per hunt; tests drive their own.
     */
    static final class HuntSession {
        private final long sessionId;
        private int wave;
        private int guardsRemaining;
        private HuntPhase phase = HuntPhase.COMBAT;
        private boolean rewardUnlocked;

        HuntSession(long sessionId) { this.sessionId = sessionId; }

        long sessionId() { return sessionId; }
        int wave() { return wave; }
        HuntPhase phase() { return phase; }
        boolean rewardUnlocked() { return rewardUnlocked; }

        void onWaveSpawned(int waveNumber, int guardCount) {
            wave = waveNumber;
            guardsRemaining = Math.max(0, guardCount);
            if (guardsRemaining == 0) {
                if (waveNumber >= TOTAL_WAVES) { phase = HuntPhase.REWARD; rewardUnlocked = true; }
                else phase = HuntPhase.WAITING;
            } else { phase = HuntPhase.COMBAT; rewardUnlocked = false; }
        }

        enum GuardDeathOutcome { STILL_FIGHTING, WAIT_NEXT_WAVE, REWARD_READY }

        GuardDeathOutcome onGuardDeath() {
            if (phase != HuntPhase.COMBAT || guardsRemaining <= 0) return GuardDeathOutcome.STILL_FIGHTING;
            guardsRemaining--;
            if (guardsRemaining > 0) return GuardDeathOutcome.STILL_FIGHTING;
            if (wave >= TOTAL_WAVES) { phase = HuntPhase.REWARD; rewardUnlocked = true; return GuardDeathOutcome.REWARD_READY; }
            phase = HuntPhase.WAITING;
            return GuardDeathOutcome.WAIT_NEXT_WAVE;
        }

        void abort() { phase = HuntPhase.IDLE; guardsRemaining = 0; rewardUnlocked = false; }

        void markRewarded() { phase = HuntPhase.REWARD; rewardUnlocked = true; guardsRemaining = 0; }

        boolean isChestLocked() { return phase == HuntPhase.COMBAT || phase == HuntPhase.WAITING; }

        boolean acceptsWaveTask(long token) { return token == sessionId && phase == HuntPhase.WAITING; }
    }

    static boolean shouldSendWaveTitle(boolean sameWorld, double distanceSquared) {
        return sameWorld && distanceSquared <= TITLE_RADIUS_BLOCKS * TITLE_RADIUS_BLOCKS;
    }

    static String waveTitle(int wave) { return "Oleada " + wave + "/" + TOTAL_WAVES; }

    private final PermadeathPlugin plugin;
    private final Set<UUID> guards = new HashSet<>();
    private BukkitTask scheduler;
    private BukkitTask cleanupTask;
    private BukkitTask waveTask;
    private HuntSession session;
    private long sessionSeq;
    private Location chestLocation;
    private int wave;
    private boolean manual;

    public FortressHuntEvent(PermadeathPlugin plugin) { this.plugin = plugin; }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduler = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1200L);
    }

    public void shutdown() {
        if (scheduler != null) { scheduler.cancel(); scheduler = null; }
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (waveTask != null) { waveTask.cancel(); waveTask = null; }
        sessionSeq++;
        session = null;
        cleanupGuards();
    }

    public String status() {
        return "Cacería de Fortaleza " + (chestLocation == null ? "inactiva" : "activa (oleada " + wave + "/3)")
                + "; día " + plugin.days().getCurrentDay() + "; última aparición día "
                + plugin.getConfig().getInt("random-events.nether.fortress-hunt.last-day", 0) + ".";
    }

    public String startManual(Player player) {
        if (player.getWorld().getEnvironment() != World.Environment.NETHER) return "Debes estar en el Nether.";
        if (chestLocation != null) return "Ya hay una Cacería de Fortaleza activa.";
        return startNear(player, true);
    }

    public String cancel() {
        if (chestLocation == null) return "No hay una Cacería de Fortaleza activa.";
        finish();
        return "Cacería de Fortaleza cancelada; los guardias restantes fueron retirados.";
    }

    /** Bloque 4 — tras el sellado no hay Cacería de Fortaleza automática. */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void tick() {
        if (seasonEnded()) return;
        if (chestLocation != null || !plugin.getConfig().getBoolean("random-events.enabled", true)
                || !plugin.getConfig().getBoolean("random-events.nether.fortress-hunt.enabled", true)) return;
        int day = plugin.days().getCurrentDay();
        int startDay = Math.max(1, plugin.getConfig().getInt("random-events.nether.fortress-hunt.start-day", 10));
        if (plugin.seasonService() != null) startDay = plugin.seasonService().mapLegacyDay(startDay);
        if (day < startDay || plugin.getConfig().getInt("random-events.nether.fortress-hunt.last-day", 0) >= day) return;
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == World.Environment.NETHER && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && !plugin.deaths().isDead(player.getUniqueId())) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) return;
        // Gate único de temporada (automático); /pd evento fortaleza iniciar lo omite (prueba manual).
        try {
            SeasonService seasons = plugin.seasonService();
            if (seasons != null && !seasons.passesGateForPlayers(candidates)) return;
        } catch (RuntimeException ignored) {
        }
        Player anchor = candidates.get(0);
        if (startNear(anchor, false).startsWith("Cacería de Fortaleza iniciada")) {
            plugin.getConfig().set("random-events.nether.fortress-hunt.last-day", day);
            plugin.saveConfig();
        }
    }

    private String startNear(Player player, boolean test) {
        World world = player.getWorld();
        // Structure search returns a reference point, which may not overlap the fortress blocks.
        // If a player is already at a fortress, anchor to the actual Nether bricks nearby.
        Location fortress = findNearbyFortressBlock(player.getLocation());
        if (fortress == null) fortress = world.locateNearestStructure(player.getLocation(), StructureType.NETHER_FORTRESS, 128, false);
        if (fortress == null) return "No encontré una fortaleza del Nether cercana. Acércate a los ladrillos de la estructura y prueba otra vez.";
        Location chest = findChestSite(fortress);
        if (chest == null) return "Sí encontré la fortaleza, pero no hay un espacio seguro para colocar el cofre cerca.";
        Block block = chest.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest state)) return "No pude colocar el cofre de la Cacería.";
        state.getPersistentDataContainer().set(plugin.fortressChestKey(), PersistentDataType.BYTE, (byte) 1);
        if (!state.update(true, false)) return "No pude preparar el cofre de la Cacería.";
        if (block.getState() instanceof Chest live) fillLoot(live.getBlockInventory());
        chestLocation = chest;
        if (waveTask != null) { waveTask.cancel(); waveTask = null; }
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        sessionSeq++;
        session = new HuntSession(sessionSeq);
        wave = 0;
        manual = test;
        Bukkit.broadcastMessage("§6§lLETUMCRAFT §7— §c¡Una Cacería de Fortaleza comenzó en el Nether! §7Sigan las señales; no se revelan coordenadas.");
        Bukkit.broadcastMessage("§7Tres oleadas protegen la cámara. Derroten a todos los guardias para abrir el cofre.");
        spawnNextWave();
        cleanupTask = Bukkit.getScheduler().runTaskLater(plugin, this::finish, 20L * 600L);
        return "Cacería de Fortaleza iniciada para prueba.";
    }

    private Location findNearbyFortressBlock(Location center) {
        World world = center.getWorld();
        if (world == null) return null;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int radius = 0; radius <= 20; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = cx + dx, z = cz + dz;
                    for (int dy = -12; dy <= 12; dy++) {
                        int y = cy + dy;
                        if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                        Material type = world.getBlockAt(x, y, z).getType();
                        if (type == Material.NETHER_BRICKS || type == Material.RED_NETHER_BRICKS
                                || type == Material.CRACKED_NETHER_BRICKS || type == Material.CHISELED_NETHER_BRICKS) {
                            return new Location(world, x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private Location findChestSite(Location origin) {
        World world = origin.getWorld();
        if (world == null) return null;
        int ox = origin.getBlockX(), oz = origin.getBlockZ();
        for (int radius = 1; radius <= 24; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int x = ox + dx, z = oz + dz;
                for (int y = Math.max(world.getMinHeight() + 1, origin.getBlockY() - 8); y <= Math.min(world.getMaxHeight() - 2, origin.getBlockY() + 8); y++) {
                    Block floor = world.getBlockAt(x, y - 1, z), spot = world.getBlockAt(x, y, z);
                    if (floor.getType().isSolid() && floor.getType() != Material.MAGMA_BLOCK
                            && spot.isEmpty() && !spot.isLiquid() && world.getBlockAt(x, y + 1, z).isEmpty()) return spot.getLocation();
                }
            }
        }
        return null;
    }

    private void spawnNextWave() {
        if (chestLocation == null || session == null) return;
        wave++;
        Bukkit.broadcastMessage("§c§lCacería §7— Oleada §e" + wave + "§7/3");
        int piglins = wave == 1 ? 4 : wave == 2 ? 2 : 0;
        int witherSkeletons = wave == 1 ? 0 : wave == 2 ? 3 : 2;
        for (int i = 0; i < piglins; i++) spawnGuard(EntityType.ZOMBIFIED_PIGLIN, false, i);
        if (wave == 3) spawnGuard(EntityType.PIGLIN_BRUTE, true, 0);
        for (int i = 0; i < witherSkeletons; i++) spawnGuard(EntityType.WITHER_SKELETON, false, i + piglins);
        session.onWaveSpawned(wave, guards.size());
        sendWaveTitles();
        if (session.phase() == HuntPhase.REWARD) { openChest(); return; }
        if (session.phase() == HuntPhase.WAITING) scheduleNextWave();
    }

    private void scheduleNextWave() {
        if (chestLocation == null || session == null) return;
        long token = session.sessionId();
        if (waveTask != null) waveTask.cancel();
        waveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> runPendingWave(token), WAVE_DELAY_TICKS);
    }

    private void runPendingWave(long token) {
        waveTask = null;
        if (chestLocation == null || session == null || !session.acceptsWaveTask(token)) return;
        // Bloque 4 — oleada pendiente al sellar: se cierra la sesión sin más daño.
        if (seasonEnded()) { finish(); return; }
        spawnNextWave();
    }

    private void sendWaveTitles() {
        if (chestLocation == null || chestLocation.getWorld() == null) return;
        String title = "§c§l" + waveTitle(wave);
        double maxDistSq = TITLE_RADIUS_BLOCKS * TITLE_RADIUS_BLOCKS;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(chestLocation.getWorld())) continue;
            if (player.getLocation().distanceSquared(chestLocation) > maxDistSq) continue;
            player.sendTitle(title, "", 10, 40, 10);
        }
    }

    boolean isChestLocked() {
        return chestLocation != null && session != null && session.isChestLocked();
    }

    private void spawnGuard(EntityType type, boolean captain, int index) {
        World world = chestLocation.getWorld();
        double angle = index * Math.PI * 2 / 6 + ThreadLocalRandom.current().nextDouble(-0.25, 0.25);
        int x = chestLocation.getBlockX() + (int) Math.round(Math.cos(angle) * (3 + index % 3));
        int z = chestLocation.getBlockZ() + (int) Math.round(Math.sin(angle) * (3 + index % 3));
        int y = chestLocation.getBlockY();
        while (y > world.getMinHeight() + 1 && world.getBlockAt(x, y - 1, z).isEmpty()) y--;
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        LivingEntity mob = (LivingEntity) world.spawnEntity(loc, type);
        mob.setPersistent(true);
        mob.setGlowing(true);
        mob.getPersistentDataContainer().set(plugin.fortressGuardKey(), PersistentDataType.BYTE, (byte) 1);
        if (captain) {
            mob.setCustomName("§cCapitán de la Fortaleza"); mob.setCustomNameVisible(true);
            mob.setMaxHealth(50); mob.setHealth(50);
        }
        if (mob instanceof Skeleton skeleton) {
            var eq = skeleton.getEquipment();
            if (eq != null) {
                eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET)); eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS)); eq.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
                zeroDrops(skeleton);
            }
        } else if (mob instanceof PigZombie pig) {
            var eq = pig.getEquipment();
            if (eq != null) { eq.setItemInMainHand(new ItemStack(captain ? Material.GOLDEN_AXE : Material.GOLDEN_SWORD)); zeroDrops(pig); }
        }
        guards.add(mob.getUniqueId());
    }

    @EventHandler public void onGuardDeath(EntityDeathEvent event) {
        if (!guards.remove(event.getEntity().getUniqueId()) || chestLocation == null || session == null) return;
        event.getDrops().clear();
        HuntSession.GuardDeathOutcome outcome = session.onGuardDeath();
        if (outcome == HuntSession.GuardDeathOutcome.WAIT_NEXT_WAVE) scheduleNextWave();
        else if (outcome == HuntSession.GuardDeathOutcome.REWARD_READY) openChest();
    }

    @EventHandler(ignoreCancelled = true) public void onChestOpen(InventoryOpenEvent event) {
        if (chestLocation == null || !(event.getPlayer() instanceof Player player) || !isFortressChestInventory(event.getInventory())) return;
        if (isChestLocked()) { event.setCancelled(true); player.sendMessage("§cLa cámara sigue sellada; derrota a todos los guardias."); }
    }

    @EventHandler(ignoreCancelled = true) public void onChestBreak(BlockBreakEvent event) {
        if (isChestLocked() && event.getBlock().getLocation().equals(chestLocation)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cLa cámara sigue sellada; derrota a todos los guardias.");
        }
    }

    @EventHandler(ignoreCancelled = true) public void onChestPlace(BlockPlaceEvent event) {
        if (!isChestLocked() || event.getBlockPlaced().getType() != Material.CHEST || chestLocation.getWorld() == null) return;
        Location placed = event.getBlockPlaced().getLocation();
        if (placed.getWorld() != null && placed.getWorld().equals(chestLocation.getWorld())
                && placed.getBlockY() == chestLocation.getBlockY()
                && Math.abs(placed.getBlockX() - chestLocation.getBlockX()) + Math.abs(placed.getBlockZ() - chestLocation.getBlockZ()) == 1) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cLa cámara sigue sellada; derrota a todos los guardias.");
        }
    }

    @EventHandler(ignoreCancelled = true) public void onChestBlockExplode(BlockExplodeEvent event) {
        if (isChestLocked()) event.blockList().removeIf(block -> block.getLocation().equals(chestLocation));
    }

    @EventHandler(ignoreCancelled = true) public void onChestEntityExplode(EntityExplodeEvent event) {
        if (isChestLocked()) event.blockList().removeIf(block -> block.getLocation().equals(chestLocation));
    }

    @EventHandler(ignoreCancelled = true) public void onChestHopperMove(InventoryMoveItemEvent event) {
        if (isChestLocked() && isFortressChestInventory(event.getSource())) event.setCancelled(true);
    }

    private boolean isFortressChestInventory(org.bukkit.inventory.Inventory inventory) {
        if (chestLocation == null) return false;
        var holder = inventory.getHolder();
        if (holder instanceof Chest chest) return isMarkedFortressChest(chest);
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest)
            return isMarkedFortressChest(doubleChest.getLeftSide()) || isMarkedFortressChest(doubleChest.getRightSide());
        return false;
    }

    private boolean isMarkedFortressChest(org.bukkit.inventory.InventoryHolder holder) {
        if (!(holder instanceof Chest chest) || !chest.getLocation().equals(chestLocation)) return false;
        return chest.getPersistentDataContainer().getOrDefault(plugin.fortressChestKey(), PersistentDataType.BYTE, (byte) 0) == 1;
    }

    private void fillLoot(org.bukkit.inventory.Inventory inventory) {
        inventory.clear();
        LootInventory.addRandomly(inventory, new ItemStack(Material.GOLD_INGOT, 6 + ThreadLocalRandom.current().nextInt(7)));
        LootInventory.addRandomly(inventory, new ItemStack(Material.QUARTZ, 12 + ThreadLocalRandom.current().nextInt(13)));
        LootInventory.addRandomly(inventory, new ItemStack(Material.COOKED_PORKCHOP, 6));
        ItemStack potions = new ItemStack(Material.POTION, 2);
        if (potions.getItemMeta() instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(PotionType.FIRE_RESISTANCE);
            potions.setItemMeta(potionMeta);
        }
        LootInventory.addRandomly(inventory, potions);
        if (ThreadLocalRandom.current().nextDouble() < probability("random-events.nether.fortress-hunt.core-chance", 0.10))
            LootInventory.addRandomly(inventory, plugin.eclipseCoreItem().createItem());
        if (ThreadLocalRandom.current().nextDouble() < probability("random-events.nether.fortress-hunt.netherite-chance", 0.10))
            LootInventory.addRandomly(inventory, new ItemStack(Material.NETHERITE_INGOT));
    }

    private void openChest() {
        if (chestLocation == null || session == null || wave < TOTAL_WAVES || !guards.isEmpty()) return;
        session.markRewarded();
        Bukkit.broadcastMessage("§6§lLETUMCRAFT §7— §aLa cámara de la fortaleza quedó despejada. ¡El cofre está abierto!");
        finishAfterReward();
    }

    private void finishAfterReward() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (waveTask != null) { waveTask.cancel(); waveTask = null; }
        cleanupGuards();
        sessionSeq++;
        session = null;
        chestLocation = null;
        wave = 0;
    }

    private void finish() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (waveTask != null) { waveTask.cancel(); waveTask = null; }
        if (session != null) session.abort();
        cleanupGuards();
        secureChestAndRemove();
        sessionSeq++;
        session = null;
        chestLocation = null;
        wave = 0;
    }

    private void secureChestAndRemove() {
        if (chestLocation == null || chestLocation.getWorld() == null) return;
        Block block = chestLocation.getBlock();
        if (block.getState() instanceof Chest chest
                && chest.getPersistentDataContainer().getOrDefault(plugin.fortressChestKey(), PersistentDataType.BYTE, (byte) 0) == 1) {
            chest.getBlockInventory().clear();
            block.setType(Material.AIR, false);
        }
    }

    private void cleanupGuards() {
        for (UUID id : Set.copyOf(guards)) {
            var entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        guards.clear();
    }

    private void zeroDrops(LivingEntity entity) {
        var eq = entity.getEquipment();
        if (eq != null) for (EquipmentSlot slot : EquipmentSlot.values()) eq.setDropChance(slot, 0f);
    }

    private double probability(String path, double fallback) {
        return Math.max(0, Math.min(1, plugin.getConfig().getDouble(path, fallback)));
    }
}
