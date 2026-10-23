package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bloque 3 — La Fosa: runs de 10–15 min (default 12) en un recinto contenido
 * en la Reserva, solo para eliminados del roster.
 *
 * <p>1 Chispa por run completado (ledger, no inventario). Sin anuncios globales:
 * solo mensajes privados. Objetivo real dentro del recinto (oleadas: 8 bajas
 * de mobs de la Fosa antes del tiempo); AFK/alt-tab no completa nada (sin
 * bajas no hay recompensa; 3 min sin acción = fallo). Salida/desconexión =
 * fallo controlado + cleanup; restart = cancelación sin recompensa (nunca se
 * duplica). El muerto solo existe dentro de la Fosa: login permitido, pero
 * teleport/contención, sin cofres, sin drops/pickup, sin PvP, sin portales,
 * sin comandos ajenos, sin farm (drops de la Fosa a cero).
 */
public final class PitController implements Listener {

    /** Radio del recinto (contención) y bajas exigidas por run. */
    public static final int RECINTO_RADIUS = 24;
    public static final int KILLS_NEEDED = 8;
    /** Sin acción 3 min = fallo (anti AFK/alt-tab). */
    public static final long AFK_MILLIS = 3L * 60L * 1000L;

    private final PermadeathPlugin plugin;
    private final Map<UUID, Run> active = new HashMap<>();
    private final Set<UUID> pluginTeleports = new HashSet<>();

