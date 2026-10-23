package mx.vicko.permadeath;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Crea el objeto raro usado más adelante por el ritual de resurrección. */
public final class EclipseCoreItem {
    private final NamespacedKey markerKey = new NamespacedKey("permadeath", "eclipse_core");
    private final NamespacedKey itemModelKey = NamespacedKey.fromString("permadeath:eclipse_core");

    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "Núcleo del Eclipse");
        meta.setLore(List.of(
                ChatColor.GRAY + "Un fragmento de oscuridad cristalizada.",
                ChatColor.LIGHT_PURPLE + "Catalizador para un ritual de resurrección."
        ));
        meta.setItemModel(itemModelKey);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCore(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }
}
