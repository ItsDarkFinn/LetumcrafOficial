package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Skull;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** Ejecuta el ritual defensivo sobre la cabeza-marcador de un jugador muerto. */
public final class ResurrectionRitual implements Listener {
    private final PermadeathPlugin plugin;
    private final RitualPenaltyManager penalties;
    private final NamespacedKey deathMarkerKey;
    private final NamespacedKey playerIdKey;
    private final Map<UUID, RitualSession> sessionsByTarget = new HashMap<>();
    private final Map<UUID, RitualSession> sessionsByParticipant = new HashMap<>();

    public ResurrectionRitual(PermadeathPlugin plugin, RitualPenaltyManager penalties) {
        this.plugin = plugin;
        this.penalties = penalties;
        this.deathMarkerKey = new NamespacedKey(plugin, "death_marker");
        this.playerIdKey = new NamespacedKey(plugin, "death_marker_player");
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void cancelAllForShutdown() {
        for (RitualSession session : List.copyOf(sessionsByTarget.values())) {
            cancel(session, null, "El servidor se está apagando. El Núcleo fue devuelto.", false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseSeal(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        Player activator = event.getPlayer();
        ItemStack held = event.getHand() == EquipmentSlot.HAND
                ? activator.getInventory().getItemInMainHand()
                : activator.getInventory().getItemInOffHand();
        if (!plugin.resurrectionSealItem().isSeal(held)) return;

        event.setCancelled(true);
        if (!plugin.getConfig().getBoolean("resurrection-ritual.enabled", true)) {
            tell(activator, "&cEl ritual de resurrección está desactivado.");
            return;
        }
        Material clickedType = event.getClickedBlock().getType();
        if (clickedType != Material.PLAYER_HEAD && clickedType != Material.PLAYER_WALL_HEAD) {
            tell(activator, "&cUsa el Sello sobre la cabeza que marca una muerte.");
            return;
        }
        if (!(event.getClickedBlock().getState() instanceof Skull skull)) return;
        Byte marker = skull.getPersistentDataContainer().get(deathMarkerKey, PersistentDataType.BYTE);
        String uuidValue = skull.getPersistentDataContainer().get(playerIdKey, PersistentDataType.STRING);
        if (marker == null || marker != (byte) 1 || uuidValue == null) {
            tell(activator, "&cEsa cabeza no es un marcador de muerte de Letumcraft.");
            return;
        }

        UUID targetId;
        try {
            targetId = UUID.fromString(uuidValue);
        } catch (IllegalArgumentException exception) {
            tell(activator, "&cEl marcador no tiene un UUID válido.");
            return;
        }
        if (!plugin.deaths().isDead(targetId)) {
            tell(activator, "&cEse jugador ya no está en el registro de eliminados.");
            return;
        }
        if (plugin.deaths().hasBeenRevived(targetId.toString())) {
            tell(activator, "&cEse jugador ya recibió su única resurrección.");
            return;
        }
        if (sessionsByTarget.containsKey(targetId)) {
            tell(activator, "&cYa hay un ritual en curso para esa muerte.");
            return;
        }
        if (sessionsByParticipant.containsKey(activator.getUniqueId())) {
            tell(activator, "&cYa participas en otro ritual.");
            return;
        }

        Location center = event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);
        double radius = getRadius();
        List<Player> nearbyParticipants = findNearbyParticipants(activator, center, radius);
        if (nearbyParticipants.size() < 2) {
            tell(activator, "&cNecesitas a otro jugador vivo a menos de " + radius + " bloques.");
            return;
        }

        DeathRegistry.DeadPlayer target = plugin.deaths().findDead(targetId.toString());
        if (target == null) {
            tell(activator, "&cNo encontré la muerte asociada a esa cabeza.");
            return;
        }
        consumeOne(activator, event.getHand());
        List<UUID> participantIds = nearbyParticipants.stream().map(Player::getUniqueId).toList();
        RitualSession session = new RitualSession(targetId, target.name(), center,
                activator.getUniqueId(), participantIds, getDuration());
        sessionsByTarget.put(targetId, session);
        for (UUID participant : session.participants) sessionsByParticipant.put(participant, session);
        for (Player participant : nearbyParticipants) {
            if (participant.equals(activator)) {
                tell(participant, "&5Iniciaste el ritual por &f" + target.name()
                        + "&5. Al menos dos participantes deben quedarse cerca durante &e"
                        + session.durationSeconds + " segundos&5.");
            } else {
                tell(participant, "&5Participas en el ritual de &f" + target.name()
                        + "&5. Quédate cerca; si el grupo baja de dos, el ritual se cancela.");
            }
        }
        spawnWave(session, 1);
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
    }

    private void tick(RitualSession session) {
        if (sessionsByTarget.get(session.targetId) != session) return;
        if (!plugin.deaths().isDead(session.targetId)) {
            cancel(session, null, "El objetivo ya no está eliminado. El ritual se canceló.", false);
            return;
        }
        if (!isDeathMarker(session.center)) {
            cancel(session, null, "El marcador de muerte desapareció y el ritual se interrumpió.", false);
            return;
        }

        List<UUID> deadParticipants = new ArrayList<>();
        List<UUID> presentParticipants = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (UUID participantId : session.participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant == null) {
                session.departedAt.putIfAbsent(participantId, now);
            } else if (participant.isDead() || participant.getGameMode() == GameMode.SPECTATOR
                    || plugin.deaths().isDead(participantId)) {
                deadParticipants.add(participantId);
            } else if (!isWithin(participant, session.center, getRadius())) {
                session.departedAt.putIfAbsent(participantId, now);
            } else {
                session.departedAt.remove(participantId);
                presentParticipants.add(participantId);
            }
        }
        for (UUID deadParticipant : deadParticipants) {
            session.participants.remove(deadParticipant);
            session.departedAt.remove(deadParticipant);
            sessionsByParticipant.remove(deadParticipant, session);
        }
        if (presentParticipants.size() < 2) {
            UUID leaverId = session.departedAt.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            cancel(session, leaverId, "El grupo se quedó con menos de dos participantes cerca y el ritual se interrumpió.", leaverId != null);
            return;
        }

        session.ticksLeft--;
        int waveInterval = getWaveInterval();
        int waveLimit = getWaveLimit();
        if (session.wavesSpawned < waveLimit
                && session.ticksLeft <= (session.durationSeconds - session.wavesSpawned * waveInterval) * 20) {
            spawnWave(session, session.wavesSpawned + 1);
        }
        if (session.ticksLeft <= 0) {
            complete(session);
            return;
        }
        int secondsLeft = (session.ticksLeft + 19) / 20;
        if (secondsLeft != session.lastShownSecond) {
            session.lastShownSecond = secondsLeft;
            for (UUID participantId : session.participants) {
                Player participant = plugin.getServer().getPlayer(participantId);
                if (participant != null) {
                    participant.sendActionBar(ChatColor.LIGHT_PURPLE + "Ritual de resurrección: "
                            + secondsLeft + "s — no te alejes");
                }
            }
        }
    }

    private boolean isDeathMarker(Location center) {
        Block block = center.getBlock();
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return false;
        if (!(block.getState() instanceof Skull skull)) return false;
        Byte marker = skull.getPersistentDataContainer().get(deathMarkerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void complete(RitualSession session) {
        if (!plugin.deaths().revive(session.targetName)) {
            cancel(session, null, "No se pudo completar la resurrección. El Núcleo fue devuelto.", false);
            return;
        }
        clearWaveMobs(session);
        close(session);
        Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                + "&dEl ritual tuvo éxito: &f" + session.targetName
                + " &dpuede volver a Letumcraft al conectarse. Regresará sin inventario."));
        for (UUID participantId : session.participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant != null) participant.sendTitle("§5Ritual completado", "§d" + session.targetName + " volverá al conectarse", 5, 50, 10);
        }
    }

    private void cancel(RitualSession session, UUID leaverId, String reason, boolean penalizeLeaver) {
        if (sessionsByTarget.get(session.targetId) != session) return;
        clearWaveMobs(session);
        close(session);
        refundCore(session);
        if (penalizeLeaver && leaverId != null) {
            String leaverName = plugin.getServer().getOfflinePlayer(leaverId).getName();
            penalties.penalize(leaverId, leaverName == null ? leaverId.toString() : leaverName);
        }
        for (UUID participantId : session.participants) {
            Player participant = plugin.getServer().getPlayer(participantId);
            if (participant != null) tell(participant, "&c" + reason + " &7El Sello y las esquirlas se perdieron; el Núcleo se devolvió.");
        }
        Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                + "&cEl ritual para &f" + session.targetName + " &cfue cancelado."));
    }

    private void close(RitualSession session) {
        if (session.task != null) session.task.cancel();
        sessionsByTarget.remove(session.targetId, session);
        for (UUID participantId : session.participants) sessionsByParticipant.remove(participantId, session);
    }

    private void spawnWave(RitualSession session, int waveNumber) {
        List<EntityType> types = switch (Math.min(waveNumber, 3)) {
            case 1 -> List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER);
            case 2 -> List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON,
                    EntityType.SKELETON, EntityType.SPIDER);
            default -> List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON,
                    EntityType.SKELETON, EntityType.SPIDER, EntityType.SPIDER);
        };
        List<Player> targets = session.participants.stream().map(plugin.getServer()::getPlayer)
                .filter(player -> player != null && isAlive(player)).toList();
        if (targets.isEmpty()) return;
        for (EntityType type : types) {
            Location spawnLocation = findWaveSpawnLocation(session.center);
            if (spawnLocation == null) continue;
            Entity entity = session.center.getWorld().spawnEntity(spawnLocation, type);
            if (!(entity instanceof LivingEntity mob)) {
                entity.remove();
                continue;
            }
            session.waveMobIds.add(mob.getUniqueId());
            if (mob instanceof Mob hostile) {
                hostile.setTarget(targets.get(ThreadLocalRandom.current().nextInt(targets.size())));
            }
        }
        session.wavesSpawned = waveNumber;
        for (Player target : targets) target.sendTitle("§5¡Oleada " + waveNumber + "!", "§7Protege la cabeza", 0, 30, 5);
        for (Player target : targets) tell(target, "&5Oleada &e" + waveNumber + "/" + getWaveLimit()
                + " &5— defiende el ritual.");
    }

    private Location findWaveSpawnLocation(Location center) {
        if (center.getWorld() == null) return null;
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            int radius = 8 + ThreadLocalRandom.current().nextInt(5);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = center.getWorld().getHighestBlockYAt(x, z);
            var ground = center.getWorld().getBlockAt(x, y, z);
            var feet = ground.getRelative(0, 1, 0);
            var head = ground.getRelative(0, 2, 0);
            if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isEmpty() || !head.isEmpty()) continue;
            return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    private void clearWaveMobs(RitualSession session) {
        for (UUID mobId : session.waveMobIds) {
            Entity mob = session.center.getWorld().getEntity(mobId);
            if (mob != null && mob.isValid()) mob.remove();
        }
        session.waveMobIds.clear();
    }

    private void refundCore(RitualSession session) {
        ItemStack core = plugin.eclipseCoreItem().createItem();
        Player initiator = plugin.getServer().getPlayer(session.initiatorId);
        if (initiator != null) {
            Map<Integer, ItemStack> overflow = initiator.getInventory().addItem(core);
            overflow.values().forEach(item -> dropRefund(item, initiator.getLocation(), initiator.getUniqueId()));
        } else {
            dropRefund(core, session.center, session.initiatorId);
        }
    }

    private void dropRefund(ItemStack item, Location location, UUID owner) {
        Item dropped = location.getWorld().dropItemNaturally(location, item);
        dropped.setOwner(owner);
        dropped.setPickupDelay(20);
    }

    private List<Player> findNearbyParticipants(Player activator, Location center, double radius) {
        List<Player> candidates = new ArrayList<>();
        candidates.add(activator);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.equals(activator) || !isAlive(player) || sessionsByParticipant.containsKey(player.getUniqueId())) continue;
            if (isWithin(player, center, radius)) candidates.add(player);
        }
        return candidates;
    }

    private boolean isAlive(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.deaths().isDead(player.getUniqueId());
    }

    private boolean isWithin(Player player, Location center, double radius) {
        return player.getWorld().equals(center.getWorld())
                && player.getLocation().distanceSquared(center) <= radius * radius;
    }

    private int getDuration() {
        return Math.max(1, Math.min(120, plugin.getConfig().getInt("resurrection-ritual.duration-seconds", 30)));
    }

    private int getWaveInterval() {
        return Math.max(1, Math.min(60,
                plugin.getConfig().getInt("resurrection-ritual.wave-interval-seconds", 10)));
    }

    private int getWaveLimit() {
        return Math.max(1, Math.min(5, plugin.getConfig().getInt("resurrection-ritual.waves", 3)));
    }

    private double getRadius() {
        double configured = plugin.getConfig().getDouble("resurrection-ritual.participant-radius", 5.0);
        return Math.max(1.0, Math.min(32.0, configured));
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item.getAmount() <= 1) {
            if (hand == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            else player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        } else item.setAmount(item.getAmount() - 1);
    }

    private void tell(Player player, String message) {
        player.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private static final class RitualSession {
        private final UUID targetId;
        private final String targetName;
        private final Location center;
        private final UUID initiatorId;
        private final List<UUID> participants;
        private final Map<UUID, Long> departedAt = new HashMap<>();
        private final int durationSeconds;
        private int ticksLeft;
        private int lastShownSecond;
        private int wavesSpawned;
        private final List<UUID> waveMobIds = new ArrayList<>();
        private BukkitTask task;

        private RitualSession(UUID targetId, String targetName, Location center, UUID initiatorId,
                              List<UUID> participants, int seconds) {
            this.targetId = targetId;
            this.targetName = targetName;
            this.center = center.clone();
            this.initiatorId = initiatorId;
            this.participants = new ArrayList<>(participants);
            this.durationSeconds = seconds;
            this.ticksLeft = seconds * 20;
            this.lastShownSecond = seconds;
        }
    }
}
