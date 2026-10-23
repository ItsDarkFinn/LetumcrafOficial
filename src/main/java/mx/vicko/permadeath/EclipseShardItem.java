package mx.vicko.permadeath;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Objeto apilable que representa una Esquirla de Eclipse. */
public final class EclipseShardItem {
    private final NamespacedKey markerKey = new NamespacedKey("permadeath", "eclipse_shard");
    private final NamespacedKey itemModelKey = NamespacedKey.fromString("permadeath:eclipse_shard");

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Esquirla de Eclipse");
        meta.setLore(List.of(
                ChatColor.GRAY + "Un fragmento cargado con el eco del eclipse.",
                ChatColor.DARK_PURPLE + "Se usa en el Sello de Resurrección."
        ));
        meta.setItemModel(itemModelKey);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isShard(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }
}
