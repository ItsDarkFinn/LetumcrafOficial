package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.scheduler.BukkitTask;

/** Evento nocturno de cacería y botín raro de estructuras. */
public final class NightHuntEvent implements Listener {
    private static final int START_DAY = 5;
    private static final int FINAL_DAY = 110;
    private static final int ZOMBIES_REQUIRED = 5;
    private static final int SKELETONS_REQUIRED = 5;
    private static final long NIGHT_START = 13_000L;
    private static final long NIGHT_END = 23_000L;

    private final PermadeathPlugin plugin;
    private ActiveHunt activeHunt;
    private BukkitTask task;

    public NightHuntEvent(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 600L);
    }

    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        if (activeHunt != null) finish("El evento se cerró al apagar el servidor.");
    }

    /** Bloque 4 — tras el sellado no hay Cacería (ni auto ni manual en curso). */
    private boolean seasonEnded() {
        try {
            return plugin.seasonService() != null && plugin.seasonService().isSeasonEnded();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String startManualTest() {
        if (activeHunt != null) return "Ya hay una Cacería Nocturna en curso.";
        List<Player> players = eligiblePlayers();
        if (players.isEmpty()) return "Necesitas al menos un jugador vivo conectado para iniciar la prueba.";
        World world = players.getFirst().getWorld();
        long timeout = Math.max(60L, plugin.getConfig().getLong("random-events.test-duration-seconds", 600L));
        begin(world, players, true, timeout);
        return "Prueba iniciada para " + players.size() + " jugador(es). Termina al completar la misión o tras "
                + (timeout / 60) + " minuto(s).";
    }

    public String cancelManual() {
        if (activeHunt == null) return "No hay una Cacería Nocturna activa.";
        finish("La Cacería Nocturna fue cancelada por un operador.");
        return "Evento cancelado.";
    }

    public String getStatus() {
        if (activeHunt == null) {
            return "No hay evento activo. Día " + plugin.days().getCurrentDay()
                    + "; siguiente ventana automática: " + nextEligibleDay() + ".";
        }
        return "Cacería Nocturna " + (activeHunt.manual ? "(prueba)" : "(automática)")
                + ": " + activeHunt.progress.size() + " participantes, "
                + activeHunt.completed.size() + " completaron. " + progressSummary();
    }

    @EventHandler(ignoreCancelled = true)
    public void onParticipantKill(EntityDeathEvent event) {
        if (activeHunt == null) return;
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        PlayerProgress progress = activeHunt.progress.get(killer.getUniqueId());
        if (progress == null || activeHunt.completed.contains(killer.getUniqueId())) return;

        if (victim.getType() == EntityType.ZOMBIE) {
            progress.zombies = Math.min(ZOMBIES_REQUIRED, progress.zombies + 1);
        } else if (victim.getType() == EntityType.SKELETON) {
            progress.skeletons = Math.min(SKELETONS_REQUIRED, progress.skeletons + 1);
        } else return;

        killer.sendActionBar(ChatColor.LIGHT_PURPLE + "Cacería: Zombis " + progress.zombies + "/5"
                + " · Esqueletos " + progress.skeletons + "/5");
        if (progress.zombies >= ZOMBIES_REQUIRED && progress.skeletons >= SKELETONS_REQUIRED) {
            reward(killer);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDungeonLoot(LootGenerateEvent event) {
        double chance = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("random-events.dungeon-core-chance", 0.05)));
        if (!plugin.getConfig().getBoolean("random-events.dungeon-core-enabled", true)
                || !isDungeonLoot(event.getLootTable()) || !chance(chance)) return;
        List<ItemStack> loot = new ArrayList<>(event.getLoot());
        loot.add(plugin.eclipseCoreItem().createItem());
        event.setLoot(loot);
    }

    private void tick() {
        if (activeHunt != null) {
            if (seasonEnded()) {
                finish("La temporada terminó; la Cacería queda cerrada.");
                return;
            }
            if (activeHunt.manual) {
                if (System.currentTimeMillis() >= activeHunt.expiresAtMillis) finish("La prueba terminó por tiempo.");
            } else if (!isNight(activeHunt.world)) {
                finish("Amaneció y terminó la Cacería Nocturna.");
            }
            return;
        }
        if (seasonEnded()) return;
        if (!plugin.getConfig().getBoolean("random-events.enabled", true)) return;
        int day = plugin.days().getCurrentDay();
        int effectiveStart = plugin.seasonService() != null ? plugin.seasonService().mapLegacyDay(START_DAY) : START_DAY;
        if (day < effectiveStart || day > FINAL_DAY || !isEligibleDay(day)) return;
        if (!passesSeasonGate(eligiblePlayers())) return;
        World nightWorld = Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL && isNight(world))
                .findFirst().orElse(null);
        if (nightWorld == null) return;
        double probability = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("random-events.night-check-chance", 0.15)));
        if (chance(probability)) {
            List<Player> players = eligiblePlayers();
            if (passesSeasonGate(players)) {
                plugin.getConfig().set("random-events.last-auto-event-day", day);
                plugin.saveConfig();
                begin(nightWorld, players, false, 0L);
            }
        }
    }

    private void begin(World world, List<Player> players, boolean manual, long timeoutSeconds) {
        Map<UUID, PlayerProgress> progress = new HashMap<>();
        for (Player player : players) progress.put(player.getUniqueId(), new PlayerProgress());
        long expiresAt = manual ? System.currentTimeMillis() + timeoutSeconds * 1000L : Long.MAX_VALUE;
        activeHunt = new ActiveHunt(world, progress, manual, expiresAt);
        String notice = "§5§lLETUMCRAFT §d— Cacería Nocturna"
                + "\n§fCada participante: elimina 5 zombis y 5 esqueletos para ganar una Esquirla de Eclipse.";
        if (manual) notice += "\n§8Evento de prueba iniciado por un operador.";
        Bukkit.broadcastMessage(notice);
    }

    private void reward(Player player) {
        UUID id = player.getUniqueId();
        if (activeHunt == null || !activeHunt.completed.add(id)) return;
        ItemStack shard = plugin.eclipseShardItem().createItem();
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(shard);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendTitle("§d¡Cacería completada!", "§fRecibiste una Esquirla de Eclipse", 5, 50, 10);
        Bukkit.broadcastMessage("§d" + player.getName() + " completó la Cacería Nocturna y obtuvo una Esquirla.");
        try {
            if (plugin.seasonService() != null) plugin.seasonService().notifyNightHunt(player);
        } catch (RuntimeException ignored) {
        }
        if (activeHunt.completed.containsAll(activeHunt.progress.keySet())) finish("Todos los participantes completaron la misión.");
    }

    private void finish(String reason) {
        ActiveHunt finished = activeHunt;
        if (finished == null) return;
        activeHunt = null;
        Bukkit.broadcastMessage("§8[§5Letumcraft§8] §7" + reason + " §f(" + finished.completed.size()
                + "/" + finished.progress.size() + " completaron).");
    }

    private String progressSummary() {
        if (activeHunt == null) return "";
        return activeHunt.progress.entrySet().stream().limit(3).map(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayerProgress progress = entry.getValue();
            return (player == null ? "desconectado" : player.getName()) + " "
                    + progress.zombies + "/5Z " + progress.skeletons + "/5E";
        }).reduce((first, second) -> first + "; " + second).orElse("");
    }

    private List<Player> eligiblePlayers() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                    && !plugin.deaths().isDead(player.getUniqueId())) players.add(player);
        }
        return players;
    }

    private boolean isEligibleDay(int day) {
        int lastDay = plugin.getConfig().getInt("random-events.last-auto-event-day", 0);
        int interval = day <= 24 ? 2 : 3;
        return lastDay == 0 || day - lastDay >= interval;
    }

    private int nextEligibleDay() {
        int day = plugin.days().getCurrentDay();
        if (day < START_DAY) return START_DAY;
        if (day > FINAL_DAY) return -1;
        int lastDay = plugin.getConfig().getInt("random-events.last-auto-event-day", 0);
        if (lastDay == 0) return day;
        return lastDay + (lastDay <= 24 ? 2 : 3);
    }

    private int minimumPlayers() {
        return Math.max(1, plugin.getConfig().getInt("random-events.minimum-online-players", 15));
    }

    /** Gate único de temporada (M roster, 40% o minimum-online-players si mayor); manual bypass. */
    private boolean passesSeasonGate(List<Player> players) {
        try {
            SeasonService seasons = plugin.seasonService();
            if (seasons != null) return seasons.passesGateForPlayers(players);
        } catch (RuntimeException ignored) {
        }
        return players.size() >= minimumPlayers();
    }

    private boolean isNight(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        long time = world.getTime();
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    private boolean isDungeonLoot(LootTable table) {
        if (table == null) return false;
        NamespacedKey key = table.getKey();
        if (!key.getNamespace().equals("minecraft")) return false;
        String path = key.getKey();
        if (path.startsWith("chests/")) path = path.substring("chests/".length());
        return path.equals("simple_dungeon") || path.equals("abandoned_mineshaft")
                || path.equals("desert_pyramid") || path.equals("jungle_temple")
                || path.equals("igloo_chest") || path.equals("woodland_mansion")
                || path.equals("pillager_outpost") || path.equals("nether_bridge")
                || path.equals("end_city_treasure") || path.equals("ancient_city")
                || path.equals("buried_treasure") || path.equals("ruined_portal")
                || path.startsWith("stronghold_") || path.startsWith("trial_chambers_")
                || path.startsWith("bastion_") || path.startsWith("shipwreck_")
                || path.startsWith("underwater_ruin_");
    }

    private boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    private static final class PlayerProgress {
        private int zombies;
        private int skeletons;
    }

    private static final class ActiveHunt {
        private final World world;
        private final Map<UUID, PlayerProgress> progress;
        private final Set<UUID> completed = new HashSet<>();
        private final boolean manual;
        private final long expiresAtMillis;

        private ActiveHunt(World world, Map<UUID, PlayerProgress> progress, boolean manual, long expiresAtMillis) {
            this.world = world;
            this.progress = progress;
            this.manual = manual;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
