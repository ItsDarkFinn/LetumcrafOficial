package mx.vicko.permadeath.odyssey;

import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import mx.vicko.permadeath.PermadeathPlugin;

/**
 * Punto único de ciclo de vida de La Falla de la Odisea.
 *
 * <p>El resto de clases de este paquete no conocen a Reserva Podrida ni
 * modifican sus mundos. La integración con el plugin base se reduce a crear
 * este manager, arrancarlo y detenerlo.</p>
 */
public final class OdysseyManager {
    public static final String DEFAULT_WORLD_NAME = "La Falla de la Odisea";
    public static final double DEFAULT_SPAWN_X = 8.5;
    public static final double DEFAULT_SPAWN_Y = 129.0;
    public static final double DEFAULT_SPAWN_Z = 8.5;
    public static final int DEFAULT_SPACING_CHUNKS = 8;
    public static final String DEFAULT_BAN_REASON =
            "Has muerto en La Falla de la Odisea. Tu cuenta ha sido baneada permanentemente.";

    private final PermadeathPlugin plugin;
    private World world;
    private Location spawn;
    private String worldName;
    private int spacingChunks;
    private int spawnTopY;
    private boolean ready;
    private OdysseyEphemeralBlocks ephemeralBlocks;
    private OdysseyGravityController gravityController;
    private OdysseySpawnController spawnController;
    private OdysseyLootChests lootChests;

    public OdysseyManager(PermadeathPlugin plugin) {
        this.plugin = plugin;
        this.worldName = configuredWorldName();
    }

    /**
     * Crea o carga el mundo y registra exclusivamente los componentes de La
     * Falla. Si el mundo no puede crearse, devuelve {@code false} y deja el
     * resto del plugin intacto.
     */
    public boolean start() {
        if (!plugin.getConfig().getBoolean("odyssey.enabled", true)) {
            plugin.getLogger().info("La Falla de la Odisea está desactivada por configuración.");
            return false;
        }

        this.worldName = configuredWorldName();
        this.spacingChunks = Math.max(4, plugin.getConfig().getInt(
                "odyssey.island-spacing-chunks", DEFAULT_SPACING_CHUNKS));
        double configuredSpawnY = plugin.getConfig().getDouble("odyssey.spawn-y", DEFAULT_SPAWN_Y);
        this.spawnTopY = (int) Math.floor(configuredSpawnY) - 1;
        this.spawn = new Location(null, DEFAULT_SPAWN_X, configuredSpawnY, DEFAULT_SPAWN_Z, 0.0f, 0.0f);

        this.world = Bukkit.getWorld(worldName);
        if (world == null) {
            this.world = createWorld();
        }
        if (world == null) {
            plugin.getLogger().severe("No se pudo crear La Falla de la Odisea; el resto del plugin sigue activo.");
            return false;
        }

        this.spawn.setWorld(world);
        configureWorld();
        ensureSpawnPlatform();
        world.setSpawnLocation(spawn);

        this.ephemeralBlocks = new OdysseyEphemeralBlocks(plugin, this);
        this.gravityController = new OdysseyGravityController(plugin, this);
        this.spawnController = new OdysseySpawnController(plugin, this);
        this.lootChests = new OdysseyLootChests(plugin, this);

        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(new OdysseyListener(plugin, this), plugin);
        pluginManager.registerEvents(ephemeralBlocks, plugin);
        pluginManager.registerEvents(gravityController, plugin);
        pluginManager.registerEvents(lootChests, plugin);

        gravityController.start();
        spawnController.start();
        ready = true;
        lootChests.start();

        plugin.getLogger().info("La Falla de la Odisea lista en '" + world.getName()
                + "' (spawn " + formatLocation(spawn) + ").");
        return true;
    }

    public void stop() {
        ready = false;
        if (spawnController != null) {
            spawnController.stop();
        }
        if (gravityController != null) {
            gravityController.stop();
        }
        if (ephemeralBlocks != null) {
            ephemeralBlocks.stop();
        }
    }

    public boolean isReady() {
        return ready && world != null;
    }

    public int spacingChunks() {
        return spacingChunks;
    }

