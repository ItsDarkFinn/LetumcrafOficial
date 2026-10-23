package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Color;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/** First playable implementation of the fungal reserve world. */
public final class ReservaPodrida implements Listener {
    public static final String WORLD_NAME = "Letumcraft_Reserva_v7";
    private static final String LEGACY_WORLD_PREFIX = "Letumcraft_Reserva";
    public static final int UNLOCK_DAY = 20;

    private final PermadeathPlugin plugin;
    private final NamespacedKey filterItemKey;
    private final NamespacedKey filterUntilKey;
    private final NamespacedKey maskItemKey;
    private final NamespacedKey maskModelKey;
    private final NamespacedKey maskRecipeKey;
    private final NamespacedKey filterRecipeKey;
    private final NamespacedKey chestLootKey;
    private final NamespacedKey reserveMobKey;
    private final NamespacedKey returnWorldKey;
    private final NamespacedKey returnXKey;
    private final NamespacedKey returnYKey;
    private final NamespacedKey returnZKey;
    private final NamespacedKey returnYawKey;
    private final NamespacedKey returnPitchKey;
    private final BukkitTask atmosphereTask;
    private final Map<UUID, Long> nextSporeDamage = new HashMap<>();

    public ReservaPodrida(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.filterItemKey = new NamespacedKey(plugin, "spore_filter");
        this.filterUntilKey = new NamespacedKey(plugin, "spore_filter_until");
        this.maskItemKey = new NamespacedKey(plugin, "spore_mask");
        this.maskModelKey = new NamespacedKey("permadeath", "spore_mask");
        this.maskRecipeKey = new NamespacedKey(plugin, "spore_mask_recipe");
        this.filterRecipeKey = new NamespacedKey(plugin, "spore_filter_recipe");
        this.chestLootKey = new NamespacedKey(plugin, "reserve_chest_loot");
        this.reserveMobKey = new NamespacedKey(plugin, "reserve_mob");
        this.returnWorldKey = new NamespacedKey(plugin, "reserve_return_world");
        this.returnXKey = new NamespacedKey(plugin, "reserve_return_x");
        this.returnYKey = new NamespacedKey(plugin, "reserve_return_y");
        this.returnZKey = new NamespacedKey(plugin, "reserve_return_z");
        this.returnYawKey = new NamespacedKey(plugin, "reserve_return_yaw");
        this.returnPitchKey = new NamespacedKey(plugin, "reserve_return_pitch");
        registerMaskRecipe();
        registerFilterRecipe();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.atmosphereTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAtmosphere, 20L, 20L);
    }

    public void shutdown() {
        if (atmosphereTask != null) { atmosphereTask.cancel(); }
    }

    /** Bloque 4 — tras el sellado las esporas no dañan a los conectados. */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean isReserve(World world) {
        return world != null && world.getName().startsWith(LEGACY_WORLD_PREFIX);
    }

    /**
     * Bloque 3 — Mundo Reserva existente para el recinto de La Fosa.
     * Solo lectura: devuelve el mundo si ya está cargado, o null.
     * Nunca crea ni regenera nada (sin tocar mundos).
     */
    public World reserveWorld() {
        for (World world : Bukkit.getWorlds()) {
            if (isReserve(world)) {
                return world;
            }
        }
        return null;
    }

    public String enter(Player player) {
        return enter(player, player.getLocation());
    }

    public String enter(Player player, Location returnLocation) {
        if (!plugin.getConfig().getBoolean("reserve.enabled", true)) {
            return "&cLa Reserva Podrida está desactivada.";
        }
        int unlockDay = plugin.getConfig().getInt("reserve.unlock-day", UNLOCK_DAY);
        int effectiveUnlock = plugin.seasonService() != null ? plugin.seasonService().effectiveUnlockDay(unlockDay) : unlockDay;
        if (plugin.days().getCurrentDay() < effectiveUnlock && !player.hasPermission("permadeath.admin")) {
            return "&cLa Reserva Podrida se desbloquea en el día " + effectiveUnlock + ".";
        }
        if (isReserve(player.getWorld())) {
              return "&cYa estás en la Reserva Podrida.";
        }
        World world = getOrCreateWorld();
        if (world == null) return "&cNo se pudo crear el mundo de la Reserva. Revisa la consola.";
        if (plugin.reservaPortal() != null) plugin.reservaPortal().ensureReserveExit(world);

        Location from = returnLocation == null ? player.getLocation() : returnLocation;
        if (from.getWorld() == null) from = player.getLocation();
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(returnWorldKey, PersistentDataType.STRING, from.getWorld().getName());
        data.set(returnXKey, PersistentDataType.DOUBLE, from.getX());
        data.set(returnYKey, PersistentDataType.DOUBLE, from.getY());
        data.set(returnZKey, PersistentDataType.DOUBLE, from.getZ());
        data.set(returnYawKey, PersistentDataType.FLOAT, from.getYaw());
        data.set(returnPitchKey, PersistentDataType.FLOAT, from.getPitch());

        Location spawn = world.getSpawnLocation();
        spawn.setY(ReservaGenerator.floorHeight(0, 0) + 1.0);
          // Keep the incoming orientation: no forced camera reset on entry.
        spawn.setYaw(from.getYaw());
        spawn.setPitch(from.getPitch());
        if (!player.teleport(spawn)) {
            clearReturnData(player);
            return "&cNo se pudo entrar a la Reserva Podrida. Inténtalo de nuevo.";
        }
        player.sendMessage(color("&2Has entrado en &aLa Reserva Podrida&2. El aire está contaminado."));
        player.sendMessage(color("&7Máscara antigás y un cartucho te protegen de las esporas durante 10 minutos."));
        return null;
    }

    public static int getReserveFloorHeight(int x, int z) {
        return floorHeight(x, z);
    }

    public String exit(Player player) {
        if (!isReserve(player.getWorld())) return "&cNo estás en la Reserva Podrida.";
        PersistentDataContainer data = player.getPersistentDataContainer();
        String worldName = data.get(returnWorldKey, PersistentDataType.STRING);
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) world = fallbackReturnWorld();
        double x = data.getOrDefault(returnXKey, PersistentDataType.DOUBLE, world.getSpawnLocation().getX());
        double y = data.getOrDefault(returnYKey, PersistentDataType.DOUBLE, world.getSpawnLocation().getY());
        double z = data.getOrDefault(returnZKey, PersistentDataType.DOUBLE, world.getSpawnLocation().getZ());
        float yaw = data.getOrDefault(returnYawKey, PersistentDataType.FLOAT, 0.0f);
        float pitch = data.getOrDefault(returnPitchKey, PersistentDataType.FLOAT, 0.0f);
        Location destination = new Location(world, x, y, z, yaw, pitch);
        world.getChunkAt(destination).load(true);
        if (!isSafeDestination(destination)) destination = world.getSpawnLocation();
        if (!player.teleport(destination)) {
              return "&cNo se pudo salir de la Reserva. Tus datos de retorno se conservan.";
        }
        clearReturnData(player);
        return null;
    }

    private World fallbackReturnWorld() {
        // Never bounce into the Nether: prefer a loaded NORMAL world.
        World normal = Bukkit.getWorlds().stream()
                .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
        return normal != null ? normal : Bukkit.getWorlds().getFirst();
    }

    private boolean isSafeDestination(Location destination) {
        Block feet = destination.getBlock();
        Block head = destination.clone().add(0.0, 1.0, 0.0).getBlock();
        if (feet.getType() == Material.LAVA || head.getType() == Material.LAVA
                || feet.getType() == Material.FIRE || head.getType() == Material.FIRE) return false;
        return !(feet.getType().isSolid() && head.getType().isSolid());
    }

    private void clearReturnData(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.remove(returnWorldKey);
        data.remove(returnXKey);
        data.remove(returnYKey);
        data.remove(returnZKey);
        data.remove(returnYawKey);
        data.remove(returnPitchKey);
    }

    public ItemStack createFilter(int amount) {
        ItemStack item = new ItemStack(Material.HONEYCOMB, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&aCartucho de Esporas"));
        meta.setItemModel(new NamespacedKey("permadeath", "spore_filter"));
        meta.setLore(List.of(color("&7Se usa automáticamente con una Máscara Antigás."),
                color("&7Protege durante 10 minutos en la Reserva Podrida.")));
        meta.getPersistentDataContainer().set(filterItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Public factory for /pd mascara (MiniMax C): gas mask with diamond-helmet stats. */
    public ItemStack createMask() {
        ItemStack item = new ItemStack(Material.TURTLE_HELMET);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&2Máscara Antigás"));
        meta.setLore(List.of(color("&7Protege de las esporas de la Reserva."),
                color("&7Necesita un Cartucho de Esporas en el inventario."),
                color("&bStats equivalentes al casco de diamante · Protección II")));
        meta.addEnchant(Enchantment.PROTECTION, 2, true);
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                new NamespacedKey(plugin, "spore_mask_armor"), 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                new NamespacedKey(plugin, "spore_mask_toughness"), 2.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.setItemModel(maskModelKey);
        meta.getPersistentDataContainer().set(maskItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.MAX_DAMAGE, 363);
        return item;
    }

    private void registerMaskRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(maskRecipeKey, createMask());
        recipe.shape("IGI", "GTG", "IHI");
        recipe.setIngredient('I', Material.IRON_NUGGET);
        recipe.setIngredient('G', Material.GLASS_PANE);
        recipe.setIngredient('T', Material.DIAMOND_HELMET);
        recipe.setIngredient('H', new org.bukkit.inventory.RecipeChoice.MaterialChoice(
                Material.HONEYCOMB, Material.HONEYCOMB_BLOCK));
        plugin.getServer().removeRecipe(maskRecipeKey);
        plugin.getServer().addRecipe(recipe);
          validateRecipe(maskRecipeKey, "máscara");
        discoverRecipe(maskRecipeKey);
    }

    private void registerFilterRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(filterRecipeKey, createFilter(2));
        recipe.shape("HCP");
        recipe.setIngredient('H', Material.HONEYCOMB);
        recipe.setIngredient('C', Material.CHARCOAL);
        recipe.setIngredient('P', Material.PAPER);
        plugin.getServer().removeRecipe(filterRecipeKey);
        plugin.getServer().addRecipe(recipe);
          validateRecipe(filterRecipeKey, "cartucho");
        discoverRecipe(filterRecipeKey);
    }

    private void validateRecipe(NamespacedKey key, String label) {
        if (plugin.getServer().getRecipe(key) == null) {
            plugin.getLogger().warning("No se pudo registrar la receta de " + label + " (" + key + ").");
        }
    }

    private void discoverRecipe(NamespacedKey key) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.discoverRecipe(key);
        }
    }

    @EventHandler
    public void onJoinDiscover(PlayerJoinEvent event) {
        // The recipe book only shows discovered recipes: grant both on every join.
        event.getPlayer().discoverRecipe(filterRecipeKey);
        event.getPlayer().discoverRecipe(maskRecipeKey);
    }

    public String giveFilter(Player player, int amount) {
        var leftovers = player.getInventory().addItem(createFilter(amount));
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        return "&aCartucho(s) de esporas entregado(s): " + amount + ".";
    }

    private World getOrCreateWorld() {
        World existing = Bukkit.getWorld(WORLD_NAME);
        if (existing != null) return existing;
        WorldCreator creator = new WorldCreator(WORLD_NAME)
                .environment(World.Environment.NORMAL)
                .generator(new ReservaGenerator(plugin.getConfig().getDouble("reserve.chest-chance-per-chunk", 0.16)))
                .biomeProvider(new ReserveBiomeProvider());
        World world = creator.createWorld();
        if (world == null) return null;
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setTime(18000L);
        world.setStorm(false);
        world.setSpawnLocation(new Location(world, 0, ReservaGenerator.floorHeight(0, 0) + 1, 0));
        return world;
    }

    private void tickAtmosphere() {
        if (seasonEnded()) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isReserve(player.getWorld())) {
                nextSporeDamage.remove(player.getUniqueId());
                continue;
            }
            Location aroundPlayer = player.getLocation().add(0, 0.8, 0);
            player.spawnParticle(org.bukkit.Particle.DUST, aroundPlayer, 10, 3.5, 2.0, 3.5, 0,
                    new org.bukkit.Particle.DustOptions(Color.fromRGB(123, 169, 70), 1.15f));
            player.spawnParticle(org.bukkit.Particle.SPORE_BLOSSOM_AIR, aroundPlayer, 6, 3.0, 1.8, 3.0, 0.01);

            ItemStack helmet = player.getInventory().getHelmet();
            boolean wearingMask = helmet != null && helmet.getType() == Material.TURTLE_HELMET
                    && helmet.getItemMeta() != null
                    && helmet.getItemMeta().getPersistentDataContainer().has(maskItemKey, PersistentDataType.BYTE);
            if (wearingMask && hasActiveFilter(player, now)) {
                nextSporeDamage.remove(player.getUniqueId());
                continue;
            }

            long damageAt = nextSporeDamage.computeIfAbsent(player.getUniqueId(), ignored -> now + 5_000L);
            if (now < damageAt) continue;
            nextSporeDamage.put(player.getUniqueId(), now + 5_000L);
            player.damage(2.0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 110, 0, false, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 110, 0, false, false, true));
            if (!wearingMask) {
                player.sendMessage(color("&2Las esporas te queman los pulmones. Necesitas una Máscara Antigás y un cartucho."));
            } else {
                player.sendMessage(color("&2Tu máscara no tiene un cartucho activo: las esporas te están alcanzando."));
            }
        }
    }

    private boolean hasActiveFilter(Player player, long now) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        long until = data.getOrDefault(filterUntilKey, PersistentDataType.LONG, 0L);
        if (until > now) return true;
        if (!consumeFilter(player)) {
            data.remove(filterUntilKey);
            return false;
        }
        long duration = Math.max(1, plugin.getConfig().getLong("reserve.filter-duration-minutes", 10)) * 60_000L;
        data.set(filterUntilKey, PersistentDataType.LONG, now + duration);
        player.sendMessage(color("&aCartucho activado. Tu máscara filtrará esporas por "
                + (duration / 60_000L) + " minutos."));
        return true;
    }

    private boolean consumeFilter(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.getPersistentDataContainer().has(filterItemKey, PersistentDataType.BYTE)) continue;
            if (item.getAmount() <= 1) item.setAmount(0);
            else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    /** Rango vertical del barrido de cofres de ruina (limite superior exclusivo). Cubre chestY = floor+1 (30..60). */
    static final int RESERVE_CHEST_MIN_Y = 30;
    static final int RESERVE_CHEST_MAX_Y = 95;

    /** Indica si una Y entra en el barrido de cofres (puro, testeable sin servidor). */
    static boolean isReserveChestScanY(int y) {
        return y >= RESERVE_CHEST_MIN_Y && y < RESERVE_CHEST_MAX_Y;
    }

    /**
     * Regla unica de relleno: el cofre se rellena UNA SOLA VEZ.
     * Solo un cofre sin bandera (null), vacio y en ruina identificable puede rellenarse (pasa a 1).
     * Un cofre con bandera 0 (saqueado) o 1 (rellenado una vez) nunca se vuelve a rellenar.
     */
    static boolean shouldFillReserveChest(Byte flag, boolean inventoryEmpty, boolean likelyRuinChest) {
        return flag == null && inventoryEmpty && likelyRuinChest;
    }

    @EventHandler
    public void onNewChunk(ChunkLoadEvent event) {
        if (!isReserve(event.getWorld())) return;
        // Tambien cubre mundos ya generados: coste acotado a este chunk, sin recorrer el mundo.
        Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> markGeneratedChests(chunk));
    }

    private void markGeneratedChests(Chunk chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = RESERVE_CHEST_MIN_Y; y < RESERVE_CHEST_MAX_Y; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() != Material.CHEST) continue;
                    BlockState state = block.getState();
                    if (!(state instanceof Chest chest)) continue;
                    // Never overwrite a live inventory with a stale snapshot: flagged chests are done.
                    if (chest.getPersistentDataContainer().has(chestLootKey, PersistentDataType.BYTE)) continue;
                    // Persist the flag first, then fill the LIVE inventory: never update with a stale snapshot.
                    chest.getPersistentDataContainer().set(chestLootKey, PersistentDataType.BYTE, (byte) 1);
                    if (!chest.update(true, false)) continue;
                    BlockState fresh = block.getState();
                    if (!(fresh instanceof Chest live)) continue;
                    Inventory inventory = live.getBlockInventory();
                    if (!inventory.isEmpty()) {
                          // Unexpected loot on a fresh ruin: keep world/player items, flag already set.
                          continue;
                    }
                      fillChest(inventory);
                      // No further update: the live inventory is already persisted.
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onReserveChestOpen(InventoryOpenEvent event) {
        List<Chest> chests = reserveChests(event.getInventory());
        if (chests.isEmpty()) return;
        // Regla unica: el cofre se rellena UNA SOLA VEZ. Si cualquier lado ya fue
        // rellenado (1) o saqueado (0), nunca se vuelve a rellenar.
        boolean anyMarked = false;
        for (Chest chest : chests) {
            Byte flag = chest.getPersistentDataContainer().get(chestLootKey, PersistentDataType.BYTE);
            if (flag != null) { anyMarked = true; break; }
        }
        if (anyMarked) {
            // Una vez saqueado, nunca vuelve a aparecer loot: marca 0 (saqueado) si quedo vacio.
            if (event.getInventory().isEmpty()) {
                for (Chest chest : chests) setChestFlag(chest, (byte) 0);
            }
            return;
        }
        // Sin bandera: solo ruinas identificables, nunca cofres colocados por jugadores.
        if (!event.getInventory().isEmpty()) return;
        if (!isLikelyRuinChest(chests.get(0).getBlock())) return;
        fillChest(event.getInventory());
        for (Chest chest : chests) setChestFlag(chest, (byte) 1);
    }

    /** Collects the reserve chest block states behind an opened inventory (single or double). */
    private List<Chest> reserveChests(Inventory inventory) {
        List<Chest> chests = new ArrayList<>();
        if (inventory.getHolder() instanceof Chest chest) {
            if (isReserve(chest.getWorld())) chests.add(chest);
        } else if (inventory.getHolder() instanceof DoubleChest pair) {
            if (pair.getLeftSide() instanceof Chest left && isReserve(left.getWorld())) chests.add(left);
            if (pair.getRightSide() instanceof Chest right && isReserve(right.getWorld())) chests.add(right);
        }
        return chests;
    }

    private void setChestFlag(Chest chest, byte value) {
        BlockState fresh = chest.getBlock().getState();
        if (!(fresh instanceof Chest live)) return;
        live.getPersistentDataContainer().set(chestLootKey, PersistentDataType.BYTE, value);
        // Fresh snapshot: persisting the flag cannot clobber the live inventory.
        live.update(true, false);
    }

    /** A generated ruin chest sits at floor+1 surrounded by ruin masonry. */
    private boolean isLikelyRuinChest(Block block) {
        if (block.getY() != floorHeight(block.getX(), block.getZ()) + 1) return false;
        World world = block.getWorld();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Material type = world.getBlockAt(block.getX() + dx, block.getY() + dy, block.getZ() + dz).getType();
                    if (type == Material.MOSSY_STONE_BRICKS || type == Material.CRACKED_STONE_BRICKS
                            || type == Material.MUD_BRICKS || type == Material.MOSS_BLOCK) return true;
                }
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onReserveMobSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !isReserve(event.getLocation().getWorld())) return;
        double roll = Math.random();
        if (event.getEntity() instanceof Zombie zombie && roll < 0.12) {
            markReserveMob(zombie, "spore_carrier", "§2Portador de Esporas", 32.0, 0.24);
            var attack = zombie.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack != null) attack.setBaseValue(5.0);
            var equipment = zombie.getEquipment();
            if (equipment != null) {
                Color moss = Color.fromRGB(83, 111, 48);
                equipment.setHelmet(dyedLeather(Material.LEATHER_HELMET, moss));
                equipment.setChestplate(dyedLeather(Material.LEATHER_CHESTPLATE, moss));
                equipment.setLeggings(dyedLeather(Material.LEATHER_LEGGINGS, moss));
                equipment.setBoots(dyedLeather(Material.LEATHER_BOOTS, moss));
                equipment.setItemInMainHand(new ItemStack(Material.IRON_HOE));
                equipment.setHelmetDropChance(0.0f);
                equipment.setChestplateDropChance(0.0f);
                equipment.setLeggingsDropChance(0.0f);
                equipment.setBootsDropChance(0.0f);
                equipment.setItemInMainHandDropChance(0.0f);
            }
        } else if (event.getEntity() instanceof Spider spider && roll < 0.10) {
            markReserveMob(spider, "root_stalker", "§aAcechador de Raíces", 26.0, 0.36);
        }
    }

    private void markReserveMob(org.bukkit.entity.LivingEntity mob, String id, String name,
                                double maxHealth, double movementSpeed) {
        mob.getPersistentDataContainer().set(reserveMobKey, PersistentDataType.STRING, id);
        mob.setCustomName(name);
        boolean rootStalker = "root_stalker".equals(id);
        mob.setCustomNameVisible(!rootStalker);
        mob.setPersistent(true);
        mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0,
                false, false, false));
        if (rootStalker) mob.setInvisible(true);
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(maxHealth);
            mob.setHealth(maxHealth);
        }
        var speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(movementSpeed);
    }

    private ItemStack dyedLeather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRootStalkerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Spider spider)
                || !"root_stalker".equals(spider.getPersistentDataContainer().get(reserveMobKey, PersistentDataType.STRING))
                || !(event.getEntity() instanceof Player player) || !isReserve(player.getWorld())) return;
        // Sin dano continuo: el golpe conserva su dano normal, sin veneno/sangrado.
    }

    @EventHandler
    public void onReserveMobDeath(EntityDeathEvent event) {
        String type = event.getEntity().getPersistentDataContainer().get(reserveMobKey, PersistentDataType.STRING);
        if ("spore_carrier".equals(type)) {
            if (Math.random() < 0.25) event.getDrops().add(createFilter(1));
        } else if ("root_stalker".equals(type)) {
            event.getDrops().add(new ItemStack(Material.MANGROVE_ROOTS, 1 + (int) (Math.random() * 2)));
            if (Math.random() < 0.12) event.getDrops().add(new ItemStack(Material.HONEYCOMB));
        } else return;
        event.setDroppedExp(Math.max(event.getDroppedExp(), 8));
    }

    private void fillChest(Inventory inventory) {
        inventory.clear();
        Random random = new Random();
        LootInventory.addRandomly(inventory, createFilter(2 + random.nextInt(3)));
        addReward(inventory, new ItemStack(Material.GOLDEN_APPLE, 1 + random.nextInt(2)));
        int rolls = 4 + random.nextInt(3);
        for (int i = 0; i < rolls; i++) {
            double roll = random.nextDouble();
            if (roll < 0.20) LootInventory.addRandomly(inventory, new ItemStack(Material.COOKED_BEEF, 8 + random.nextInt(9)));
            else if (roll < 0.35) LootInventory.addRandomly(inventory, new ItemStack(Material.ARROW, 16 + random.nextInt(17)));
            else if (roll < 0.50) LootInventory.addRandomly(inventory, new ItemStack(Material.IRON_INGOT, 6 + random.nextInt(7)));
            else if (roll < 0.67) LootInventory.addRandomly(inventory, new ItemStack(Material.DIAMOND, 1 + random.nextInt(3)));
            else if (roll < 0.79) LootInventory.addRandomly(inventory, enchant(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION, 3));
            else if (roll < 0.88) LootInventory.addRandomly(inventory, enchant(new ItemStack(Material.DIAMOND_SWORD), Enchantment.SHARPNESS, 3));
            else if (roll < 0.95) LootInventory.addRandomly(inventory, new ItemStack(Material.NETHERITE_SCRAP, 1 + random.nextInt(2)));
            else if (roll < 0.99) LootInventory.addRandomly(inventory, new ItemStack(Material.TOTEM_OF_UNDYING));
            else LootInventory.addRandomly(inventory, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
        }
    }

    private ItemStack enchant(ItemStack item, Enchantment enchantment, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(enchantment, level, true);
        item.setItemMeta(meta);
        return item;
    }

    private void addReward(Inventory inventory, ItemStack item) {
        LootInventory.addRandomly(inventory, item);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private static final class ReserveBiomeProvider extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.MANGROVE_SWAMP;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.MANGROVE_SWAMP);
        }
    }

    private static final class ReservaGenerator extends ChunkGenerator {
        private static final int ROOF_Y = 68;
        private final double chestChance;

        private ReservaGenerator(double chestChance) {
            this.chestChance = Math.max(0.0, Math.min(1.0, chestChance));
        }

        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, floorHeight(0, 0) + 1.0, 0.5);
        }

        @Override
        public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
            long seed = worldInfo.getSeed() ^ ((long) chunkX * 341873128712L) ^ ((long) chunkZ * 132897987541L);
            Random features = new Random(seed);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;
                    int floor = floorHeight(worldX, worldZ);
                    int roof = reserveRoofHeight(worldX, worldZ);

                    int minY = data.getMinHeight();
                      for (int y = minY + 1; y < floor - 3; y++) {
                          data.setBlock(x, y, z, y < 0 ? Material.DEEPSLATE : Material.STONE);
                    }
                    // Continuous bedrock slab: deterministic per column, no gaps across chunks.
                    data.setBlock(x, minY, z, Material.BEDROCK);
                    data.setBlock(x, floor - 3, z, Material.DIRT);
                    data.setBlock(x, floor - 2, z, Material.ROOTED_DIRT);
                    data.setBlock(x, floor - 1, z, Material.MUD);
                    boolean path = isPath(worldX, worldZ);
                    data.setBlock(x, floor, z, path ? Material.MUD_BRICKS
                            : features.nextDouble() < 0.62 ? Material.MOSS_BLOCK : Material.MUD);

                    for (int y = roof - 4; y <= roof + 2; y++) {
                        Material ceiling = y == roof + 2 ? Material.MANGROVE_LEAVES
                                : y == roof ? Material.MUD
                                : features.nextDouble() < 0.20 ? Material.MANGROVE_ROOTS
                                : features.nextDouble() < 0.35 ? Material.ROOTED_DIRT : Material.MUD;
                        if (ceiling == Material.MANGROVE_LEAVES) setPersistentLeaves(data, x, y, z, ceiling);
                        else data.setBlock(x, y, z, ceiling);
                    }
                    // Bedrock seal above the real cave roof; deterministic per column, within height limits.
                    int capY = roof + 3;
                    if (capY < data.getMaxHeight()) data.setBlock(x, capY, z, Material.BEDROCK);

                    if (features.nextDouble() < 0.035) {
                        int length = 2 + features.nextInt(5);
                        for (int i = 1; i <= length && roof - i > floor + 3; i++) {
                            data.setBlock(x, roof - i, z, Material.HANGING_ROOTS);
                        }
                    }

                    if (!path && features.nextDouble() < 0.32) {
                        Material plant = switch (features.nextInt(7)) {
                            case 0 -> Material.MANGROVE_ROOTS;
                            case 1 -> Material.MOSS_CARPET;
                            case 2 -> Material.BROWN_MUSHROOM;
                            case 3 -> Material.RED_MUSHROOM;
                            case 4 -> Material.SHORT_GRASS;
                            case 5 -> Material.FERN;
                            default -> Material.MANGROVE_LEAVES;
                        };
                        if (plant == Material.MANGROVE_LEAVES || plant == Material.JUNGLE_LEAVES) {
                            setPersistentLeaves(data, x, floor + 1, z, plant);
                        } else {
                            data.setBlock(x, floor + 1, z, plant);
                        }
                        if (features.nextDouble() < 0.48) {
                            setPersistentLeaves(data, x, floor + 2, z, features.nextBoolean()
                                    ? Material.MANGROVE_LEAVES : Material.JUNGLE_LEAVES);
                        }
                    }
                }
            }

            // A varied mix of silhouettes makes the forest read less like repeated stamps.
            int treeCount = 7 + features.nextInt(5);
            List<int[]> treePositions = new java.util.ArrayList<>();
            for (int tree = 0; tree < treeCount; tree++) {
                int x = 1 + features.nextInt(14);
                int z = 1 + features.nextInt(14);
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                if (nearPath(worldX, worldZ, 1)) continue;

                boolean tooClose = treePositions.stream().anyMatch(position -> {
                    int dx = position[0] - x;
                    int dz = position[1] - z;
                    return dx * dx + dz * dz < 25;
                });
                if (tooClose) continue;
                treePositions.add(new int[] {x, z});

                int floor = floorHeight(worldX, worldZ);
                int roof = reserveRoofHeight(worldX, worldZ);
                int style = features.nextInt(100);
                int height;
                int radiusX;
                int radiusZ;
                int crownDepth;
                boolean ancient = style < 24;
                if (ancient) { // broad, old-growth silhouette like the reference
                    height = 10 + features.nextInt(7);
                    radiusX = 5 + features.nextInt(3);
                    radiusZ = 5 + features.nextInt(3);
                    crownDepth = 2;
                } else if (style < 36) { // very tall jungle tree
                    height = 14 + features.nextInt(6);
                    radiusX = 2 + features.nextInt(3);
                    radiusZ = 2 + features.nextInt(3);
                    crownDepth = 3;
                } else if (style < 53) { // broad, low mangrove crown
                    height = 6 + features.nextInt(4);
                    radiusX = 4 + features.nextInt(3);
                    radiusZ = 4 + features.nextInt(3);
                    crownDepth = 2;
                } else if (style < 70) { // thin trunk and compact crown
                    height = 9 + features.nextInt(6);
                    radiusX = 1 + features.nextInt(2);
                    radiusZ = 1 + features.nextInt(2);
                    crownDepth = 2;
                } else if (style < 88) { // squat, tangled tree
                    height = 5 + features.nextInt(4);
                    radiusX = 2 + features.nextInt(2);
                    radiusZ = 2 + features.nextInt(2);
                    crownDepth = 2;
                } else { // tiny young tree
                    height = 3 + features.nextInt(3);
                    radiusX = 1 + features.nextInt(2);
                    radiusZ = 1 + features.nextInt(2);
                    crownDepth = 1;
                }
                int crownY = Math.min(floor + height, roof - 4);
                height = Math.max(2, crownY - floor);
                Material log = features.nextInt(3) == 0 ? Material.JUNGLE_LOG : Material.MANGROVE_LOG;
                Material leaves = log == Material.JUNGLE_LOG ? Material.JUNGLE_LEAVES : Material.MANGROVE_LEAVES;
                boolean crooked = ancient || features.nextDouble() < 0.38;
                boolean thickTrunk = ancient || height >= 12 && features.nextDouble() < 0.28;
                int trunkX = x;
                int trunkZ = z;
                int bends = 0;

                for (int y = floor + 1; y <= crownY; y++) {
                    if (crooked && bends < 3 && y < crownY - 2 && y > floor + 2 && features.nextDouble() < 0.30) {
                        int oldX = trunkX;
                        int oldZ = trunkZ;
                        if (features.nextBoolean()) trunkX = Math.max(1, Math.min(14, trunkX + (features.nextBoolean() ? 1 : -1)));
                        else trunkZ = Math.max(1, Math.min(14, trunkZ + (features.nextBoolean() ? 1 : -1)));
                        // The old and new positions share a layer, creating a visible elbow instead of a straight pole.
                        data.setBlock(oldX, y, oldZ, log);
                        bends++;
                    }
                    data.setBlock(trunkX, y, trunkZ, log);
                    if (thickTrunk && y <= floor + 3 && trunkX < 15 && trunkZ < 15) {
                        data.setBlock(trunkX + 1, y, trunkZ, log);
                        data.setBlock(trunkX, y, trunkZ + 1, log);
                        data.setBlock(trunkX + 1, y, trunkZ + 1, log);
                    }
                }
                for (int dx = -radiusX; dx <= radiusX; dx++) {
                    for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                        for (int dy = -crownDepth; dy <= crownDepth; dy++) {
                            double distance = (dx * dx) / (double) (radiusX * radiusX)
                                    + (dz * dz) / (double) (radiusZ * radiusZ)
                                    + (dy * dy) / (double) (crownDepth * crownDepth);
                            int leafY = crownY + dy;
                            int leafX = trunkX + dx;
                            int leafZ = trunkZ + dz;
                            if (distance <= 1.15 && leafX >= 0 && leafX < 16 && leafZ >= 0 && leafZ < 16
                                    && leafY < roof - 2
                                    && !(dx == 0 && dz == 0 && leafY <= crownY)
                                    && features.nextDouble() > (style >= 84 ? 0.24 : 0.10)
                                    && data.getType(leafX, leafY, leafZ).isAir()) {
                                setPersistentLeaves(data, leafX, leafY, leafZ, leaves);
                            }
                        }
                    }
                }

                int branchCount = crooked ? 4 + features.nextInt(3) : height >= 12 ? 1 + features.nextInt(2) : 0;
                for (int branch = 0; branch < branchCount; branch++) {
                    int direction = features.nextInt(4);
                    int dx = direction == 0 ? 1 : direction == 1 ? -1 : 0;
                    int dz = direction == 2 ? 1 : direction == 3 ? -1 : 0;
                    int branchLength = 2 + features.nextInt(4);
                    int branchX = trunkX;
                    int branchZ = trunkZ;
                    int branchY = floor + Math.max(2, height / 2 + features.nextInt(Math.max(1, height / 3)));
                    Axis branchAxis = dx != 0 ? Axis.X : Axis.Z;
                    for (int step = 1; step <= branchLength; step++) {
                        branchX += dx;
                        branchZ += dz;
                        int logY = branchY + step / 2;
                        if (branchX < 0 || branchX > 15 || branchZ < 0 || branchZ > 15 || logY >= roof - 3) break;
                        BlockData branchLog = log.createBlockData();
                        if (branchLog instanceof Orientable orientable) orientable.setAxis(branchAxis);
                        data.setBlock(branchX, logY, branchZ, branchLog);
                    }
                    for (int leafX = branchX - 2; leafX <= branchX + 2; leafX++) {
                        for (int leafZ = branchZ - 2; leafZ <= branchZ + 2; leafZ++) {
                            for (int leafY = branchY + branchLength / 2 - 1; leafY <= branchY + branchLength / 2 + 1; leafY++) {
                                int ox = leafX - branchX;
                                int oz = leafZ - branchZ;
                                int oy = leafY - (branchY + branchLength / 2);
                                if (leafX >= 0 && leafX < 16 && leafZ >= 0 && leafZ < 16
                                        && leafY < roof - 2 && ox * ox + oz * oz + oy * oy <= 5
                                        && data.getType(leafX, leafY, leafZ).isAir()) {
                                    setPersistentLeaves(data, leafX, leafY, leafZ, leaves);
                                }
                            }
                        }
                    }
                }

                // Buttress roots and aerial roots give some trunks the old mangrove silhouette.
                for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if ((log == Material.MANGROVE_LOG || ancient) && features.nextBoolean()) {
                        int rootX = trunkX + offset[0];
                        int rootZ = trunkZ + offset[1];
                        if (rootX >= 0 && rootX < 16 && rootZ >= 0 && rootZ < 16) {
                            data.setBlock(rootX, floor + 1, rootZ, Material.MANGROVE_ROOTS);
                        }
                    }
                }
                if (ancient) {
                    for (int arm = 0; arm < 3; arm++) {
                        int rootDx = features.nextInt(3) - 1;
                        int rootDz = rootDx == 0 ? (features.nextBoolean() ? 1 : -1) : features.nextInt(3) - 1;
                        if (rootDx == 0 && rootDz == 0) rootDx = 1;
                        int rootLength = 2 + features.nextInt(3);
                        for (int step = 1; step <= rootLength; step++) {
                            int rootX = trunkX + rootDx * step;
                            int rootZ = trunkZ + rootDz * step;
                            if (rootX < 0 || rootX > 15 || rootZ < 0 || rootZ > 15) break;
                            data.setBlock(rootX, floor + Math.max(1, 3 - step / 2), rootZ, Material.MANGROVE_ROOTS);
                        }
                    }
                }
                if (log == Material.MANGROVE_LOG && height > 6 && features.nextBoolean()) {
                    int rootX = Math.max(0, Math.min(15, x + features.nextInt(5) - 2));
                    int rootZ = Math.max(0, Math.min(15, z + features.nextInt(5) - 2));
                    for (int y = crownY - 2; y > floor + 2; y--) {
                        if (features.nextDouble() > 0.55) break;
                        data.setBlock(rootX, y, rootZ, Material.HANGING_ROOTS);
                    }
                }
            }

            if (features.nextDouble() < chestChance) {
                int x = 4 + features.nextInt(8);
                int z = 4 + features.nextInt(8);
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                if (Math.abs(worldX) + Math.abs(worldZ) > 40) {
                    generateRuin(data, features, chunkX, chunkZ, x, z);
                }
            }
        }

        /** Builds a chunk-contained, partially reclaimed shelter around its loot chest. */
        private static void generateRuin(ChunkData data, Random random, int chunkX, int chunkZ, int cx, int cz) {
            int minX = cx - 3;
            int maxX = cx + 3;
            int minZ = cz - 3;
            int maxZ = cz + 3;
            Material[] masonry = {Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                    Material.MUD_BRICKS, Material.MOSS_BLOCK};

            // A broken, uneven floor; leave a central aisle toward the doorway on one side.
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int ground = floorHeight(chunkX * 16 + x, chunkZ * 16 + z);
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (edge || random.nextDouble() < 0.78) {
                        Material tile = random.nextDouble() < 0.18 ? Material.MOSSY_STONE_BRICKS
                                : random.nextDouble() < 0.25 ? Material.CRACKED_STONE_BRICKS : Material.MUD_BRICKS;
                        data.setBlock(x, ground, z, tile);
                    }
                }
            }

            // Wall remnants with irregular heights and deliberate gaps, never closing the entrance.
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean wall = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!wall) continue;
                    boolean doorway = z == maxZ && Math.abs(x - cx) <= 1;
                    boolean brokenAway = random.nextDouble() < 0.22;
                    if (doorway || brokenAway) continue;
                    int ground = floorHeight(chunkX * 16 + x, chunkZ * 16 + z);
                    int height = 1 + random.nextInt(4);
                    if ((x == minX || x == maxX) && (z == minZ || z == maxZ)) height += 1;
                    for (int y = 1; y <= height; y++) {
                        Material block = masonry[random.nextInt(masonry.length)];
                        data.setBlock(x, ground + y, z, block);
                    }
                    if (random.nextDouble() < 0.28) {
                        data.setBlock(x, ground + height + 1, z, Material.MANGROVE_ROOTS);
                    }
                }
            }

            // Collapsed roof fragments and rubble, with the middle kept open above the chest.
            for (int x = minX + 1; x < maxX; x++) {
                for (int z = minZ + 1; z < maxZ; z++) {
                    if (Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1) continue;
                    if (random.nextDouble() < 0.24) {
                        int ground = floorHeight(chunkX * 16 + x, chunkZ * 16 + z);
                        data.setBlock(x, ground + 4, z, masonry[random.nextInt(masonry.length)]);
                    }
                }
            }

            int chestY = floorHeight(chunkX * 16 + cx, chunkZ * 16 + cz) + 1;
            data.setBlock(cx, chestY, cz, Material.CHEST);
            // Moss and hanging roots visually blend the ruin into the overgrown reserve.
            for (int i = 0; i < 5; i++) {
                int x = minX + random.nextInt(7);
                int z = minZ + random.nextInt(7);
                int ground = floorHeight(chunkX * 16 + x, chunkZ * 16 + z);
                if (data.getType(x, ground + 1, z).isAir() && !(x == cx && z == cz)) {
                    Material detail = random.nextBoolean() ? Material.MOSS_CARPET : Material.MANGROVE_ROOTS;
                    data.setBlock(x, ground + 1, z, detail);
                }
            }
        }

        private static double mainPathX(double z) {
            return Math.sin(z * 0.025) * 9.0 + Math.sin(z * 0.009) * 18.0;
        }

        private static boolean isPath(int x, int z) {
            if (Math.abs(x - mainPathX(z)) <= 0.9) return true;

            long nearestAnchor = Math.round(z / 48.0) * 48L;
            for (long anchor = nearestAnchor - 48L; anchor <= nearestAnchor + 48L; anchor += 48L) {
                double centerX = mainPathX(anchor);
                int branchLength = 14 + Math.floorMod(anchorHash(anchor), 15);
                double branchCenterZ = anchor + Math.sin((x - centerX) * 0.045 + anchor) * 1.3;
                if (Math.abs(z - branchCenterZ) <= 0.9 && Math.abs(x - centerX) <= branchLength) return true;

                int diagonalSide = (anchorHash(anchor + 7L) & 1) == 0 ? 1 : -1;
                double endX = centerX + diagonalSide * branchLength;
                double deltaX = x - endX;
                double deltaZ = z - anchor;
                double progress = (deltaX * diagonalSide + deltaZ) * 0.5;
                double crossTrack = Math.abs(deltaX - deltaZ * diagonalSide);
                if (progress >= 0 && progress <= 14 && crossTrack <= 1.25) return true;
            }
            return false;
        }

        private static boolean nearPath(int x, int z, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isPath(x + dx, z + dz)) return true;
                }
            }
            return false;
        }

        private static int anchorHash(long value) {
            long mixed = value * 0x9E3779B97F4A7C15L;
            mixed ^= mixed >>> 30;
            mixed *= 0xBF58476D1CE4E5B9L;
            mixed ^= mixed >>> 27;
            return (int) (mixed ^ (mixed >>> 31));
        }

        private static int floorHeight(int x, int z) {
            double broadHills = Math.sin(x * 0.035) * 3.8 + Math.cos(z * 0.041) * 3.2
                    + Math.sin((x + z) * 0.022) * 2.4;
            double ridges = (1.0 - Math.abs(Math.sin(x * 0.018 + z * 0.013))) * 3.2;
            double roughGround = Math.sin(x * 0.11 + Math.sin(z * 0.04) * 2.0) * 1.4
                    + Math.cos(z * 0.13 - x * 0.045) * 1.1;
            double shape = broadHills + ridges + roughGround;
            return 44 + (int) Math.round(shape);
        }

        private static void setPersistentLeaves(ChunkData data, int x, int y, int z, Material material) {
            BlockData blockData = material.createBlockData();
            if (blockData instanceof Leaves leafData) leafData.setPersistent(true);
            data.setBlock(x, y, z, blockData);
        }
    }

    private static int floorHeight(int x, int z) {
        double broadHills = Math.sin(x * 0.035) * 3.8 + Math.cos(z * 0.041) * 3.2
                + Math.sin((x + z) * 0.022) * 2.4;
        double ridges = (1.0 - Math.abs(Math.sin(x * 0.018 + z * 0.013))) * 3.2;
        double roughGround = Math.sin(x * 0.11 + Math.sin(z * 0.04) * 2.0) * 1.4
                + Math.cos(z * 0.13 - x * 0.045) * 1.1;
        double shape = broadHills + ridges + roughGround;
        return 44 + (int) Math.round(shape);
    }

    /** Deterministic cave-roof height for column (x, z); the bedrock cap sits at +3. */
    static int reserveRoofHeight(int x, int z) {
        return ReservaGenerator.ROOF_Y + (int) Math.round(Math.sin(x * 0.025) * 1.5 + Math.cos(z * 0.03) * 1.5);
    }
}
