package mx.vicko.permadeath;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bloque 2 — Ventajas y costes reales de los sacrificios (Bukkit).
 *
 * <p>Cada opción se consume en eventos efectivos (nunca flags sin consumidor):
 * <ul>
 *   <li>Abrigo: excluye clima/frío (ácido bajo lluvia, náusea/lentitud en agua,
 *       lentitud en nieve/hielo, daño y acumulación de congelación), pero ningún otro daño.
 *       Detección a 32 por re-objetivo acotado.</li>
 *   <li>Cama: la exención de la explosión UHC vive en {@link DifficultyListener} (solo elegidos).</li>
 *   <li>Ojo: brillo (GLOWING, mecanismo vanilla que atraviesa paredes) a mobs en 16,
 *       acotado y con tope; prioridad hostil por re-objetivo.</li>
 *   <li>Pulmón: sin ahogo (aire) y −15% velocidad en tierra por modificador estable.</li>
 *   <li>Estómago: sin agotamiento (comida fija) y sin beneficios de poción
 *       (beber/salpicadura/nube); no bloquea otros ataques mágicos.</li>
 *   <li>Corazón: +8 HP por modificador estable y bloqueo de armadura superior a hierro
 *       en todas las rutas (clic, shift, drag, interactuar, dispensador, re-log).</li>
 *   <li>Ancla: guarda el punto de muerte y lo restaura una vez al revivir; el grupo paga
 *       1 Núcleo del banco. No concede vuelta gratis ni salta La Fosa.</li>
 * </ul>
 *
 * <p>Limitación informada: el brillo del Ojo depende del render del cliente (a través de
 * paredes sí, pero a distancia de render); no se simula ningún beneficio invisible:
 * si el cliente no renderiza, no hay información extra. Los re-objetivos están acotados
 * (radio + tope) y nunca hacen escaneos globales de mundos.
 */
public final class SacrificeListener implements Listener {

    private final PermadeathPlugin plugin;
    private BukkitTask glowTask;
    private BukkitTask detectTask;
    private BukkitTask armorTask;

