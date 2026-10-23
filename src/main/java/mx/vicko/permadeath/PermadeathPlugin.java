package mx.vicko.permadeath;

import mx.vicko.permadeath.odyssey.OdysseyManager;
import org.bukkit.World;
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
    private PhaseTasks phaseTasks;
    private SeasonService seasonService;
    private SeasonEconomyService seasonEconomyService;
    private SeasonAltar seasonAltar;
    private SacrificeListener sacrificeListener;
    private SolarFallEvent solarFallEvent;
    private PitController pitController;
    private ChispaItem chispaItem;
    private OdysseyManager odyssey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().addDefault("reserve.enabled", true);
        getConfig().addDefault("reserve.unlock-day", 20);
        getConfig().addDefault("reserve.filter-duration-minutes", 10);
        getConfig().addDefault("reserve.chest-chance-per-chunk", 0.16);
        getConfig().addDefault("reserve.portal.enabled", true);
        getConfig().addDefault("reserve.portal.search-radius-blocks", 128);
        getConfig().addDefault("season.pit-duration-minutes", 12);
        getConfig().addDefault("odyssey.enabled", true);
        getConfig().addDefault("odyssey.world-name", OdysseyManager.DEFAULT_WORLD_NAME);
        getConfig().addDefault("odyssey.spawn-y", OdysseyManager.DEFAULT_SPAWN_Y);
        getConfig().addDefault("odyssey.island-spacing-chunks", OdysseyManager.DEFAULT_SPACING_CHUNKS);
        getConfig().addDefault("odyssey.seed", 0L);
        getConfig().addDefault("odyssey.ban-reason", OdysseyManager.DEFAULT_BAN_REASON);
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
        // Fábricas de items antes de SeasonService.start(): retryPendingRewards entrega Esquirlas.
        this.eclipseCoreItem = new EclipseCoreItem();
        this.eclipseShardItem = new EclipseShardItem();
        this.seasonService = new SeasonService(this);
        seasonService.start();
        this.deathLocationMarker = new DeathLocationMarker(this);
        this.revivalAnchor = new RevivalAnchor(this);
        revivalAnchor.register();
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

        // Dimensión aislada: el manager crea/carga su mundo y registra sus
        // propios listeners y tareas. No toca Reserva Podrida ni los mundos
        // vanilla.
        this.odyssey = new OdysseyManager(this);
        try {
            this.odyssey.start();
        } catch (RuntimeException exception) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "No se pudo iniciar La Falla de la Odisea; el resto del plugin sigue activo.",
                    exception);
        }

        getServer().getPluginManager().registerEvents(new PermanentDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new DifficultyListener(this), this);
        getServer().getPluginManager().registerEvents(new SeasonListener(this), this);
        this.paleWardenController = new PaleWardenController(this);
        paleWardenController.start();
        this.endThreatController = new EndThreatController(this);
        endThreatController.start();
        this.phaseTasks = new PhaseTasks(this);
        phaseTasks.start();
        this.seasonEconomyService = new SeasonEconomyService(this);
        this.seasonAltar = new SeasonAltar(this);
        this.sacrificeListener = new SacrificeListener(this);
        this.chispaItem = new ChispaItem(this);
        this.pitController = new PitController(this);
        pitController.register();
        this.solarFallEvent = new SolarFallEvent(this);
        getServer().getPluginManager().registerEvents(sacrificeListener, this);
        getServer().getPluginManager().registerEvents(seasonAltar, this);
        sacrificeListener.start();
        try {
            solarFallEvent.checkAndRun(seasonService.currentDay());
        } catch (RuntimeException ignored) {
        }

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
        if (sacrificeListener != null) sacrificeListener.stop();
        if (seasonService != null) seasonService.shutdown();
        if (resurrectionRitual != null) resurrectionRitual.cancelAllForShutdown();
        if (nightHuntEvent != null) nightHuntEvent.shutdown();
        if (caravanEvent != null) caravanEvent.shutdown();
        if (netherAshstormEvent != null) netherAshstormEvent.shutdown();
        if (fortressHuntEvent != null) fortressHuntEvent.shutdown();
        if (cursedStriderController != null) cursedStriderController.shutdown();
        if (pitController != null) pitController.stop();
        if (odyssey != null) odyssey.stop();
        if (reservaPodrida != null) reservaPodrida.shutdown();
        if (reservaPortalController != null) reservaPortalController.shutdown();
        if (phaseTasks != null) phaseTasks.stop();
        if (ritualPenaltyManager != null) ritualPenaltyManager.clearOnlineModifiers();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        if (endThreatController != null) endThreatController.onConfigReload();
        if (seasonService != null) seasonService.reload();
    }

    public DayManager days() {
        return dayManager;
    }

    public boolean isPhaseActive(int day) {
        if (seasonService != null) return seasonService.isPhaseActive(day);
        return dayManager.getCurrentDay() >= day && getConfig().getBoolean("phases." + day + ".enabled", true);
    }

    public SeasonService seasonService() {
        return seasonService;
    }

    public SeasonEconomyService seasonEconomy() {
        return seasonEconomyService;
    }

    public SeasonAltar seasonAltar() {
        return seasonAltar;
    }

    public SacrificeListener sacrificeListener() {
        return sacrificeListener;
    }

    public PitController pitController() {
        return pitController;
    }

    public ChispaItem chispaItem() {
        return chispaItem;
    }

    public SolarFallEvent solarFallEvent() {
        return solarFallEvent;
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

    public OdysseyManager odyssey() { return odyssey; }

    /** Guard central para que las fases globales no invadan La Falla. */
    public boolean isOdysseyWorld(World world) {
        return odyssey != null && odyssey.isOdyssey(world);
    }

    public PhaseTasks phaseTasks() { return phaseTasks; }

    public void setPhaseEnabled(int day, boolean enabled) {
        getConfig().set("phases." + day + ".enabled", enabled);
        saveConfig();
    }
}
