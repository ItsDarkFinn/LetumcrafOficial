package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.scheduler.BukkitTask;

/** Campamento de caravana: un cofre protegido por cuatro guardias. */
public final class CaravanEvent implements Listener {
    private final PermadeathPlugin plugin;
    private BukkitTask task;
    private long lastAttemptDay = -1;

    public CaravanEvent(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 600L);
    }

    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
    }

    /** Bloque 4 — tras el sellado no hay Caravana automática. */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String status() {
        int day = plugin.days().getCurrentDay();
        int lastDay = plugin.getConfig().getInt("random-events.caravan.last-spawn-day", 0);
        return "Caravana: " + (plugin.getConfig().getBoolean("random-events.caravan.enabled", true) ? "activa" : "desactivada")
                + "; día " + day + "; última aparición automática: día " + lastDay + ".";
    }

    public String startTest(Player player) {
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return "La caravana solo puede aparecer en el Overworld.";
        }
        Location site = findSite(player);
        if (site == null) return "No encontré terreno seguro cerca; prueba en una zona despejada.";
        spawnCaravan(site, true);
        return "Caravana de prueba creada cerca de ti.";
    }

    private void tick() {
        if (seasonEnded()) return;
        if (!plugin.getConfig().getBoolean("random-events.enabled", true)
                || !plugin.getConfig().getBoolean("random-events.caravan.enabled", true)) return;
        int day = plugin.days().getCurrentDay();
        int interval = Math.max(1, plugin.getConfig().getInt("random-events.caravan.interval-days", 2));
        int lastDay = plugin.getConfig().getInt("random-events.caravan.last-spawn-day", 0);
        int caravanStart = Math.max(1, plugin.getConfig().getInt("random-events.caravan.start-day", 1));
        if (plugin.seasonService() != null) caravanStart = plugin.seasonService().mapLegacyDay(caravanStart);
        if (day < caravanStart
                || (lastDay > 0 && day - lastDay < interval) || lastAttemptDay == day) return;
        List<Player> players = eligiblePlayers().stream()
                .filter(player -> isNight(player.getWorld())).toList();
        if (players.isEmpty()) return;
        // Gate único de temporada (automático); /pd caravana iniciar lo omite (prueba manual).
        try {
            SeasonService seasons = plugin.seasonService();
            if (seasons != null && !seasons.passesGateForPlayers(players)) return;
        } catch (RuntimeException ignored) {
        }
        // Solo se tira una vez por noche; si falla, puede volver a intentarlo la siguiente noche.
        lastAttemptDay = day;
        double chance = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("random-events.caravan.night-check-chance", 0.10)));
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        Location site = findSite(target);
        if (site == null) return;
        plugin.getConfig().set("random-events.caravan.last-spawn-day", day);
        plugin.saveConfig();
        spawnCaravan(site, false);
    }

    private void spawnCaravan(Location chestLocation, boolean test) {
        World world = chestLocation.getWorld();
        if (world == null) return;
        Block block = chestLocation.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) return;
        chest.getPersistentDataContainer().set(plugin.caravanKey(), PersistentDataType.BYTE, (byte) 1);
        if (!chest.update(true, false)) return;
        if (!(block.getState() instanceof Chest liveChest)) return;
        fillLoot(liveChest.getBlockInventory());

        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 2.0 * i / 4.0;
            Location guardLocation = chestLocation.clone().add(Math.cos(angle) * 4.0, 0, Math.sin(angle) * 4.0);
            guardLocation.setY(world.getHighestBlockYAt(guardLocation) + 1.0);
            LivingEntity guard = i < 2
                    ? (LivingEntity) world.spawnEntity(guardLocation, EntityType.ZOMBIE)
                    : (LivingEntity) world.spawnEntity(guardLocation, EntityType.SKELETON);
            guard.setPersistent(true);
            guard.setGlowing(true);
            guard.getPersistentDataContainer().set(plugin.caravanKey(), PersistentDataType.BYTE, (byte) 1);
            if (guard instanceof Zombie zombie) equipZombie(zombie);
            else if (guard instanceof Skeleton skeleton) equipSkeleton(skeleton);
        }
        String label = test ? "Caravana de prueba" : "¡Se detectó una caravana!";
        Bukkit.broadcastMessage("§6§lLETUMCRAFT §e— " + label + " §7en §f"
                + world.getName() + " §7(X " + chestLocation.getBlockX() + ", Z " + chestLocation.getBlockZ() + ").");
        Bukkit.broadcastMessage("§7Derrota a sus cuatro guardias para saquear el cofre.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCaravanChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof Chest chest)
                || chest.getPersistentDataContainer().getOrDefault(plugin.caravanKey(), PersistentDataType.BYTE, (byte) 0) != 1) {
            return;
        }
        boolean guarded = chest.getWorld().getNearbyEntities(chest.getLocation().add(0.5, 0.5, 0.5), 20, 12, 20).stream()
                .anyMatch(entity -> entity instanceof LivingEntity living
                        && living.getPersistentDataContainer().getOrDefault(plugin.caravanKey(), PersistentDataType.BYTE, (byte) 0) == 1);
        if (guarded) {
            event.setCancelled(true);
            player.sendMessage("§6La caravana sigue protegida. Derrota a sus guardias para abrir el cofre.");
        }
    }

    private void fillLoot(org.bukkit.inventory.Inventory inventory) {
        inventory.clear();
        LootInventory.addRandomly(inventory, new ItemStack(Material.COOKED_BEEF, 6));
        LootInventory.addRandomly(inventory, new ItemStack(Material.ARROW, 12 + ThreadLocalRandom.current().nextInt(13)));
        LootInventory.addRandomly(inventory, new ItemStack(Material.IRON_INGOT, 3 + ThreadLocalRandom.current().nextInt(4)));
        LootInventory.addRandomly(inventory, new ItemStack(Material.BREAD, 4 + ThreadLocalRandom.current().nextInt(5)));

        double coreChance = probability("random-events.caravan.core-chance", 0.25);
        if (ThreadLocalRandom.current().nextDouble() < coreChance) {
            LootInventory.addRandomly(inventory, plugin.eclipseCoreItem().createItem());
        }
        // 1% total: se elige uno de los dos premios raros, no 1% para cada uno.
        if (ThreadLocalRandom.current().nextDouble() < probability("random-events.caravan.rare-loot-chance", 0.01)) {
            Material rare = ThreadLocalRandom.current().nextBoolean()
                    ? Material.TOTEM_OF_UNDYING : Material.ENCHANTED_GOLDEN_APPLE;
            LootInventory.addRandomly(inventory, new ItemStack(rare));
        }
    }

    private void equipZombie(Zombie zombie) {
        var equipment = zombie.getEquipment();
        if (equipment == null) return;
        equipment.setHelmet(new ItemStack(Material.IRON_HELMET));
        equipment.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        equipment.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        equipment.setBoots(new ItemStack(Material.IRON_BOOTS));
        equipment.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        zeroDropChances(zombie);
    }

    private void equipSkeleton(Skeleton skeleton) {
        var equipment = skeleton.getEquipment();
        if (equipment == null) return;
        equipment.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
        equipment.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        equipment.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        equipment.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
        equipment.setItemInMainHand(new ItemStack(Material.BOW));
        zeroDropChances(skeleton);
    }

    private void zeroDropChances(LivingEntity entity) {
        var equipment = entity.getEquipment();
        if (equipment == null) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) equipment.setDropChance(slot, 0.0f);
    }

    private Location findSite(Player player) {
        World world = player.getWorld();
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            int distance = 28 + ThreadLocalRandom.current().nextInt(37);
            int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            Block above = ground.getRelative(0, 1, 0);
            Block above2 = ground.getRelative(0, 2, 0);
            if (ground.getType().isSolid() && !ground.isLiquid() && above.isEmpty() && above2.isEmpty()) {
                return above.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    private List<Player> eligiblePlayers() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && !plugin.deaths().isDead(player.getUniqueId())
                    && !plugin.isOdysseyWorld(player.getWorld())) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean isNight(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL || plugin.isOdysseyWorld(world)) return false;
        long time = world.getTime();
        return time >= 13_000L && time <= 23_000L;
    }

    private double probability(String path, double fallback) {
        return Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble(path, fallback)));
    }
}
