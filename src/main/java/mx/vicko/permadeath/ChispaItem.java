package mx.vicko.permadeath;

import java.util.List;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Bloque 3 — Chispa visual (opcional, solo escaparate).
 *
 * <p>Las Chispas reales viven en el ledger persistente ({@code pitEarned/pitSpent}
 * en {@code season-state.yml}), NUNCA en inventarios ni en el banco: el banco
 * solo acepta Núcleos (sin depósito ni conversión de Chispas) y este item no es
 * canjeable por nada. Si se entrega como recuerdo visual, lleva dueño (UUID) +
 * temporada + autenticidad PDC: sin esos tres no es una Chispa.
 */
public final class ChispaItem {

    private final NamespacedKey markerKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey seasonKey;
    private final NamespacedKey itemModelKey;

    public ChispaItem(PermadeathPlugin plugin) {
        this.markerKey = new NamespacedKey(plugin, "chispa");
        this.ownerKey = new NamespacedKey(plugin, "chispa_owner");
        this.seasonKey = new NamespacedKey(plugin, "chispa_season");
        this.itemModelKey = NamespacedKey.fromString("permadeath:chispa");
    }

    /** Recuerdo visual ligado a un dueño y una temporada (no es moneda). */
    public ItemStack createItem(UUID owner, String seasonId) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Chispa de la Fosa");
        meta.setLore(List.of(
                ChatColor.GRAY + "Recuerdo de una run completada.",
                ChatColor.DARK_GRAY + "No es moneda: tu saldo vive en la temporada."));
        if (itemModelKey != null) {
            try {
                meta.setItemModel(itemModelKey);
            } catch (NoSuchMethodError | RuntimeException ignored) {
            }
        }
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        if (owner != null) {
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        }
        if (seasonId != null && !seasonId.isBlank()) {
            meta.getPersistentDataContainer().set(seasonKey, PersistentDataType.STRING, seasonId);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Auténtica solo con marca + dueño UUID válido + temporada no vacía. */
    public boolean isAuthentic(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) {
            return false;
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        Byte marker = data.get(markerKey, PersistentDataType.BYTE);
        if (marker == null || marker != (byte) 1) {
            return false;
        }
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        String season = data.get(seasonKey, PersistentDataType.STRING);
        if (owner == null || season == null || season.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(owner);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return true;
    }
}
