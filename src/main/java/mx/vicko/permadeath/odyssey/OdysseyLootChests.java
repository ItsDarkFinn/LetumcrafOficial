package mx.vicko.permadeath.odyssey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Rellena una sola vez el cofre de cada isla.
 *
 * <p>El bloque se coloca durante la generacion, pero la API de inventarios no
 * es segura desde un populator. Por eso el contenido se aplica al cargar o
 * poblar el chunk, que ya ocurre en el hilo principal. Una etiqueta persistente
 * evita que el cofre se rellene de nuevo tras vaciarlo o al reiniciar.</p>
 */
public final class OdysseyLootChests implements Listener {
    private static final int CHEST_SLOTS = 27;
    private static final double RARE_TOTEM_CHANCE = 0.03;
    private static final double RARE_ENCHANTED_APPLE_CHANCE = 0.06;
    private static final double RARE_SCRAP_CHANCE = 0.10;

    private final JavaPlugin plugin;
    private final OdysseyManager odyssey;
    private final NamespacedKey markerKey;

    public OdysseyLootChests(JavaPlugin plugin, OdysseyManager odyssey) {
        this.plugin = plugin;
        this.odyssey = odyssey;
        this.markerKey = new NamespacedKey(plugin, OdysseyTags.LOOT_CHEST);
    }

    /** Rellena los cofres de los chunks que ya estuvieran cargados al arrancar. */
    public void start() {
        World world = odyssey.world();
        if (world == null) {
            return;
        }
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            fill(chunk);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        if (odyssey.isOdyssey(event.getWorld())) {
            fill(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (odyssey.isOdyssey(event.getWorld())) {
            fill(event.getChunk());
        }
    }

    private void fill(org.bukkit.Chunk chunk) {
        int spacingChunks = odyssey.spacingChunks();
        if (Math.floorMod(chunk.getX(), spacingChunks) != 0
                || Math.floorMod(chunk.getZ(), spacingChunks) != 0) {
            return;
        }

        int chestX = chunk.getX() * 16 + 8 + OdysseyBlockPopulator.CHEST_OFFSET;
        int chestZ = chunk.getZ() * 16 + 8 + OdysseyBlockPopulator.CHEST_OFFSET;
        int topY = OdysseyBlockPopulator.islandTopY(
                chunk.getWorld(), spacingChunks, chunk.getX(), chunk.getZ(), odyssey.spawnTopY());
        Block block = chunk.getWorld().getBlockAt(chestX, topY + 1, chestZ);
        if (block.getType() != Material.CHEST) {
            return;
        }
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        if (chest.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) {
            return;
        }

        Inventory inventory = chest.getSnapshotInventory();
        if (!inventory.isEmpty()) {
            mark(chest);
            return;
        }

        fillInventory(inventory, chestX, chestZ, chunk.getWorld().getSeed());
        chest.customName(Component.text("Cofre de la Odisea", NamedTextColor.LIGHT_PURPLE));
        mark(chest);
        plugin.getLogger().fine("Cofre de La Falla preparado en " + chestX + ", " + (topY + 1)
                + ", " + chestZ + ".");
    }

    private void mark(Chest chest) {
        chest.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        chest.update(true, false);
    }

    private static void fillInventory(Inventory inventory, int chestX, int chestZ, long worldSeed) {
        long seed = worldSeed
                ^ (chestX * 7_919_191L)
                ^ (chestZ * 104_729L)
                ^ 0x5DEECE66DL;
        Random random = new Random(seed);
        inventory.clear();

        List<ItemStack> loot = new ArrayList<>();
        add(loot, Material.GOLDEN_APPLE, 1, 3, random);
        add(loot, Material.ENDER_PEARL, 2, 5, random);
        add(loot, Material.DIAMOND, 1, 4, random);
        add(loot, Material.IRON_INGOT, 4, 10, random);
        add(loot, Material.ARROW, 8, 16, random);
        add(loot, Material.COOKED_BEEF, 4, 10, random);
        add(loot, Material.EXPERIENCE_BOTTLE, 4, 10, random);
        add(loot, Material.AMETHYST_SHARD, 4, 12, random);
        if (random.nextBoolean()) {
            add(loot, Material.OBSIDIAN, 8, 16, random);
        }
        if (random.nextDouble() < RARE_ENCHANTED_APPLE_CHANCE) {
            add(loot, Material.ENCHANTED_GOLDEN_APPLE, 1, 1, random);
        }
        if (random.nextDouble() < RARE_SCRAP_CHANCE) {
            add(loot, Material.NETHERITE_SCRAP, 1, 2, random);
        }
        if (random.nextDouble() < RARE_TOTEM_CHANCE) {
            add(loot, Material.TOTEM_OF_UNDYING, 1, 1, random);
        }

        List<Integer> slots = new ArrayList<>(CHEST_SLOTS);
        for (int slot = 0; slot < CHEST_SLOTS; slot++) {
            slots.add(slot);
        }
        Collections.shuffle(slots, random);
        for (int index = 0; index < loot.size() && index < slots.size(); index++) {
            inventory.setItem(slots.get(index), loot.get(index));
        }
    }

    private static void add(List<ItemStack> loot, Material material, int min, int max, Random random) {
        int amount = min + random.nextInt(max - min + 1);
        loot.add(new ItemStack(material, amount));
    }
}
