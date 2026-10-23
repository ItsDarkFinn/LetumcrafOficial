package mx.vicko.permadeath;

import java.util.HashSet;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Daily Nether expedition at a nearby fortress, with three guard waves and a locked reward chest. */
public final class FortressHuntEvent implements Listener {
    private final PermadeathPlugin plugin;
    private final Set<UUID> guards = new HashSet<>();
    private BukkitTask scheduler;
    private BukkitTask cleanupTask;
    private Location chestLocation;
    private int wave;
    private boolean manual;

    public FortressHuntEvent(PermadeathPlugin plugin) { this.plugin = plugin; }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduler = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1200L);
    }

    public void shutdown() {
        if (scheduler != null) scheduler.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
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

    private void tick() {
        if (chestLocation != null || !plugin.getConfig().getBoolean("random-events.enabled", true)
                || !plugin.getConfig().getBoolean("random-events.nether.fortress-hunt.enabled", true)) return;
        int day = plugin.days().getCurrentDay();
        int startDay = Math.max(1, plugin.getConfig().getInt("random-events.nether.fortress-hunt.start-day", 10));
        if (day < startDay || plugin.getConfig().getInt("random-events.nether.fortress-hunt.last-day", 0) >= day) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() == World.Environment.NETHER && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && !plugin.deaths().isDead(player.getUniqueId())) {
                if (startNear(player, false).startsWith("Cacería de Fortaleza iniciada")) {
                    plugin.getConfig().set("random-events.nether.fortress-hunt.last-day", day);
                    plugin.saveConfig();
                }
                return;
            }
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
        if (chestLocation == null) return;
        wave++;
        Bukkit.broadcastMessage("§c§lCacería §7— Oleada §e" + wave + "§7/3");
        int piglins = wave == 1 ? 4 : wave == 2 ? 2 : 0;
        int witherSkeletons = wave == 1 ? 0 : wave == 2 ? 3 : 2;
        for (int i = 0; i < piglins; i++) spawnGuard(EntityType.ZOMBIFIED_PIGLIN, false, i);
        if (wave == 3) spawnGuard(EntityType.PIGLIN_BRUTE, true, 0);
        for (int i = 0; i < witherSkeletons; i++) spawnGuard(EntityType.WITHER_SKELETON, false, i + piglins);
        if (guards.isEmpty()) openChest();
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
        if (!guards.remove(event.getEntity().getUniqueId()) || chestLocation == null) return;
        event.getDrops().clear();
        if (guards.isEmpty()) {
            if (wave < 3) Bukkit.getScheduler().runTaskLater(plugin, this::spawnNextWave, 40L);
            else openChest();
        }
    }

    @EventHandler(ignoreCancelled = true) public void onChestOpen(InventoryOpenEvent event) {
        if (chestLocation == null || !(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof Chest chest)
                || !chest.getLocation().equals(chestLocation)
                || chest.getPersistentDataContainer().getOrDefault(plugin.fortressChestKey(), PersistentDataType.BYTE, (byte) 0) != 1) return;
        if (!guards.isEmpty()) { event.setCancelled(true); player.sendMessage("§cLa cámara sigue sellada; derrota a todos los guardias."); }
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
        if (chestLocation == null) return;
        Bukkit.broadcastMessage("§6§lLETUMCRAFT §7— §aLa cámara de la fortaleza quedó despejada. ¡El cofre está abierto!");
        finish();
    }

    private void finish() {
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        cleanupGuards();
        chestLocation = null;
        wave = 0;
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