    public PitController(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Cancela todo sin recompensa (restart/apagado: nunca duplica). */
    public void stop() {
        for (Run run : List.copyOf(active.values())) {
            endSilent(run);
        }
        active.clear();
    }

    public boolean hasActiveRun(UUID uuid) {
        return uuid != null && active.containsKey(uuid);
    }

    /** Inicio manual o automático: solo elegibles con cupo diario. */
    public String startRun(Player player) {
        if (player == null) {
            return "&cJugador no válido.";
        }
        SeasonService seasons = seasonService();
        if (seasons == null || !seasons.isSeasonActive()) {
            return "&cLa Fosa solo existe en temporada activa.";
        }
        // Bloque 4 — ENDED cierra runs nuevos (sin Chispa ni encierro post-final).
        if (seasons.isSeasonEnded()) {
            return "&cLa temporada ya terminó: la Fosa está cerrada. Mira el resultado con /pd final.";
        }
        if (!seasons.isPitEligible(player.getUniqueId())) {
            return "&cLa Fosa es solo para eliminados del roster.";
        }
        long today = PitLedger.epochDay(System.currentTimeMillis());
        if (!PitLedger.canEarn(seasons.pitEarnedOf(player.getUniqueId()),
                lastEarnDay(player.getUniqueId()), today)) {
            if (seasons.pitEarnedOf(player.getUniqueId()) >= PitLedger.MAX_EARNED_PER_SEASON) {
                return "&cCupo de temporada agotado (6 Chispas ganadas).";
            }
            return "&cYa ganaste tu Chispa de hoy. Vuelve mañana (día real).";
        }
        if (active.containsKey(player.getUniqueId())) {
            return "&cYa tienes una run en curso en La Fosa.";
        }
        Location center = recintoCenter();
        if (center == null) {
            return "&cLa Fosa no está disponible (Reserva sin mundo). Inténtalo de nuevo.";
        }
        teleportLocked(player, center);
        player.setGameMode(GameMode.ADVENTURE);
        Run run = new Run(player.getUniqueId(), center,
                System.currentTimeMillis(), seasons.pitRunMinutes() * 60L * 1000L);
        active.put(player.getUniqueId(), run);
        spawnWave(run, 4);
        run.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(run), 20L, 20L);
        player.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                + "Run iniciada: consigue " + KILLS_NEEDED + " bajas antes de "
                + seasons.pitRunMinutes() + " min. Sin huir del recinto.");
        return null;
    }

    /** Estado privado para /pd fosa estado (sin spam global). */
    public List<String> statusLines(UUID uuid) {
        List<String> lines = new ArrayList<>();
        SeasonService seasons = seasonService();
        if (seasons == null || !seasons.isSeasonActive() || uuid == null) {
            lines.add("&cLa Fosa solo existe en temporada activa.");
            return lines;
        }
        if (seasons.isSeasonEnded()) {
            lines.add("&cLa temporada terminó: la Fosa está cerrada. Mira el resultado con /pd final.");
            return lines;
        }
        int earned = seasons.pitEarnedOf(uuid);
        int balance = seasons.pitBalanceOf(uuid);
        int revivals = seasons.revivalsOf(uuid);
        lines.add("&4[Fosa] &fChispas: &e" + balance + " &7(ganadas " + earned + "/"
                + PitLedger.MAX_EARNED_PER_SEASON + ") &8• &fVueltas: &e" + revivals + "/"
                + PitLedger.MAX_REVIVALS + " &8• &fSiguiente: &e" + PitLedger.nextCost(revivals));
        Run run = active.get(uuid);
        if (run != null) {
            lines.add("&7Run en curso: &e" + run.kills + "/" + KILLS_NEEDED + " bajas");
        }
        RevivalPolicy.Decision decision = seasons.evaluateRevival(uuid, true, System.currentTimeMillis());
        if (decision.allowed()) {
            lines.add("&aPuedes volver por vía " + decision.path() + " (coste " + decision.cost() + ").");
        } else if (!decision.missing().isEmpty()) {
            lines.add("&cTe falta: " + String.join(" ", decision.missing()));
        }
        return lines;
    }

    // ------------------------------------------------------------ eventos

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SeasonService seasons = seasonService();
        if (seasons == null || !seasons.isSeasonActive()) {
            return;
        }
        // Escrow antes del guard de elegibilidad: un revivido con tx abierta tras
        // un crash ya está VIVO (no elegible) y necesita el commit aquí; después
        // del guard jamás se alcanzaría y la vuelta quedaría sin contar.
        try {
            seasons.reconcileReviveTx(player.getUniqueId());
        } catch (RuntimeException ignored) {
        }
        // Bloque 4 — tras el final no hay teleport ni encierro: el eliminado
        // entra normal y ve el resultado con /pd final.
        if (seasons.isSeasonEnded()) {
            player.sendMessage(ChatColor.GOLD + "[Fosa] " + ChatColor.WHITE
                    + "La temporada ya terminó: no hay runs. Mira el resultado con /pd final.");
            return;
        }
        if (!seasons.isPitEligible(player.getUniqueId())) {
            return;
        }
        if (seasons.shouldSignalSeasonEnd()) {
            plugin.getLogger().info("[Fosa] señal a B4: 0 vivos del roster ("
                    + player.getName() + " en Fosa, sin finalizar entre muerte y kick).");
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (seasons.isSeasonEnded()) {
                return;
            }
            if (!player.isOnline() || !seasons.isPitEligible(player.getUniqueId())) {
                return;
            }
            Location center = recintoCenter();
            if (center == null) {
                player.kickPlayer(ChatColor.DARK_RED + "[Fosa] "
                        + ChatColor.WHITE + "La Fosa no está disponible (Reserva sin mundo). "
                        + "Reintenta cuando el mundo esté cargado; tu progreso está guardado.");
                return;
            }
            if (!isInside(player.getLocation(), center)) {
                teleportLocked(player, center);
                player.setGameMode(GameMode.ADVENTURE);
            }
            if (!active.containsKey(player.getUniqueId())) {
                long today = PitLedger.epochDay(System.currentTimeMillis());
                if (PitLedger.canEarn(seasons.pitEarnedOf(player.getUniqueId()),
                        lastEarnDay(player.getUniqueId()), today)) {
                    startRun(player);
                } else {
                    player.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                            + "Esperas en La Fosa: sin cupo de Chispa hoy. El grupo debe hacer el ritual.");
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        failQuiet(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        failQuiet(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        failQuiet(event.getEntity().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Run run = active.get(event.getPlayer().getUniqueId());
        if (run == null) {
            if (!isContained(event.getPlayer().getUniqueId())) {
                return;
            }
            Location center = recintoCenter();
            if (center == null) {
                return;
            }
            if (!isInside(event.getTo(), center)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            run.lastAction = System.currentTimeMillis();
        }
        if (!isInside(event.getTo(), run.center)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                    + "El recinto te contiene: completa tu run aquí.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!isContained(event.getPlayer().getUniqueId())) {
            return;
        }
        if (pluginTeleports.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getPlayer() != null && isContained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        UUID damagerId = damagerId(event);
        UUID victimId = victimId(event);
        // Sin PvP que toque a un runner (ni entre runners ni contra externos).
        if (victimId != null && isContained(victimId)
                && event.getDamager() instanceof Player) {
            event.setCancelled(true);
            return;
        }
        if (damagerId != null && isContained(damagerId)
                && victimId != null && !isContained(victimId)
                && event.getEntity() instanceof Player) {
            event.setCancelled(true);
            return;
        }
        if (damagerId != null) {
            Run run = active.get(damagerId);
            if (run != null) {
                run.lastAction = System.currentTimeMillis();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        for (Run run : List.copyOf(active.values())) {
            if (run.mobs.remove(dead.getUniqueId())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
                run.kills++;
                run.lastAction = System.currentTimeMillis();
                Player runner = Bukkit.getPlayer(run.owner);
                if (runner != null && runner.isOnline()) {
                    runner.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                            + "Baja " + run.kills + "/" + KILLS_NEEDED + ".");
                }
                if (run.kills >= KILLS_NEEDED) {
                    complete(run);
                } else if (run.mobs.isEmpty()) {
                    spawnWave(run, 4);
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isContained(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isContained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChest(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isContained(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                    + "Sin equipos de cofres: tus bajas te dan la Chispa.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isContained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isContained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isContained(event.getPlayer().getUniqueId())) {
            return;
        }
        String msg = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (isAllowedWhileContained(msg)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                + "En la run solo /pd fosa, /pd temporada, /pd actos y /pd final.");
    }

    /**
     * Bloque 4 — comandos permitidos a un contenido de la Fosa. {@code /pd final}
     * es accesible a todo el roster incluidos muertos (pantalla del final).
     * Puro y testeable: recibe el mensaje ya en minúsculas.
     */
    static boolean isAllowedWhileContained(String lowerMessage) {
        if (lowerMessage == null) {
            return false;
        }
        return lowerMessage.startsWith("/pd fosa") || lowerMessage.startsWith("/pd temporada")
                || lowerMessage.startsWith("/pd actos") || lowerMessage.startsWith("/pd final")
                || lowerMessage.startsWith("/permadeath fosa") || lowerMessage.startsWith("/permadeath temporada")
                || lowerMessage.startsWith("/permadeath final");
    }

    // ------------------------------------------------------------ motor

    private void tick(Run run) {
        if (active.get(run.owner) != run) {
            return;
        }
        // Bloque 4 — el sellado cancela la run activa sin Chispa (no daña).
        try {
            SeasonService seasons = seasonService();
            if (seasons != null && seasons.isSeasonEnded()) {
                fail(run, "La temporada terminó: la run se cierra sin Chispa. Mira el resultado con /pd final.");
                return;
            }
        } catch (RuntimeException ignored) {
        }
        Player runner = Bukkit.getPlayer(run.owner);
        if (runner == null || !runner.isOnline()) {
            failQuiet(run.owner);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - run.startedAt >= run.timeoutMillis) {
            fail(run, "Tiempo agotado (" + (run.timeoutMillis / 60000) + " min): la run falló sin Chispa. Puedes reintentar hoy.");
            return;
        }
        if (now - run.lastAction >= AFK_MILLIS) {
            fail(run, "Sin acción 3 min: la run falló (AFK no gana Chispas). Muévete y lucha.");
        }
    }

    private void complete(Run run) {
        if (active.get(run.owner) != run) {
            return;
        }
        active.remove(run.owner);
        cleanup(run);
        SeasonService seasons = seasonService();
        Player runner = Bukkit.getPlayer(run.owner);
        boolean ended = false;
        try {
            ended = seasons != null && seasons.isSeasonEnded();
        } catch (RuntimeException ignored) {
        }
        boolean earned = !ended && seasons != null && seasons.tryEarnChispa(run.owner, System.currentTimeMillis());
        if (runner != null && runner.isOnline()) {
            if (earned) {
                runner.sendMessage(ChatColor.GOLD + "[Fosa] " + ChatColor.WHITE
                        + "¡Run completada! +1 Chispa (saldo " + seasons.pitBalanceOf(run.owner)
                        + ", ganadas " + seasons.pitEarnedOf(run.owner) + "/"
                        + PitLedger.MAX_EARNED_PER_SEASON + "). Espera el ritual del grupo.");
                try {
                    runner.getInventory().addItem(
                            plugin.chispaItem().createItem(run.owner, seasons.seasonId()));
                } catch (RuntimeException ignored) {
                }
            } else if (ended) {
                runner.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                        + "Run completada pero la temporada ya terminó: sin Chispa. Mira el resultado con /pd final.");
            } else {
                runner.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE
                        + "Run completada pero sin cupo (1/día, 6 por temporada): sin Chispa.");
            }
        }
    }

    private void fail(Run run, String reason) {
        if (active.get(run.owner) != run) {
            return;
        }
        active.remove(run.owner);
        cleanup(run);
        Player runner = Bukkit.getPlayer(run.owner);
        if (runner != null && runner.isOnline()) {
            runner.sendMessage(ChatColor.DARK_RED + "[Fosa] " + ChatColor.WHITE + reason);
        }
    }

    private void failQuiet(UUID uuid) {
        Run run = uuid == null ? null : active.remove(uuid);
        if (run != null) {
            cleanup(run);
        }
    }

    private void endSilent(Run run) {
        try {
            if (run.task != null) {
                run.task.cancel();
            }
        } catch (RuntimeException ignored) {
        }
        cleanup(run);
    }

    private void cleanup(Run run) {
        try {
            if (run.task != null) {
                run.task.cancel();
            }
        } catch (RuntimeException ignored) {
        }
        for (UUID mobId : List.copyOf(run.mobs)) {
            try {
                Entity entity = run.center.getWorld().getEntity(mobId);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            } catch (RuntimeException ignored) {
            }
        }
        run.mobs.clear();
    }

    private void spawnWave(Run run, int count) {
        World world = run.center.getWorld();
        if (world == null) {
            return;
        }
        EntityType[] kinds = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.HUSK};
        for (int i = 0; i < count && run.mobs.size() < 6; i++) {
            try {
                double angle = Math.random() * Math.PI * 2;
                double dist = 8 + Math.random() * 10;
                Location at = run.center.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
                at.setY(world.getHighestBlockAt(at).getY() + 1.0);
                EntityType kind = kinds[(int) (Math.random() * kinds.length)];
                Entity spawned = world.spawnEntity(at, kind);
                if (spawned instanceof Monster monster) {
                    monster.setTarget(Bukkit.getPlayer(run.owner));
                }
                run.mobs.add(spawned.getUniqueId());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Location recintoCenter() {
        World reserve = null;
        try {
            if (plugin.reservaPodrida() != null) {
                reserve = plugin.reservaPodrida().reserveWorld();
            }
        } catch (RuntimeException ignored) {
        }
        if (reserve == null) {
            for (World world : Bukkit.getWorlds()) {
                try {
                    if (plugin.reservaPodrida() != null && plugin.reservaPodrida().isReserve(world)) {
                        reserve = world;
                        break;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (reserve == null) {
            return null;
        }
        int x = 100;
        int z = 100;
        int y;
        try {
            y = ReservaPodrida.getReserveFloorHeight(x, z) + 1;
        } catch (RuntimeException exception) {
            y = reserve.getSpawnLocation().getBlockY() + 1;
        }
        return new Location(reserve, x + 0.5, y, z + 0.5);
    }

    private boolean isInside(Location location, Location center) {
        if (location == null || center == null || location.getWorld() == null
                || !location.getWorld().equals(center.getWorld())) {
            return false;
        }
        return location.distanceSquared(center) <= (double) RECINTO_RADIUS * RECINTO_RADIUS;
    }

    /**
     * Bloque 3 — contención ampliada: el muerto elegible sigue contenido
     * aunque no tenga run activa (sin cupo diario, run fallida o recinto en
     * transición). Sin run no hay centro propio: se usa el recinto global.
     */
    private boolean isContained(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (active.containsKey(uuid)) {
            return true;
        }
        try {
            SeasonService seasons = seasonService();
            // Bloque 4 — tras el final no hay contención: salida libre del recinto.
            return seasons != null && seasons.isSeasonActive() && !seasons.isSeasonEnded()
                    && seasons.isPitEligible(uuid);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Bloque 3 — libera al revivido: cierra la run sin penalización, restaura
     * SUPERVIVENCIA y lo lleva al spawn principal (igual que RevivalAnchor al
     * unirse). Llamar solo tras una vuelta registrada con éxito.
     */
    public void releaseRevived(Player player) {
        if (player == null) {
            return;
        }
        failQuiet(player.getUniqueId());
        try {
            player.setGameMode(GameMode.SURVIVAL);
        } catch (RuntimeException ignored) {
        }
        try {
            if (!plugin.getServer().getWorlds().isEmpty()) {
                player.teleport(plugin.getServer().getWorlds().getFirst().getSpawnLocation().clone());
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void teleportLocked(Player player, Location center) {
        pluginTeleports.add(player.getUniqueId());
        try {
            player.teleport(center);
        } finally {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> pluginTeleports.remove(player.getUniqueId()), 10L);
        }
    }

    private long lastEarnDay(UUID uuid) {
        try {
            SeasonService seasons = seasonService();
            if (seasons != null) {
                return seasons.state() == null ? -1L
                        : seasons.state().pitLastEarnDayOf(uuid);
            }
        } catch (RuntimeException ignored) {
        }
        return -1L;
    }

    private SeasonService seasonService() {
        try {
            return plugin.seasonService();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static UUID damagerId(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player.getUniqueId();
        }
        return null;
    }

    private static UUID victimId(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player) {
            return player.getUniqueId();
        }
        return null;
    }

    private static final class Run {
        private final UUID owner;
        private final Location center;
        private final long startedAt;
        private final long timeoutMillis;
        private long lastAction;
        private int kills;
        private final Set<UUID> mobs = new HashSet<>();
        private BukkitTask task;

        private Run(UUID owner, Location center, long startedAt, long timeoutMillis) {
            this.owner = owner;
            this.center = center;
            this.startedAt = startedAt;
            this.timeoutMillis = timeoutMillis;
            this.lastAction = startedAt;
        }
    }
}
