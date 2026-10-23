package mx.vicko.permadeath.odyssey;

import java.util.List;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Generador de vacío absoluto. No escribe bedrock, capas base, superficie,
 * cuevas, decoraciones ni estructuras. Las islas llegan después mediante
 * {@link OdysseyBlockPopulator}.
 *
 * <p>La clase es intencionadamente independiente de Reserva Podrida. Paper
 * puede instanciarla en varios hilos durante la generación, por lo que no
 * guarda estado mutable compartido.</p>
 */
public final class OdysseyChunkGenerator extends ChunkGenerator {
    private final int spacingChunks;
    private final double spawnX;
    private final double spawnY;
    private final double spawnZ;

    public OdysseyChunkGenerator(int spacingChunks, double spawnX, double spawnY, double spawnZ) {
        this.spacingChunks = Math.max(4, spacingChunks);
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        int spawnTopY = (int) Math.floor(spawnY) - 1;
        return List.of(
                new OdysseyBlockPopulator(spacingChunks, spawnTopY, spawnX, spawnZ),
                new OdysseyParkourPopulator(spacingChunks, spawnTopY));
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new OdysseyBiomeProvider();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, spawnX, spawnY, spawnZ, 0.0f, 0.0f);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return worldInfo.getMinHeight();
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        // El controlador de Odyssey decide qué entidades existen. Así ningún
        // mob vanilla puede aparecer por el spawning natural.
        return false;
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Vacío intencional.
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Vacío intencional.
    }

    @Override
    public void generateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Nunca se genera bedrock en La Falla.
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Vacío intencional.
    }
}