    public SacrificeListener(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    /** Arranca re-aplicaciones y tareas acotadas (llamar una vez desde el plugin). */
    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyHealthModifiers(plugin, player);
            enforceArmor(plugin, player);
        }
        glowTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickOjoGlow, 40L, 40L);
        detectTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAbrigoDetect, 100L, 100L);
        // Red de seguridad para rutas que Paper no permite cancelar (clic derecho
        // desde la hotbar, cambios de armadura de otros plugins, etc.).
        armorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickArmor, 20L, 5L);
    }

    public void stop() {
        if (glowTask != null) {
            glowTask.cancel();
            glowTask = null;
        }
        if (detectTask != null) {
            detectTask.cancel();
            detectTask = null;
        }
        if (armorTask != null) {
            armorTask.cancel();
            armorTask = null;
        }
    }

    private boolean has(UUID uuid, SacrificeType type) {
        try {
            SeasonService seasons = plugin.seasonService();
            return seasons != null && seasons.hasSacrifice(uuid, type);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // ------------------------------------------------------------------ modificadores estables

    /**
     * Aplica/limpia los modificadores de vida de la elección. Limpia solo los nuestros
     * (sin tocar la base 14 de fase 70 ni otros modificadores): sin duplicados ni apilados
     * al reentrar. Cama −4 HP, Corazón +8 HP.
     */
    public static void applyHealthModifiers(PermadeathPlugin plugin, Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        NamespacedKey cama = new NamespacedKey(plugin, "sac_cama");
        NamespacedKey corazon = new NamespacedKey(plugin, "sac_corazon");
        attr.removeModifier(cama);
        attr.removeModifier(corazon);
        SeasonService seasons;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException exception) {
            return;
        }
        if (seasons == null) {
            return;
        }
        if (seasons.hasSacrifice(player.getUniqueId(), SacrificeType.CAMA)) {
            attr.addTransientModifier(new AttributeModifier(cama, -4.0,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
        if (seasons.hasSacrifice(player.getUniqueId(), SacrificeType.CORAZON)) {
            attr.addTransientModifier(new AttributeModifier(corazon, 8.0,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
        double max = attr.getValue();
        if (player.getHealth() > max) {
            player.setHealth(max);
        }
        // Pulmón: −15% velocidad en tierra (transitorio, limpio y re-aplicado).
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            NamespacedKey pulmon = new NamespacedKey(plugin, "sac_pulmon");
            speed.removeModifier(pulmon);
            if (seasons.hasSacrifice(player.getUniqueId(), SacrificeType.PULMON)) {
                speed.addTransientModifier(new AttributeModifier(pulmon, -0.15,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    /** Quita armadura superior a hierro a elegidos de Corazón (re-log incluido). */
    public static void enforceArmor(PermadeathPlugin plugin, Player player) {
        SeasonService seasons;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException exception) {
            return;
        }
        if (seasons == null || !seasons.hasSacrifice(player.getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            if (isHighArmor(armor[i])) {
                ItemStack piece = armor[i];
                armor[i] = null;
                changed = true;
                for (ItemStack rest : inv.addItem(piece).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        }
        if (changed) {
            inv.setArmorContents(armor);
            player.sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
        }
    }

    /** Aplica el bloqueo a todos los elegidos online; la tarea corre cada 5 ticks. */
    private void tickArmor() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isDead()) {
                enforceArmor(plugin, player);
            }
        }
    }

    static boolean isHighArmor(ItemStack item) {
        return item != null && isHighArmorMaterial(item.getType());
    }

    static boolean isHighArmorMaterial(Material type) {
        if (type == null || type == Material.AIR) {
            return false;
        }
        String name = type.name();
        boolean tier = name.startsWith("DIAMOND_") || name.startsWith("NETHERITE_");
        boolean piece = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
        return tier && piece;
    }

    // ------------------------------------------------------------------ Abrigo: clima/frío, nada más

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onColdDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.ABRIGO)) {
            return;
        }
        // Solo clima/frío: congelación. Cualquier otro daño sigue intacto.
        if (event.getCause() == EntityDamageEvent.DamageCause.FREEZE) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ Pulmón: aire

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAirChange(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.PULMON)) {
            return;
        }
        event.setAmount(player.getMaximumAir());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrown(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.PULMON)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ Estómago

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaustion(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.ESTOMAGO)) {
            return;
        }
        // Sin agotamiento: solo se cancela la bajada; comer no da extra.
        if (event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrinkPotion(PlayerItemConsumeEvent event) {
        if (!has(event.getPlayer().getUniqueId(), SacrificeType.ESTOMAGO)) {
            return;
        }
        Material type = event.getItem().getType();
        if (type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.GRAY + "Tu Estómago rechaza las pociones.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplashPotion(PotionSplashEvent event) {
        // Intensidad 0 para elegidos (mecanismo real de la API; sin lista mutable arriesgada).
        for (LivingEntity affected : new ArrayList<>(event.getAffectedEntities())) {
            if (affected instanceof Player player && has(player.getUniqueId(), SacrificeType.ESTOMAGO)) {
                try {
                    event.setIntensity(affected, 0.0);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloudApply(AreaEffectCloudApplyEvent event) {
        event.getAffectedEntities().removeIf(e ->
                e instanceof Player p && has(p.getUniqueId(), SacrificeType.ESTOMAGO));
    }

    // ------------------------------------------------------------------ Corazón: bloqueo de armadura

    /**
     * Paper avisa después de equipar y el evento no es cancelable. Se revierte en
     * el tick siguiente; la tarea periódica cubre cualquier ruta restante.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (!has(player.getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        if (!isHighArmor(event.getNewItem())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                enforceArmor(plugin, player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        switch (event.getClick()) {
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (isHighArmor(event.getCurrentItem())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
                }
            }
            case NUMBER_KEY -> {
                if (isHighArmor(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
                    if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
                        event.setCancelled(true);
                        player.sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
                    }
                }
            }
            default -> {
                if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR
                        && isHighArmor(event.getCursor())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        if (!isHighArmor(event.getOldCursor())) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= 5 && raw <= 8) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorInteract(PlayerInteractEvent event) {
        if (!has(event.getPlayer().getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        if (event.getItem() == null || !isHighArmor(event.getItem())) {
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Tu Corazón rechaza la armadura superior al hierro.");
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorDispense(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player)) {
            return;
        }
        if (!has(player.getUniqueId(), SacrificeType.CORAZON)) {
            return;
        }
        if (isHighArmor(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ Ojo / Abrigo: re-objetivos acotados

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }
        if (!(event.getTarget() instanceof Player current)) {
            // Sin objetivo: Abrigo a 32 puede ser detectado (acotado al entorno del mob).
            if (monster.getTarget() != null) {
                return;
            }
            Player abrigo = nearestWith(monster.getLocation(), 32.0, SacrificeType.ABRIGO, 1);
            if (abrigo != null) {
                event.setTarget(abrigo);
            }
            return;
        }
        // Prioridad hostil del Ojo: si el mob apunta a otro y hay un Ojo a 16, lo prioriza.
        if (has(current.getUniqueId(), SacrificeType.OJO)) {
            return;
        }
        Player ojo = nearestWith(monster.getLocation(), 16.0, SacrificeType.OJO, 1);
        if (ojo != null && !ojo.equals(current)) {
            event.setTarget(ojo);
        }
    }

    private Player nearestWith(Location origin, double range, SacrificeType type, int cap) {
        if (origin == null || origin.getWorld() == null) {
            return null;
        }
        Player best = null;
        double bestSq = range * range;
        int checked = 0;
        for (Entity nearby : origin.getWorld().getNearbyEntities(origin, range, range / 2.0, range)) {
            if (!(nearby instanceof Player player) || player.isDead() || !player.isOnline()) {
                continue;
            }
            if (!has(player.getUniqueId(), type)) {
                continue;
            }
            if (++checked > 25) {
                break;
            }
            double dist = player.getLocation().distanceSquared(origin);
            if (dist < bestSq) {
                best = player;
                bestSq = dist;
            }
        }
        return best;
    }

    /** Ojo: brillo vanilla (atraviesa paredes en el cliente) a mobs en 16, acotado con tope. */
    private void tickOjoGlow() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || !has(player.getUniqueId(), SacrificeType.OJO)) {
                continue;
            }
            int applied = 0;
            for (Entity nearby : player.getNearbyEntities(16, 16, 16)) {
                if (!(nearby instanceof LivingEntity living) || nearby instanceof Player) {
                    continue;
                }
                living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 80, 0, false, false, false));
                if (++applied >= 20) {
                    break;
                }
            }
        }
    }

    /** Abrigo: los mobs sin objetivo te detectan a 32 (acotado, con tope, sin scans globales). */
    private void tickAbrigoDetect() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || !has(player.getUniqueId(), SacrificeType.ABRIGO)) {
                continue;
            }
            int retargeted = 0;
            for (Entity nearby : player.getNearbyEntities(32, 16, 32)) {
                if (!(nearby instanceof Monster monster) || monster.getTarget() != null) {
                    continue;
                }
                monster.setTarget(player);
                if (++retargeted >= 10) {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ Ancla: destino, no vuelta gratis

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!has(player.getUniqueId(), SacrificeType.ANCLA)) {
            return;
        }
        SeasonService seasons = plugin.seasonService();
        if (seasons == null || seasons.isAnchorUsed(player.getUniqueId())) {
            return;
        }
        Location loc = player.getLocation();
        seasons.putAnchorDeath(player.getUniqueId(),
                loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!has(player.getUniqueId(), SacrificeType.ANCLA)) {
            return;
        }
        SeasonService seasons = plugin.seasonService();
        if (seasons == null || seasons.isAnchorUsed(player.getUniqueId())) {
            return;
        }
        Location death = seasons.takeAnchorDeath(player.getUniqueId());
        if (death == null) {
            if (seasons.peekAnchorDeath(player.getUniqueId()) != null) {
                plugin.getLogger().warning("[Altar] Ancla de " + player.getName()
                        + " pendiente: destino no resoluble ahora, se conserva sin cobrar.");
                player.sendMessage(ChatColor.DARK_PURPLE + "[Altar] " + ChatColor.WHITE
                        + "tu punto de muerte no está disponible ahora (mundo no cargado); "
                        + "se conserva para el próximo intento, sin coste.");
            }
            return;
        }
        if (!seasons.consumeBankCore(1)) {
            seasons.putAnchorDeath(player.getUniqueId(),
                    death.getWorld().getName() + "," + death.getBlockX() + "," + death.getBlockY() + ","
                            + death.getBlockZ());
            plugin.getLogger().warning("[Altar] Ancla de " + player.getName()
                    + " sin Núcleo en el banco: no se aplica ni se consume (sin deuda).");
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "[Altar] " + ChatColor.WHITE + player.getName()
                    + " no pudo volver a su punto de muerte (el banco no tiene el Núcleo del grupo).");
            return;
        }
        seasons.markAnchorUsed(player.getUniqueId());
        event.setRespawnLocation(death);
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "[Altar] " + ChatColor.WHITE + player.getName()
                + " volvió a su punto de muerte (el grupo pagó 1 Núcleo).");
    }

    // ------------------------------------------------------------------ re-apply

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            applyHealthModifiers(plugin, player);
            enforceArmor(plugin, player);
        }, 20L);
    }

    /** La cama evita la explosión UHC solo para elegidos (el resto explota como siempre). */
    public static boolean mayUseBed(PermadeathPlugin plugin, Player player) {
        try {
            SeasonService seasons = plugin.seasonService();
            return seasons != null && seasons.hasSacrifice(player.getUniqueId(), SacrificeType.CAMA);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Abrigo excluye clima/frío (ácido, agua fría, nieve/hielo, congelación). */
    public static boolean isShelteredFromClimate(PermadeathPlugin plugin, Player player) {
        try {
            SeasonService seasons = plugin.seasonService();
            return seasons != null && seasons.hasSacrifice(player.getUniqueId(), SacrificeType.ABRIGO);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Nota offhand: la armadura en offhand no se equipa por sí sola en vanilla; las rutas
    // reales (clic/shift/drag/clic-derecho/dispensador) más el re-log cierran el resto.
}
