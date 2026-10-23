package mx.vicko.permadeath.odyssey;

import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import mx.vicko.permadeath.PermadeathPlugin;

/**
 * Eventos propios de La Falla. Todos los métodos comprueban primero el mundo
 * antes de tocar entidades, bloques, jugadores o estado del plugin.
 */
public final class OdysseyListener implements Listener {
    private static final int ENTRY_Y = -60;
    private static final double PHANTOM_KNOCKBACK = 3.0;
    private static final int BLINDNESS_TICKS = 20;

    private final PermadeathPlugin plugin;
    private final OdysseyManager odyssey;
    private final Set<UUID> pendingChorusEntry = ConcurrentHashMap.newKeySet();

    public OdysseyListener(PermadeathPlugin plugin, OdysseyManager odyssey) {
        this.plugin = plugin;
        this.odyssey = odyssey;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.CHORUS_FRUIT) {
            return;
        }
        Player player = event.getPlayer();
        if (!isEntryPad(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        pendingChorusEntry.add(playerId);
        // La fruta puede no encontrar un destino vanilla en el vacío y, por
        // tanto, no disparar PlayerTeleportEvent. Este fallback garantiza la
        // entrada sin duplicarla si el evento sí llegó a ejecutarse.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (pendingChorusEntry.remove(playerId)) {
                enter(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChorusTeleport(PlayerTeleportEvent event) {
        if (!isChorusFruitTeleport(event.getCause())) {
            return;
        }
        Player player = event.getPlayer();
        if (!pendingChorusEntry.remove(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> enter(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!odyssey.isOdyssey(player.getWorld())) {
            return;
        }

        String reason = odyssey.banReason();
        try {
            player.ban(reason, (Date) null, "La Falla de la Odisea", false);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "No se pudo registrar el ban permanente de " + player.getName()
                            + " tras morir en La Falla.", exception);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(player.getUniqueId());
            if (online != null) {
                online.kickPlayer(reason);
            }
        });
        event.setDeathMessage(player.getName() + " se perdió en La Falla de la Odisea.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET) {
            return;
        }
        Block block = event.getBlock();
        if (block == null || !odyssey.isOdyssey(block.getWorld())) {
            return;
        }

        event.setCancelled(true);
        World world = block.getWorld();
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        world.playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.8f);
        world.spawnParticle(Particle.CLOUD, location, 20, 0.3, 0.25, 0.3, 0.02);
        world.spawnParticle(Particle.SMOKE, location, 12, 0.25, 0.2, 0.25, 0.01);
        event.getPlayer().sendActionBar(Component.text("El agua se evapora...", NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!odyssey.isOdyssey(event.getLocation().getWorld())) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        // Los tres tipos exclusivos se crean con CUSTOM. /summon se permite
        // para administración; todo lo demás (natural, spawner, huevo, etc.)
        // queda bloqueado.
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!odyssey.isOdyssey(event.getEntity().getWorld())) {
            return;
        }

        if (event.getDamager() instanceof Phantom phantom
                && phantom.getScoreboardTags().contains(OdysseyTags.PHANTOM)
                && event.getEntity() instanceof Player victim) {
            event.setCancelled(true);
            event.setDamage(0.0);
            Vector push = victim.getLocation().toVector().subtract(phantom.getLocation().toVector());
            if (push.lengthSquared() < 0.0001) {
                push = victim.getLocation().getDirection().clone();
            }
            push.setY(0.0).normalize().multiply(PHANTOM_KNOCKBACK).setY(1.0);
            victim.setVelocity(push);
            victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.65f);
            return;
        }

        if (event.getDamager() instanceof Enderman enderman
                && enderman.getScoreboardTags().contains(OdysseyTags.ENDERMAN)
                && event.getEntity() instanceof Player victim) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, BLINDNESS_TICKS, 0, false, true, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (odyssey.isOdyssey(event.getBlock().getWorld())) {
            odyssey.ephemeralBlocks().cancel(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!odyssey.isOdyssey(event.getBlock().getWorld())) {
            return;
        }
        for (Block block : event.blockList()) {
            odyssey.ephemeralBlocks().cancel(block);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingChorusEntry.remove(event.getPlayer().getUniqueId());
        if (odyssey.gravity() != null) {
            odyssey.gravity().clear(event.getPlayer());
        }
    }

    private void enter(Player player) {
        if (!plugin.isEnabled() || !odyssey.isReady()
                || !player.isOnline() || player.isDead()) {
            return;
        }
        Location origin = player.getLocation().clone();
        player.playSound(origin, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.75f);
        player.getWorld().spawnParticle(Particle.END_ROD, origin.add(0.0, 1.0, 0.0),
                18, 0.25, 0.5, 0.25, 0.01);
        if (!odyssey.teleport(player)) {
            plugin.getLogger().warning("No se pudo teletransportar a " + player.getName()
                    + " a La Falla de la Odisea.");
        }
    }

    @SuppressWarnings("removal")
    private static boolean isChorusFruitTeleport(PlayerTeleportEvent.TeleportCause cause) {
        // Paper 26.3 marca CHORUS_FRUIT como obsoleto y añade CONSUMABLE_EFFECT.
        // Se aceptan ambos para cubrir servidores antiguos y nuevos.
        return cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT
                || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT;
    }

    private boolean isEntryPad(Player player) {
        if (player == null || !player.isOnline() || !player.isOnGround()) {
            return false;
        }
        World world = player.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL
                || !world.getKey().equals(NamespacedKey.minecraft("overworld"))
                || odyssey.isOdyssey(world)) {
            return false;
        }
        Location location = player.getLocation();
        Block below = location.clone().subtract(0.0, 1.0, 0.0).getBlock();
        if (below.getType() != Material.CRYING_OBSIDIAN) {
            return false;
        }
        return below.getY() == ENTRY_Y || location.getBlockY() == ENTRY_Y;
    }
}