    public int spawnTopY() {
        return spawnTopY;
    }

    public World world() {
        return world;
    }

    public Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    public boolean isOdyssey(World candidate) {
        return candidate != null && candidate.getName().equals(worldName);
    }

    public boolean isOdyssey(Location location) {
        return location != null && isOdyssey(location.getWorld());
    }

    public String banReason() {
        return plugin.getConfig().getString("odyssey.ban-reason", DEFAULT_BAN_REASON);
    }

    public OdysseyEphemeralBlocks ephemeralBlocks() {
        return ephemeralBlocks;
    }

    public OdysseyGravityController gravity() {
        return gravityController;
    }

    /**
     * Teletransporta al spawn de La Falla. La comprobación de mundo la hace
     * cada listener antes de llamar aquí.
     */
    public boolean teleport(Player player) {
        if (!ready || world == null || spawn == null) {
            return false;
        }
        Location destination = spawn.clone();
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        boolean teleported = player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (teleported) {
            player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.65f);
            player.sendActionBar(Component.text("La Falla de la Odisea", NamedTextColor.LIGHT_PURPLE));
        }
        return teleported;
    }

    private World createWorld() {
        long seed = plugin.getConfig().getLong("odyssey.seed", 0L);
        OdysseyChunkGenerator generator = new OdysseyChunkGenerator(
                spacingChunks, DEFAULT_SPAWN_X, spawn.getY(), DEFAULT_SPAWN_Z);

        World created = tryCreateWorld(World.Environment.CUSTOM, generator, seed);
        if (created != null) {
            return created;
        }

        // Paper puede rechazar Environment.CUSTOM si el servidor no tiene un
        // tipo de dimensión registrado. El fallback mantiene el mundo aislado
        // y con el mismo generador; nunca toca Overworld, Nether ni End.
        plugin.getLogger().warning("Environment.CUSTOM no disponible para La Falla; "
                + "se usará un mundo separado con Environment.NORMAL.");
        return tryCreateWorld(World.Environment.NORMAL, generator, seed);
    }

    private World tryCreateWorld(World.Environment environment, OdysseyChunkGenerator generator, long seed) {
        try {
            WorldCreator creator = new WorldCreator(worldName)
                    .environment(environment)
                    .generator(generator)
                    .biomeProvider(new OdysseyBiomeProvider())
                    .generateStructures(false)
                    .seed(seed)
                    .hardcore(true);
            return creator.createWorld();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("No se pudo crear La Falla con environment "
                    + environment + ": " + exception.getMessage());
            plugin.getLogger().log(Level.FINE,
                    "Detalle de creación de La Falla con environment " + environment, exception);
            return null;
        }
    }

    @SuppressWarnings("removal")
    private void configureWorld() {
        world.setHardcore(true);
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        // El listener cancela el spawning natural. Desactivar la regla evita
        // que se intente generar fauna vanilla antes de que el listener exista.
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setTime(18_000L);
        world.setStorm(false);
        world.setThundering(false);
    }

    /**
     * Red de seguridad para mundos creados antes de que existiera el
     * populator. En un mundo nuevo el chunk 0,0 ya contiene la isla de llegada.
     */
    private void ensureSpawnPlatform() {
        Block center = world.getBlockAt((int) Math.floor(spawn.getX()), spawnTopY,
                (int) Math.floor(spawn.getZ()));
        if (!center.getType().isAir()) {
            return;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(center.getX() + dx, spawnTopY, center.getZ() + dz)
                        .setType(Material.OBSIDIAN, false);
                world.getBlockAt(center.getX() + dx, spawnTopY - 1, center.getZ() + dz)
                        .setType(Material.OBSIDIAN, false);
            }
        }
    }

    private String configuredWorldName() {
        String configured = plugin.getConfig().getString("odyssey.world-name", DEFAULT_WORLD_NAME);
        return configured == null || configured.isBlank() ? DEFAULT_WORLD_NAME : configured.trim();
    }

    private static String formatLocation(Location location) {
        return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f",
                location.getX(), location.getY(), location.getZ());
    }
}
