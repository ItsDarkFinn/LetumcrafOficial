package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Places custom chest loot in shuffled slots instead of filling from the first slot onward. */
public final class LootInventory {
    private LootInventory() { }

    public static void addRandomly(Inventory inventory, ItemStack item) {
        if (inventory == null || item == null || item.getType().isAir() || item.getAmount() <= 0) return;

        List<Integer> slots = new ArrayList<>(inventory.getSize());
        for (int slot = 0; slot < inventory.getSize(); slot++) slots.add(slot);
        Collections.shuffle(slots, ThreadLocalRandom.current());

        int remaining = item.getAmount();
        for (int slot : slots) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                int moved = Math.min(remaining, item.getMaxStackSize());
                ItemStack placed = item.clone();
                placed.setAmount(moved);
                inventory.setItem(slot, placed);
                remaining -= moved;
            } else if (existing.isSimilar(item)) {
                int capacity = Math.min(existing.getMaxStackSize(), item.getMaxStackSize()) - existing.getAmount();
                if (capacity <= 0) continue;
                int moved = Math.min(remaining, capacity);
                existing.setAmount(existing.getAmount() + moved);
                inventory.setItem(slot, existing);
                remaining -= moved;
            }
            if (remaining <= 0) return;
        }

        if (remaining > 0) {
            ItemStack overflow = item.clone();
            overflow.setAmount(remaining);
            inventory.addItem(overflow);
        }
    }
}
