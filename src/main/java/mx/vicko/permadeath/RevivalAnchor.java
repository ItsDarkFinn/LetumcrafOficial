package mx.vicko.permadeath;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Crea el Ancla del Último Aliento y vincula su modelo del resource pack. */
public final class RevivalAnchor implements Listener {
    public static final int UNLOCK_DAY = 35;
    private final PermadeathPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey recipeKey;
    private final NamespacedKey itemModelKey;

    public RevivalAnchor(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "revival_anchor");
        this.recipeKey = new NamespacedKey(plugin, "revival_anchor_recipe");
        this.itemModelKey = NamespacedKey.fromString("permadeath:anchor_last_breath");
    }

    public void register() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createItem());
        recipe.shape("ESE", "SND", "ESE");
        recipe.setIngredient('E', Material.ECHO_SHARD);
        recipe.setIngredient('S', Material.CRYING_OBSIDIAN);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('D', Material.DRAGON_BREATH);
        plugin.getServer().addRecipe(recipe);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Ancla del Último Aliento");
        meta.setLore(List.of(
                ChatColor.GRAY + "Un último latido contra la muerte permanente.",
                ChatColor.DARK_GRAY + "Úsala para devolver a alguien a la partida."
        ));
        meta.setItemModel(itemModelKey);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isAnchor(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public int findAnchorSlot(org.bukkit.entity.Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isAnchor(contents[slot])) return slot;
        }
        return -1;
    }

    public void consumeFromStorage(org.bukkit.entity.Player player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || !isAnchor(item)) return;
        if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
        else item.setAmount(item.getAmount() - 1);
    }

    public NamespacedKey recipeKey() {
        return recipeKey;
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        int effectiveAnchorDay = plugin.seasonService() != null ? plugin.seasonService().mapLegacyDay(UNLOCK_DAY) : UNLOCK_DAY;
        if (event.getRecipe() instanceof Keyed keyed && keyed.getKey().equals(recipeKey)
                && plugin.days().getCurrentDay() < effectiveAnchorDay) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onRevivedPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (!plugin.deaths().takePendingRevival(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.getInventory().clear();
            player.getEnderChest().clear();
            if (!plugin.getServer().getWorlds().isEmpty()) {
                var spawn = plugin.getServer().getWorlds().getFirst().getSpawnLocation().clone();
                player.teleport(spawn);
            }
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Has vuelto a la partida. Tu inventario se reinició.");
        });
    }
}
