package mx.vicko.permadeath;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Adds End City elytra guardians and invisible charged creepers from day 35. */
public final class EndThreatController implements Listener {
    private static final double DEFAULT_ARMORED_SHULKER_CHANCE = 0.25;

    private static final double ARMORED_SHULKER_HEALTH = 45.0;
    private static final double GUARDIAN_HEALTH = 60.0;

    private final PermadeathPlugin plugin;
    private final org.bukkit.NamespacedKey electricCreeperKey;
    private final org.bukkit.NamespacedKey armoredShulkerKey;
    private final org.bukkit.NamespacedKey guardianKey;
    private final org.bukkit.NamespacedKey frameTriggeredKey;
    private final org.bukkit.NamespacedKey frameUnlockedKey;
    private final org.bukkit.NamespacedKey guardianFrameKey;
    private boolean spawningGuardian;

    public EndThreatController(PermadeathPlugin plugin) {
        this.plugin = plugin;
        electricCreeperKey = new org.bukkit.NamespacedKey(plugin, "end_electric_creeper");
        armoredShulkerKey = new org.bukkit.NamespacedKey(plugin, "end_armored_shulker");
        guardianKey = new org.bukkit.NamespacedKey(plugin, "elytra_void_guardian");
        frameTriggeredKey = new org.bukkit.NamespacedKey(plugin, "elytra_guardian_triggered");
        frameUnlockedKey = new org.bukkit.NamespacedKey(plugin, "elytra_guardian_unlocked");
        guardianFrameKey = new org.bukkit.NamespacedKey(plugin, "elytra_guardian_frame");
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndermanSpawn(CreatureSpawnEvent event) {
        if (!plugin.isPhaseActive(35)
                || !plugin.getConfig().getBoolean("phases.35.end-threats.electric-creepers.enabled", true)
                || !(event.getEntity() instanceof org.bukkit.entity.Enderman enderman)
                || event.getLocation().getWorld().getEnvironment() != World.Environment.THE_END
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;

        // Un creeper por cada Enderman que el juego genere naturalmente en el End: ratio 1:1.
        int ratio = Math.max(1, plugin.getConfig().getInt(
                "phases.35.end-threats.electric-creepers.per-enderman", 1));
        for (int i = 0; i < ratio; i++) {
            plugin.getServer().getScheduler().runTask(plugin, () -> spawnElectricCreeper(enderman));
        }
    }

    private void spawnElectricCreeper(org.bukkit.entity.Enderman enderman) {
        if (!enderman.isValid() || !plugin.isPhaseActive(35)
                || !plugin.getConfig().getBoolean("phases.35.end-threats.electric-creepers.enabled", true)) return;

        Player target = enderman.getWorld().getPlayers().stream()
                .filter(player -> !player.isDead())
                .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(enderman.getLocation())))
                .orElse(null);
        if (target == null) return;

        Location spawn = findSafeSpawnNear(enderman.getLocation());
        Creeper creeper = (Creeper) enderman.getWorld().spawnEntity(spawn, EntityType.CREEPER);
        creeper.getPersistentDataContainer().set(electricCreeperKey, PersistentDataType.BYTE, (byte) 1);
        creeper.setPowered(true);
        creeper.setMaxFuseTicks(10); // medio segundo desde que empieza a cebarse
        creeper.setExplosionRadius(5);
        creeper.setRemoveWhenFarAway(true);
        creeper.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        creeper.setTarget(target);
        spawn.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, spawn.clone().add(0, .6, 0), 12, .3, .5, .3, .04);
        spawn.getWorld().playSound(spawn, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .45f, 1.9f);
    }

    private Location findSafeSpawnNear(Location origin) {
        World world = origin.getWorld();
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        int y = baseY + dy;
                        Block floor = world.getBlockAt(x, y - 1, z);
                        Block feet = world.getBlockAt(x, y, z);
                        Block head = world.getBlockAt(x, y + 1, z);
                        if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                            return new Location(world, x + .5, y, z + .5);
                        }
                    }
                }
            }
        }
        return origin.clone();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndCityShulkerSpawn(CreatureSpawnEvent event) {
        if (spawningGuardian || !plugin.isPhaseActive(35)
                || !plugin.getConfig().getBoolean("phases.35.end-threats.elytra-guardian.enabled", true)
                || event.getEntityType() != EntityType.SHULKER
                || event.getLocation().getWorld().getEnvironment() != World.Environment.THE_END) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                && reason != CreatureSpawnEvent.SpawnReason.DEFAULT
                && reason != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        double chance = plugin.getConfig().getDouble(
                "phases.35.end-threats.elytra-guardian.armored-shulker-chance", DEFAULT_ARMORED_SHULKER_CHANCE);
        if (ThreadLocalRandom.current().nextDouble() >= Math.max(0.0, Math.min(1.0, chance))) return;

        Shulker shulker = (Shulker) event.getEntity();
        shulker.getPersistentDataContainer().set(armoredShulkerKey, PersistentDataType.BYTE, (byte) 1);
        setHealth(shulker, ARMORED_SHULKER_HEALTH);
        shulker.setCustomName(ChatColor.DARK_PURPLE + "Shulker blindado");
        shulker.setCustomNameVisible(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onElytraFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !isElytraFrame(frame)
                || !isFeatureActive(frame.getWorld())) return;
        if (isFrameUnlocked(frame)) return;

        event.setCancelled(true);
        if (isMarked(frame, frameTriggeredKey)) {
            event.getPlayer().sendMessage(ChatColor.DARK_PURPLE + "El Custodio del Vacío aún protege esta élitras.");
            return;
        }
        activateGuardian(frame, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onElytraFrameBreak(HangingBreakEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !isElytraFrame(frame)
                || !isFeatureActive(frame.getWorld()) || isFrameUnlocked(frame)) return;

        event.setCancelled(true);
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player
                && !isMarked(frame, frameTriggeredKey)) {
            activateGuardian(frame, player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onElytraFrameHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !isElytraFrame(frame)
                || !isFeatureActive(frame.getWorld()) || isFrameUnlocked(frame)
                || !(event.getDamager() instanceof Player player)) return;

        event.setCancelled(true);
        if (!isMarked(frame, frameTriggeredKey)) activateGuardian(frame, player);
        else player.sendMessage(ChatColor.DARK_PURPLE + "El Custodio del Vacío aún protege esta élitras.");
    }

    private void activateGuardian(ItemFrame frame, Player triggerer) {
        PersistentDataContainer frameData = frame.getPersistentDataContainer();
        frameData.set(frameTriggeredKey, PersistentDataType.BYTE, (byte) 1);
        frame.setPersistent(true);

        Location spawn = findSafeSpawnNear(triggerer.getLocation());
        spawningGuardian = true;
        Shulker guardian;
        try {
            guardian = (Shulker) frame.getWorld().spawnEntity(spawn, EntityType.SHULKER);
        } catch (RuntimeException exception) {
            frameData.set(frameUnlockedKey, PersistentDataType.BYTE, (byte) 1);
            plugin.getLogger().warning("No se pudo crear el Custodio del Vacío; se liberó la élitras: "
                    + exception.getMessage());
            triggerer.sendMessage(ChatColor.RED + "No se pudo iniciar el encuentro; ya puedes tomar la élitras.");
            return;
        } finally {
            spawningGuardian = false;
        }

        guardian.getPersistentDataContainer().set(guardianKey, PersistentDataType.BYTE, (byte) 1);
        guardian.getPersistentDataContainer().set(guardianFrameKey, PersistentDataType.STRING,
                frame.getUniqueId().toString());
        guardian.setCustomName(ChatColor.DARK_PURPLE + "Custodio del Vacío");
        guardian.setCustomNameVisible(true);
        guardian.setRemoveWhenFarAway(false);
        guardian.setPeek(100);
        setHealth(guardian, GUARDIAN_HEALTH);
        guardian.setTarget(triggerer);

        frame.getWorld().playSound(spawn, Sound.ENTITY_SHULKER_AMBIENT, 1.2f, .55f);
        frame.getWorld().spawnParticle(Particle.PORTAL, spawn, 35, .8, .8, .8, .08);
        triggerer.sendMessage(ChatColor.LIGHT_PURPLE + "El Custodio del Vacío ha despertado. Derrotalo para tomar la élitras.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuardianDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Shulker guardian) || !isMarked(guardian, guardianKey)) return;

        String frameId = guardian.getPersistentDataContainer().get(guardianFrameKey, PersistentDataType.STRING);
        if (frameId == null) return;
        Entity linked = Bukkit.getEntity(UUID.fromString(frameId));
        if (!(linked instanceof ItemFrame frame) || !frame.isValid()) return;

        frame.getPersistentDataContainer().set(frameUnlockedKey, PersistentDataType.BYTE, (byte) 1);
        frame.getWorld().playSound(frame.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, .8f, 1.4f);
        for (Player player : frame.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(frame.getLocation()) <= 96 * 96) {
                player.sendMessage(ChatColor.GREEN + "El Custodio cayó. La élitras ya es tuya.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElectricCreeperExplosion(EntityExplodeEvent event) {
        if (event.getEntity() instanceof Creeper creeper && isMarked(creeper, electricCreeperKey)) {
            // Conserva la explosión sobre entidades, pero no destruye ciudades ni la nave con la élitras.
            event.blockList().clear();
            event.setYield(0);
        }
    }

    private boolean isFeatureActive(World world) {
        return world.getEnvironment() == World.Environment.THE_END
                && plugin.isPhaseActive(35)
                && plugin.getConfig().getBoolean("phases.35.end-threats.elytra-guardian.enabled", true);
    }

    private boolean isElytraFrame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        return item != null && item.getType() == Material.ELYTRA;
    }

    private boolean isFrameUnlocked(ItemFrame frame) {
        return isMarked(frame, frameUnlockedKey);
    }

    private boolean isMarked(Entity entity, org.bukkit.NamespacedKey key) {
        Byte marker = entity.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void setHealth(org.bukkit.entity.LivingEntity entity, double health) {
        if (entity.getAttribute(Attribute.MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
            entity.setHealth(health);
        }
    }
}
