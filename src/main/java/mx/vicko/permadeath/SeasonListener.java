package mx.vicko.permadeath;

import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Bloque 1 — Listener de temporada. Solo eventos reales, ningún stub de progreso:
 * portal de la Reserva (teleport exitoso al mundo Reserva), primer Núcleo
 * (recoger/click real con la marca {@code eclipse_core}), Fortaleza (abrir el
 * cofre marcado tras las 3 oleadas), Cacería (vía hook en su recompensa),
 * noche sin dormir (bed-enter + borde de día) y muestra de esporas (recoger
 * cartucho/filtro/máscara real). Sin escaneos periódicos.
 */
public final class SeasonListener implements Listener {
    private final PermadeathPlugin plugin;

    public SeasonListener(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    private SeasonService seasons() {
        try {
            return plugin.seasonService();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------ roster

    /**
     * Roster cerrado: con lista válida, quien no está dentro no entra
     * (KICK_WHITELIST). Con config vacía NO se bloquea a nadie (y menos a un
     * operador de forma irreversible): solo aviso en consola.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        SeasonService seasons = seasons();
        if (seasons == null || !seasons.isEnforcing() || !seasons.hasRoster()) {
            return;
        }
        if (seasons.roster().isMember(event.getPlayer().getUniqueId())) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST,
                ChatColor.RED + "No estás en el roster de la temporada " + seasons.seasonId() + ".");
    }

    // ------------------------------------------------------------------ objetivos

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        SeasonService seasons = seasons();
        if (seasons == null || event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        String to = event.getTo().getWorld().getName().toLowerCase(Locale.ROOT);
        if (!to.contains("reserva")) {
            return;
        }
        seasons.notifyPortal(event.getPlayer(), event.getTo().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (plugin.isOdysseyWorld(player.getWorld())) {
            return;
        }
        SeasonService seasons = seasons();
        if (seasons == null) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        if (isCore(item)) {
            seasons.notifyCore(player);
            return;
        }
        if (isEsporaItem(item)) {
            seasons.notifyEspora(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (plugin.isOdysseyWorld(player.getWorld())) {
            return;
        }
        SeasonService seasons = seasons();
        if (seasons == null) {
            return;
        }
        if (isCore(event.getCurrentItem()) || isCore(event.getCursor())) {
            seasons.notifyCore(player);
        }
    }

    /** El cofre de la Fortaleza solo se puede abrir tras las 3 oleadas: abrirlo = completarla. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (plugin.isOdysseyWorld(player.getWorld())) {
            return;
        }
        SeasonService seasons = seasons();
        if (seasons == null || !isFortressChest(event.getInventory())) {
            return;
        }
        seasons.notifyFortress(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (plugin.isOdysseyWorld(event.getPlayer().getWorld())) {
            return;
        }
        SeasonService seasons = seasons();
        if (seasons == null) {
            return;
        }
        seasons.noteBedEnter();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (plugin.isOdysseyWorld(event.getEntity().getWorld())) {
            return;
        }
        SeasonService seasons = seasons();
        if (seasons == null) {
            return;
        }
        seasons.onDeathStreak();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        SeasonService seasons = seasons();
        if (seasons == null) {
            return;
        }
        // Sin consumir pending-revival (lo gestiona RevivalAnchor): reevalúa racha, idempotente por día.
        seasons.onReviveStreak();
        // Objetivos colectivos sin actor: si el pago quedo pendiente, este join lo reintenta.
        try {
            seasons.retryPendingRewards();
        } catch (RuntimeException ignored) {
        }
        // Bloque 4 — fija el primer día visto por UUID (días sobrevividos por hook, sin escaneos).
        try {
            seasons.notePlayerSeen(event.getPlayer().getUniqueId(), seasons.currentDay());
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isCore(ItemStack item) {
        try {
            return plugin.eclipseCoreItem() != null && plugin.eclipseCoreItem().isCore(item);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Cartucho/filtro/máscara de esporas real (por nombre visible, con códigos de color). */
    private boolean isEsporaItem(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().getDisplayName() == null) {
            return false;
        }
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
        return name.contains("cartucho") || name.contains("espora") || name.contains("spore")
                || name.contains("filtro") || name.contains("filter") || name.contains("cartridge")
                || name.contains("mascara") || name.contains("m\u00e1scara") || name.contains("antigas");
    }

    private boolean isFortressChest(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        var holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return isMarkedFortressChest(chest);
        }
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            return (doubleChest.getLeftSide() instanceof Chest left && isMarkedFortressChest(left))
                    || (doubleChest.getRightSide() instanceof Chest right && isMarkedFortressChest(right));
        }
        return false;
    }

    private boolean isMarkedFortressChest(Chest chest) {
        try {
            return chest.getPersistentDataContainer().getOrDefault(
                    plugin.fortressChestKey(), PersistentDataType.BYTE, (byte) 0) == (byte) 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
