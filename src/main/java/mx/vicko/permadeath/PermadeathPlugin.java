package mx.vicko.permadeath;

import org.bukkit.command.PluginCommand;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class PermadeathPlugin extends JavaPlugin {
    private DayManager dayManager;
    private DeathRegistry deathRegistry;
    private DeathLocationMarker deathLocationMarker;
    private RevivalAnchor revivalAnchor;
    private EclipseCoreItem eclipseCoreItem;
    private EclipseShardItem eclipseShardItem;
    private ResurrectionSealItem resurrectionSealItem;
    private RitualPenaltyManager ritualPenaltyManager;
    private ResurrectionRitual resurrectionRitual;
    private NightHuntEvent nightHuntEvent;
    private PaleWardenController paleWardenController;
    private EndThreatController endThreatController;
    private CaravanEvent caravanEvent;
    private NetherAshstormEvent netherAshstormEvent;
    private FortressHuntEvent fortressHuntEvent;
    private CursedStriderController cursedStriderController;
    private ReservaPodrida reservaPodrida;
    private ReservaPortalController reservaPortalController;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().addDefault("reserve.enabled", true);
        getConfig().addDefault("reserve.unlock-day", 20);
        getConfig().addDefault("reserve.filter-duration-minutes", 10);
        getConfig().addDefault("reserve.chest-chance-per-chunk", 0.16);
        getConfig().addDefault("reserve.portal.enabled", true);
        getConfig().addDefault("reserve.portal.search-radius-blocks", 128);
        String configuredPrefix = getConfig().getString("messages.prefix", "");
        if (configuredPrefix.contains("Permadeath")) {
            getConfig().set("messages.prefix", configuredPrefix.replace("Permadeath", "LetumcrafOficial"));
        }
        getConfig().options().copyDefaults(true);
        saveConfig();
        this.dayManager = new DayManager(this);
        dayManager.initialize();
        this.deathRegistry = new DeathRegistry(this);
        deathRegistry.load();
        this.deathLocationMarker = new DeathLocationMarker(this);
        this.revivalAnchor = new RevivalAnchor(this);
        revivalAnchor.register();
        this.eclipseCoreItem = new EclipseCoreItem();
        this.eclipseShardItem = new EclipseShardItem();
        this.resurrectionSealItem = new ResurrectionSealItem(this);
        resurrectionSealItem.register();
        this.ritualPenaltyManager = new RitualPenaltyManager(this);
        ritualPenaltyManager.register();
        this.resurrectionRitual = new ResurrectionRitual(this, ritualPenaltyManager);
        resurrectionRitual.register();
        this.nightHuntEvent = new NightHuntEvent(this);
        nightHuntEvent.register();
        this.caravanEvent = new CaravanEvent(this);
        caravanEvent.register();
        this.netherAshstormEvent = new NetherAshstormEvent(this);
        netherAshstormEvent.register();
        this.fortressHuntEvent = new FortressHuntEvent(this);
        fortressHuntEvent.register();
        this.cursedStriderController = new CursedStriderController(this);
        cursedStriderController.start();
        this.reservaPodrida = new ReservaPodrida(this);
        this.reservaPortalController = new ReservaPortalController(this, reservaPodrida);

        getServer().getPluginManager().registerEvents(new PermanentDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new DifficultyListener(this), this);
        this.paleWardenController = new PaleWardenController(this);
        paleWardenController.start();
        this.endThreatController = new EndThreatController(this);
        endThreatController.start();
        new PhaseTasks(this).start();

        PluginCommand command = getCommand("permadeath");
        if (command == null) {
            throw new IllegalStateException("No se pudo registrar /permadeath");
        }
        PermadeathCommand executor = new PermadeathCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getLogger().info("Permadeath activo. Día actual: " + dayManager.getCurrentDay());
    }

    @Override
    public void onDisable() {
        if (resurrectionRitual != null) resurrectionRitual.cancelAllForShutdown();
        if (nightHuntEvent != null) nightHuntEvent.shutdown();
        if (caravanEvent != null) caravanEvent.shutdown();
        if (netherAshstormEvent != null) netherAshstormEvent.shutdown();
        if (fortressHuntEvent != null) fortressHuntEvent.shutdown();
        if (cursedStriderController != null) cursedStriderController.shutdown();
        if (reservaPodrida != null) reservaPodrida.shutdown();
        if (reservaPortalController != null) reservaPortalController.shutdown();
        if (ritualPenaltyManager != null) ritualPenaltyManager.clearOnlineModifiers();
    }

    public DayManager days() {
        return dayManager;
    }

    public boolean isPhaseActive(int day) {
        return dayManager.getCurrentDay() >= day && getConfig().getBoolean("phases." + day + ".enabled", true);
    }

    public DeathRegistry deaths() {
        return deathRegistry;
    }

    public DeathLocationMarker deathMarker() {
        return deathLocationMarker;
    }

    public RevivalAnchor revivalAnchor() {
        return revivalAnchor;
    }

    public EclipseCoreItem eclipseCoreItem() {
        return eclipseCoreItem;
    }

    public EclipseShardItem eclipseShardItem() {
        return eclipseShardItem;
    }

    public ResurrectionSealItem resurrectionSealItem() {
        return resurrectionSealItem;
    }

    public NightHuntEvent nightHuntEvent() {
        return nightHuntEvent;
    }

    public CaravanEvent caravanEvent() {
        return caravanEvent;
    }

    public NamespacedKey caravanKey() {
        return new NamespacedKey(this, "caravan_guard");
    }

    public NetherAshstormEvent netherAshstormEvent() {
        return netherAshstormEvent;
    }

    public FortressHuntEvent fortressHuntEvent() { return fortressHuntEvent; }

    public NamespacedKey fortressChestKey() { return new NamespacedKey(this, "fortress_hunt_chest"); }

    public NamespacedKey fortressGuardKey() { return new NamespacedKey(this, "fortress_hunt_guard"); }

    public NamespacedKey cursedStriderKey() { return new NamespacedKey(this, "cursed_strider"); }

    public ReservaPodrida reservaPodrida() { return reservaPodrida; }

    public ReservaPortalController reservaPortal() { return reservaPortalController; }

    public void setPhaseEnabled(int day, boolean enabled) {
        getConfig().set("phases." + day + ".enabled", enabled);
        saveConfig();
    }
}
