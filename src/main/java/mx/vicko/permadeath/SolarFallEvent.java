package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Bloque 2 — Caída del Sol (Bukkit).
 *
 * <p>Evento programado y acotado de ítems auténticos: anuncia, suelta Núcleos del Eclipse
 * reales (misma fábrica PDC) cerca del altar (o del spawn) y marca un solo intento en
 * {@code season-state.yml}. Sin fuego, sin bloques, sin spam y sin rellenar loot.
 * Las fuentes actuales (Botín/Cacería/Fortaleza) no se tocan y no se otorgan núcleos
 * gratuitos al conectarse.
 */
public final class SolarFallEvent {

    private final PermadeathPlugin plugin;

    public SolarFallEvent(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        try {
            return plugin.getConfig().getBoolean("season.solar-fall.enabled", true);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    public int configuredDay() {
        try {
            return Math.max(1, plugin.getConfig().getInt("season.solar-fall.day", 30));
        } catch (RuntimeException exception) {
            return 30;
        }
    }

    public int configuredCores() {
        try {
            return Math.min(9, Math.max(1, plugin.getConfig().getInt("season.solar-fall.cores", 3)));
        } catch (RuntimeException exception) {
            return 3;
        }
    }

    /**
     * Dispara una sola vez cuando el día alcanza lo configurado. Idempotente por
     * {@code solarFallDone} persistido (un solo intento aunque se reinicie).
     */
    public void checkAndRun(int day) {
        SeasonService seasons;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException exception) {
            return;
        }
        // Bloque 4 — tras el sellado no cae nada ni muta el estado (foto congelada).
        if (seasons == null || seasons.isSeasonEnded() || !isEnabled() || day < configuredDay() || seasons.isSolarFallDone()) {
            return;
        }
        seasons.markSolarFallDone();
        int cores = configuredCores();
        Location anchor = dropAnchor();
        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < cores; i++) {
            try {
                drops.add(plugin.eclipseCoreItem().createItem());
            } catch (RuntimeException exception) {
                break;
            }
        }
        if (anchor != null && anchor.getWorld() != null) {
            for (int i = 0; i < drops.size(); i++) {
                Location at = anchor.clone().add(i * 2 - drops.size(), 1, 0);
                anchor.getWorld().dropItemNaturally(at, drops.get(i));
            }
        }
        String where = anchor == null ? "el altar" : anchor.getWorld().getName()
                + " " + anchor.getBlockX() + ", " + anchor.getBlockY() + ", " + anchor.getBlockZ();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Caída del Sol] " + ChatColor.WHITE
                + "Fragmentos auténticos del eclipse cayeron cerca de " + where
                + " (" + drops.size() + " Núcleos).");
        plugin.getLogger().info("[Caída del Sol] día " + day + ": " + drops.size()
                + " Núcleos auténticos en " + where + " (intento único).");
    }

    private Location dropAnchor() {
        try {
            if (plugin.seasonAltar() != null && plugin.seasonAltar().altarLocation() != null) {
                return plugin.seasonAltar().altarLocation();
            }
        } catch (RuntimeException ignored) {
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        return worlds.get(0).getSpawnLocation();
    }

    public String status() {
        SeasonService seasons = null;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException ignored) {
        }
        boolean done = seasons != null && seasons.isSolarFallDone();
        return ChatColor.GOLD + "[Caída del Sol] " + ChatColor.WHITE + "día " + configuredDay()
                + ", " + configuredCores() + " Núcleos, " + (done ? "completada (intento único)" : "pendiente");
    }

    /** Sin otorgar nada al conectarse: los ítems solo caen en el evento. */
    public void onJoin(Player player) {
    }
}
