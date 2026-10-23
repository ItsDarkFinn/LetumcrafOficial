package mx.vicko.permadeath;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Eventos de juego. Las probabilidades están agrupadas por día para que el calendario sea fácil de auditar. */
@SuppressWarnings("deprecation")
public final class DifficultyListener implements Listener {
    private final PermadeathPlugin plugin;

    public DifficultyListener(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onNaturalRegeneration(EntityRegainHealthEvent event) {
        if (plugin.isPhaseActive(10) && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUndeadCombust(EntityCombustEvent event) {
        if (plugin.isPhaseActive(10) && (event.getEntity() instanceof Zombie || event.getEntity() instanceof Skeleton)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material material = block.getType();
        if (plugin.isPhaseActive(10) && Tag.LOGS.isTagged(material) && !isAxe(player.getInventory().getItemInMainHand().getType())) player.damage(1.0);

        if (plugin.isPhaseActive(25) && (Tag.BASE_STONE_OVERWORLD.isTagged(material) || material == Material.DEEPSLATE) && chance(0.05)) {
            if (chance(0.5)) block.setType(Material.LAVA);
            else {
                LivingEntity silverfish = (LivingEntity) block.getWorld().spawnEntity(block.getLocation().add(.5, .5, .5), EntityType.SILVERFISH);
                silverfish.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 30, 1));
            }
        }
        if (plugin.isPhaseActive(25) && material == Material.COAL_ORE && chance(0.02)) block.getWorld().createExplosion(block.getLocation(), 4.0F, false, true);
        if (plugin.isPhaseActive(25) && (material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE) && chance(0.20)) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.COAL));
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0));
        }
        if (plugin.isPhaseActive(90) && Tag.CROPS.isTagged(material) && chance(0.30)) {
            event.setCancelled(true);
            block.setType(Material.DIRT);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFragileToolUse(PlayerItemDamageEvent event) {
        if (plugin.isPhaseActive(70)) event.setDamage(event.getDamage() * 2);
        if (!plugin.isPhaseActive(10) || !chance(0.15)) return;
        String name = event.getItem().getType().name();
        if ((name.startsWith("WOODEN_") || name.startsWith("STONE_")) && name.matches(".*(PICKAXE|AXE|SHOVEL|HOE|SWORD)$")) {
            event.setCancelled(true);
            event.getItem().setAmount(0);
            event.getPlayer().sendMessage("§cTu herramienta se hizo pedazos.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobHit(EntityDamageByEntityEvent event) {
        if (plugin.isOdysseyWorld(event.getEntity().getWorld())) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (plugin.isPhaseActive(10) && attacker instanceof Monster) player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        if (plugin.isPhaseActive(20) && attacker instanceof Spider) player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
        if (plugin.isPhaseActive(40) && attacker.getType() == EntityType.PHANTOM) player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 80, 0));
        if (plugin.isPhaseActive(60) && player.isBlocking() && event.getFinalDamage() >= 5.0) player.setCooldown(Material.SHIELD, 200);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (plugin.isPhaseActive(35) && event.getEntity() instanceof Player player && player.getWorld().getEnvironment() == World.Environment.THE_END && event.getCause() == EntityDamageEvent.DamageCause.FALL) event.setDamage(event.getDamage() * 3.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (!plugin.isPhaseActive(20) || event.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        // Bloque 2 (Cama): dormir funciona solo para el elegido; el resto explota como siempre.
        if (SacrificeListener.mayUseBed(plugin, event.getPlayer())) return;
        event.setUseBed(org.bukkit.event.Event.Result.DENY);
        event.getBed().getWorld().createExplosion(event.getBed().getLocation(), 5.0F, true, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSkeletonArrowHit(ProjectileHitEvent event) {
        if (plugin.isPhaseActive(20) && event.getEntity() instanceof Arrow arrow && arrow.getShooter() instanceof Skeleton && event.getHitEntity() instanceof Player player) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (plugin.isOdysseyWorld(event.getLocation().getWorld())) return;
        LivingEntity mob = event.getEntity();
        if (plugin.isPhaseActive(25) && mob instanceof Creeper creeper) { creeper.setExplosionRadius(creeper.getExplosionRadius() * 2); creeper.setMaxFuseTicks(20); }
        if (plugin.isPhaseActive(35) && mob instanceof EnderDragon dragon) {
            dragon.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(600.0);
            dragon.setHealth(600.0);
        }
        if (plugin.isPhaseActive(60) && mob instanceof Monster && chance(0.50)) equipHostile(mob);
        if (plugin.isPhaseActive(60) && mob instanceof Skeleton skeleton && chance(0.08)) {
            Creeper creeper = (Creeper) mob.getWorld().spawnEntity(mob.getLocation(), EntityType.CREEPER);
            creeper.addPassenger(skeleton);
              // Sincronización inicial: la montura hereda el objetivo del esqueleto si ya existe.
              // El refresco continuo lo mantiene el propietario D (PhaseTasks). Enderman forzado retirado.
              if (skeleton.getTarget() instanceof Player initialTarget) creeper.setTarget(initialTarget);
        }
        if (plugin.isPhaseActive(80) && mob instanceof Creeper creeper) { creeper.setSilent(true); if (chance(0.15)) creeper.setInvisible(true); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDragonArrow(EntityDamageByEntityEvent event) {
        if (!plugin.isPhaseActive(35) || !(event.getEntity() instanceof EnderDragon dragon) || !(event.getDamager() instanceof Arrow)) return;
        for (int i = 0; i < 3; i++) dragon.getWorld().spawnEntity(dragon.getLocation(), EntityType.ENDERMITE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageEvent event) {
        if (!plugin.isPhaseActive(35) || !(event.getEntity() instanceof EnderCrystal crystal)) return;
        // Se elimina antes de la explosión para que el daño de área no vuelva a disparar este evento.
        event.setCancelled(true);
        var location = crystal.getLocation();
        crystal.remove();
        location.getWorld().createExplosion(location, 8.0F, true, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreeperExplosion(EntityExplodeEvent event) {
        if (!plugin.isPhaseActive(80) || !(event.getEntity() instanceof Creeper)) return;
        for (Entity nearby : event.getEntity().getNearbyEntities(12, 12, 12)) if (nearby instanceof Player player) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!plugin.isPhaseActive(40)) return;
        if (event.isCancelled()) return;
        // Clasificación custom pública acordada (paso 6 la implementa en ReservaPortalController).
        // Se excluyen ambos extremos antes de cualquier daño/transformación.
        if (isCustomPortal(event.getFrom()) || (event.getTo() != null && isCustomPortal(event.getTo()))) return;
        // Solo portales Nether auténticos: nunca End ni frames aunque otro listener aún no haya cancelado.
        if (!isNetherPortalCause(event.getCause())) return;
        event.getPlayer().damage(4.0);
        Block centre = event.getFrom().getBlock();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            Block block = centre.getRelative(x, 0, z);
            if (block.getType().isSolid() && !isEndProtected(block.getType())) block.setType(Material.NETHERRACK);
            Block above = block.getRelative(0, 1, 0);
            if (above.getType().isAir() && chance(0.35)) above.setType(Material.FIRE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!plugin.isPhaseActive(60) || !(event.getEntity().getType() == EntityType.HORSE || event.getEntity().getType() == EntityType.DONKEY) || !(event.getOwner() instanceof Player player)) return;
        event.setCancelled(true);
        player.damage(2.0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 1200, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onZombieDeath(EntityDeathEvent event) {
        if (!plugin.isPhaseActive(60) || !(event.getEntity() instanceof Zombie zombie) || !chance(0.30)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> zombie.getWorld().spawnEntity(zombie.getLocation(), EntityType.SKELETON), 40L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnimalDeath(EntityDeathEvent event) {
        if (plugin.isPhaseActive(90) && event.getEntity() instanceof Animals && chance(0.50)) { event.getDrops().clear(); event.getDrops().add(new ItemStack(Material.ROTTEN_FLESH)); }
    }

    @EventHandler
    public void onBatDeath(EntityDeathEvent event) {
        if (!plugin.isPhaseActive(20) || event.getEntity().getType() != EntityType.BAT
                || !chance(plugin.getConfig().getDouble("phases.20.bats.book-drop-chance", 0.05))) return;

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.PROTECTION, 5, true);
        meta.addStoredEnchant(Enchantment.BLAST_PROTECTION, 5, true);
        meta.setDisplayName("§dManual de supervivencia explosiva");
        book.setItemMeta(meta);
        event.getDrops().add(book);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGoldenApple(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (event.getItem().getType() == Material.GOLDEN_APPLE && plugin.isPhaseActive(70)) {
            if (player.hasCooldown(Material.GOLDEN_APPLE)) { event.setCancelled(true); return; }
            player.setCooldown(Material.GOLDEN_APPLE, GOLDEN_APPLE_COOLDOWN_TICKS);
            // Sin HUNGER amplifier 2: solo NAUSEA residual. Hambre vanilla y sprint de fases intactos.
              if (chance(0.05)) { event.setCancelled(true); player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 600, 0)); }
        }
        if (plugin.isPhaseActive(90) && event.getItem().getType().isEdible()) {
            int before = player.getFoodLevel();
            plugin.getServer().getScheduler().runTask(plugin, () -> { int gained = Math.max(0, player.getFoodLevel() - before); player.setFoodLevel(before + (gained + 1) / 2); });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent event) {
        if (plugin.isPhaseActive(80) && event.getClickedBlock() != null && isChest(event.getClickedBlock().getType()) && chance(0.05)) { event.getPlayer().damage(8.0); event.getPlayer().sendMessage("§4El cofre te mordió."); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.isPhaseActive(50) || event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !chance(0.08)) return;
        event.setCancelled(true);
        Entity drowned = event.getPlayer().getWorld().spawnEntity(event.getPlayer().getLocation().add(event.getPlayer().getLocation().getDirection().multiply(2)), EntityType.DROWNED);
        drowned.setVelocity(event.getPlayer().getLocation().getDirection().multiply(-1.3));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoatMove(VehicleMoveEvent event) {
        if (!plugin.isPhaseActive(50) || !(event.getVehicle() instanceof Boat)) return;
        boolean danger = event.getVehicle().getNearbyEntities(1.5, 1.5, 1.5).stream().anyMatch(e -> e.getType() == EntityType.SQUID) || event.getTo().getBlock().getType() == Material.LILY_PAD;
        if (danger) event.getVehicle().remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (plugin.isPhaseActive(100)) { event.setCancelled(true); event.getPlayer().sendMessage("§8El aura de soledad impide que tu mensaje llegue a alguien."); }
    }

    private void equipHostile(LivingEntity mob) {
        EntityEquipment gear = mob.getEquipment();
        if (gear == null) return;
        String prefix = chance(0.5) ? "DIAMOND_" : "IRON_";
        gear.setHelmet(new ItemStack(Material.valueOf(prefix + "HELMET")));
        gear.setChestplate(new ItemStack(Material.valueOf(prefix + "CHESTPLATE")));
        gear.setLeggings(new ItemStack(Material.valueOf(prefix + "LEGGINGS")));
        gear.setBoots(new ItemStack(Material.valueOf(prefix + "BOOTS")));
    }

    static final int GOLDEN_APPLE_COOLDOWN_TICKS = 160;

    static boolean isAxe(Material material) {
        if (material == null) return false;
        // Tag.ITEMS_AXES si existe en API objetivo; fallback por nombre cubre todas las hachas sin incluir picos.
        return material.name().endsWith("_AXE");
    }

    static boolean isEndProtected(Material material) {
        return material == Material.END_PORTAL || material == Material.END_GATEWAY
                || material == Material.END_PORTAL_FRAME;
    }

    static boolean isNetherPortalCause(PlayerTeleportEvent.TeleportCause cause) {
        return cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL;
    }

    private boolean isCustomPortal(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) return false;
        try {
            ReservaPortalController controller = plugin.reservaPortal();
            if (controller == null) return false;
            try {
                java.lang.reflect.Method method = controller.getClass().getMethod("isCustomPortal", org.bukkit.Location.class);
                Object result = method.invoke(controller, location);
                if (result instanceof Boolean value) return value;
            } catch (NoSuchMethodException missing) {
                // Paso 6 aún la implementa: sin clasificación disponible no se excluye aquí.
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void targetClosestPlayer(Monster mob, double range) {
        Player best = null; double bestDistance = range * range;
        for (Player player : mob.getWorld().getPlayers()) { double distance = player.getLocation().distanceSquared(mob.getLocation()); if (!player.isDead() && distance < bestDistance) { best = player; bestDistance = distance; } }
        if (best != null) mob.setTarget(best);
    }

    private boolean chance(double probability) { return ThreadLocalRandom.current().nextDouble() < probability; }
    private boolean isChest(Material material) { return material.name().endsWith("CHEST") || material == Material.BARREL; }
}
