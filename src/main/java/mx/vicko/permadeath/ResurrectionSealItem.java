package mx.vicko.permadeath;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Sello preparado para activar el ritual de resurrección. */
public final class ResurrectionSealItem implements Listener {
    private final PermadeathPlugin plugin;
    private final NamespacedKey markerKey = new NamespacedKey("permadeath", "resurrection_seal");
    private final NamespacedKey itemModelKey = NamespacedKey.fromString("permadeath:resurrection_seal");
    private final NamespacedKey recipeKey;

    public ResurrectionSealItem(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "resurrection_seal_recipe");
    }

    public void register() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createItem());
        recipe.shape(" S ", "SNS", " S ");
        recipe.setIngredient('S', Material.AMETHYST_SHARD);
        recipe.setIngredient('N', Material.ECHO_SHARD);
        plugin.getServer().removeRecipe(recipeKey);
        plugin.getServer().addRecipe(recipe);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "Sello de Resurrección");
        meta.setLore(List.of(
                ChatColor.GRAY + "Un vínculo entre los vivos y los caídos.",
                ChatColor.LIGHT_PURPLE + "El ritual requiere dos jugadores cerca."
        ));
        meta.setItemModel(itemModelKey);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSeal(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean hasCustomIngredients(ItemStack[] matrix) {
        return matrix != null && matrix.length == 9
                && plugin.eclipseShardItem().isShard(matrix[1])
                && plugin.eclipseShardItem().isShard(matrix[3])
                && plugin.eclipseCoreItem().isCore(matrix[4])
                && plugin.eclipseShardItem().isShard(matrix[5])
                && plugin.eclipseShardItem().isShard(matrix[7]);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() instanceof Keyed keyed && keyed.getKey().equals(recipeKey)
                && !hasCustomIngredients(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getRecipe() instanceof Keyed keyed && keyed.getKey().equals(recipeKey)
                && !hasCustomIngredients(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
            event.setCancelled(true);
        }
    }
}
