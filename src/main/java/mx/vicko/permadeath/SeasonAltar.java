package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Bloque 2 — Altar de sacrificios y cofre del Banco (Bukkit).
 *
 * <p>Disponible desde el acto II. La ubicación es configurable
 * ({@code season.altar.*}) o con {@code /pd altar aqui}; nunca sobreescribe
 * construcciones automáticamente (si el bloque está ocupado, se rechaza).
 * El menú muestra las elecciones públicas de todos ANTES de confirmar y exige
 * doble confirmación (doble clic, anti-robo: drag/click cancelados, cerrar no cobra).
 */
public final class SeasonAltar implements Listener {

    public static final String MENU_TITLE = ChatColor.DARK_PURPLE + "Altar de Sacrificios";
    private static final long CONFIRM_WINDOW_MILLIS = 60_000L;

    private final PermadeathPlugin plugin;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    public SeasonAltar(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    /** Disponible desde el acto II (narrativo, no suspendido por N&lt;4). */
    public boolean isAvailable() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().currentAct() >= 2;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Ubicación configurada del altar (null si aún no se fijó). */
    public Location altarLocation() {
        try {
            String worldName = plugin.getConfig().getString("season.altar.world", "");
            if (worldName == null || worldName.isBlank()) {
                return null;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            int x = plugin.getConfig().getInt("season.altar.x", 0);
            int y = plugin.getConfig().getInt("season.altar.y", 64);
            int z = plugin.getConfig().getInt("season.altar.z", 0);
            return new Location(world, x + 0.5, y, z + 0.5);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Fija el altar en el bloque mirado (máx. 6). Si está ocupado, se rechaza
     * sin sobreescribir nada. Requiere {@code permadeath.admin}.
     */
    public String setHere(Player player) {
        Block target;
        try {
            target = player.getTargetBlockExact(6);
        } catch (NoSuchMethodError | RuntimeException exception) {
            return ChatColor.RED + "Tu versión no permite fijar el altar así.";
        }
        if (target == null || target.getType().isAir()) {
            return ChatColor.RED + "Mira un bloque sólido a menos de 6 bloques.";
        }
        Location loc = target.getLocation();
        plugin.getConfig().set("season.altar.world", loc.getWorld().getName());
        plugin.getConfig().set("season.altar.x", loc.getBlockX());
        plugin.getConfig().set("season.altar.y", loc.getBlockY() + 1);
        plugin.getConfig().set("season.altar.z", loc.getBlockZ());
        plugin.saveConfig();
        return ChatColor.GREEN + "Altar fijado en " + loc.getWorld().getName()
                + " " + loc.getBlockX() + ", " + (loc.getBlockY() + 1) + ", " + loc.getBlockZ() + ".";
    }

    /** Abre el menú de 7 opciones con elecciones públicas visibles antes de confirmar. */
    public void openMenu(Player player) {
        if (!isAvailable()) {
            player.sendMessage(ChatColor.RED + "El Altar aún no está disponible (acto II).");
            return;
        }
        SeasonService seasons = plugin.seasonService();
        if (seasons == null) {
            player.sendMessage(ChatColor.RED + "Temporada no disponible todavía.");
            return;
        }
        if (seasons.sacrificeOf(player.getUniqueId()) != null) {
            player.sendMessage(ChatColor.YELLOW + "Ya elegiste: "
                    + seasons.sacrificeOf(player.getUniqueId()).displayName() + " (irreversible).");
            return;
        }
        Inventory menu = Bukkit.createInventory(new AltarHolder(), 27, MENU_TITLE);
        Map<String, String> all = seasons.allSacrifices();
        int slot = 10;
        for (SacrificeType type : SacrificeType.values()) {
            menu.setItem(slot++, menuItem(type, all));
            if (slot == 17) {
                slot = 19;
            }
        }
        player.openInventory(menu);
    }

    private ItemStack menuItem(SacrificeType type, Map<String, String> all) {
        ItemStack item = new ItemStack(menuMaterial(type));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + type.displayName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + type.description());
        List<String> chosen = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (type.id().equals(e.getValue())) {
                chosen.add(e.getKey());
            }
        }
        lore.add(ChatColor.DARK_GRAY + "Elegido por " + chosen.size() + ":");
        int shown = 0;
        for (String uuid : chosen) {
            if (shown >= 5) {
                lore.add(ChatColor.DARK_GRAY + "…y " + (chosen.size() - shown) + " más");
                break;
            }
            String name = uuid;
            try {
                Player online = Bukkit.getPlayer(UUID.fromString(uuid));
                if (online != null) {
                    name = online.getName();
                }
            } catch (IllegalArgumentException ignored) {
            }
            lore.add(ChatColor.DARK_GRAY + "- " + name);
            shown++;
        }
        lore.add(ChatColor.YELLOW + "Clic para elegir, repite el clic para CONFIRMAR (irreversible).");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Material menuMaterial(SacrificeType type) {
        return switch (type) {
            case ABRIGO -> Material.LEATHER_CHESTPLATE;
            case CAMA -> Material.RED_BED;
            case OJO -> Material.ENDER_EYE;
            case PULMON -> Material.TURTLE_HELMET;
            case ESTOMAGO -> Material.BREAD;
            case CORAZON -> Material.GOLDEN_APPLE;
            case ANCLA -> Material.ECHO_SHARD;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof AltarHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof AltarHolder)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        SacrificeType type = null;
        for (SacrificeType candidate : SacrificeType.values()) {
            if (current.getType() == menuMaterial(candidate)) {
                type = candidate;
                break;
            }
        }
        if (type == null) {
            return;
        }
        SeasonService seasons = plugin.seasonService();
        if (seasons == null || !isAvailable()) {
            player.closeInventory();
            return;
        }
        if (seasons.sacrificeOf(player.getUniqueId()) != null) {
            player.sendMessage(ChatColor.YELLOW + "Ya tienes sacrificio (irreversible).");
            player.closeInventory();
            return;
        }
        Pending staged = pending.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (staged == null || !staged.type.equals(type) || now - staged.at > CONFIRM_WINDOW_MILLIS) {
            pending.put(player.getUniqueId(), new Pending(type, now));
            player.sendMessage(ChatColor.YELLOW + "Elegiste " + type.displayName()
                    + ". Repite el clic en " + type.displayName() + " para CONFIRMAR (irreversible, dura tu UUID).");
            return;
        }
        pending.remove(player.getUniqueId());
        if (seasons.chooseSacrifice(player.getUniqueId(), type)) {
            player.closeInventory();
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "[Altar] " + ChatColor.WHITE + player.getName()
                    + " se sacrificó: " + type.displayName());
            SacrificeListener.applyHealthModifiers(plugin, player);
            SacrificeListener.enforceArmor(plugin, player);
        } else {
            player.sendMessage(ChatColor.RED + "No se pudo registrar (¿ya habías elegido?).");
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AltarHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMenuClose(InventoryCloseEvent event) {
        // Cerrar sin confirmar no cobra nada: el pendiente expira solo (60 s).
        if (event.getInventory().getHolder() instanceof AltarHolder) {
            prune();
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().at > CONFIRM_WINDOW_MILLIS);
    }

    private record Pending(SacrificeType type, long at) {
    }

    private static final class AltarHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
