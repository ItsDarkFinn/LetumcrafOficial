package mx.vicko.permadeath.odyssey;

import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Une las islas con rutas de parkour de plataformas 1x1.
 *
 * <p>Cada isla emite una ruta hacia el este y otra hacia el sur. La ruta
 * comienza y termina a tres bloques del borde de la isla, deja huecos de 2 a 4
 * bloques y cambia de altura como maximo un bloque por salto. Al generarse
 * desde las coordenadas globales, las rutas continuan indefinidamente sin
 * depender de que los chunks vecinos ya existan.</p>
 */
public final class OdysseyParkourPopulator extends BlockPopulator {
    private static final int PATH_EDGE_GAP = 3;
    private static final int ISLAND_BLOCK_SIZE = 16;

    private final int spacingChunks;
    private final int spawnTopY;
    private final int cellSize;

    public OdysseyParkourPopulator(int spacingChunks, int spawnTopY) {
        this.spacingChunks = Math.max(OdysseyBlockPopulator.MIN_SPACING_CHUNKS, spacingChunks);
        this.spawnTopY = spawnTopY;
        this.cellSize = this.spacingChunks * ISLAND_BLOCK_SIZE;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        int cellX = Math.floorDiv(chunkX, spacingChunks);
        int cellZ = Math.floorDiv(chunkZ, spacingChunks);

        // Un chunk ancla puede contener el final de la ruta anterior y el
        // comienzo de la siguiente. Se generan ambas de forma determinista.
        if (Math.floorMod(chunkZ, spacingChunks) == 0) {
            buildEast(worldInfo, region, cellX, cellZ, chunkX, chunkZ);
            buildEast(worldInfo, region, cellX - 1, cellZ, chunkX, chunkZ);
        }
        if (Math.floorMod(chunkX, spacingChunks) == 0) {
            buildSouth(worldInfo, region, cellX, cellZ, chunkX, chunkZ);
            buildSouth(worldInfo, region, cellX, cellZ - 1, chunkX, chunkZ);
        }
    }

    private void buildEast(WorldInfo worldInfo, LimitedRegion region, int cellX, int cellZ,
            int chunkX, int chunkZ) {
        int sourceCenterX = cellX * cellSize + 8;
        int sourceCenterZ = cellZ * cellSize + 8;
        int targetCenterX = sourceCenterX + cellSize;
        int sourceRadius = OdysseyBlockPopulator.islandRadius(
                worldInfo, spacingChunks, cellX * spacingChunks, cellZ * spacingChunks);
        int targetRadius = OdysseyBlockPopulator.islandRadius(
                worldInfo, spacingChunks, (cellX + 1) * spacingChunks, cellZ * spacingChunks);
        int start = sourceCenterX + sourceRadius + PATH_EDGE_GAP;
        int end = targetCenterX - targetRadius - PATH_EDGE_GAP;
        if (start > end) {
            return;
        }

        int sourceTopY = OdysseyBlockPopulator.islandTopY(
                worldInfo, spacingChunks, cellX * spacingChunks, cellZ * spacingChunks, spawnTopY);
        int targetTopY = OdysseyBlockPopulator.islandTopY(
                worldInfo, spacingChunks, (cellX + 1) * spacingChunks, cellZ * spacingChunks, spawnTopY);
        List<OdysseyParkourPath.Platform> platforms =
                OdysseyParkourPath.plan(start, end, sourceTopY, targetTopY);
        if (platforms.isEmpty() || platforms.get(platforms.size() - 1).y() != targetTopY) {
            return;
        }

        for (int index = 0; index < platforms.size(); index++) {
            OdysseyParkourPath.Platform platform = platforms.get(index);
            int x = platform.position();
            int z = sourceCenterZ + platform.sway();
            if (!isInChunk(x, z, chunkX, chunkZ)) {
                continue;
            }
            placePlatform(region, x, platform.y(), z, index);
        }
    }

    private void buildSouth(WorldInfo worldInfo, LimitedRegion region, int cellX, int cellZ,
            int chunkX, int chunkZ) {
        int sourceCenterX = cellX * cellSize + 8;
        int sourceCenterZ = cellZ * cellSize + 8;
        int targetCenterZ = sourceCenterZ + cellSize;
        int sourceRadius = OdysseyBlockPopulator.islandRadius(
                worldInfo, spacingChunks, cellX * spacingChunks, cellZ * spacingChunks);
        int targetRadius = OdysseyBlockPopulator.islandRadius(
                worldInfo, spacingChunks, cellX * spacingChunks, (cellZ + 1) * spacingChunks);
        int start = sourceCenterZ + sourceRadius + PATH_EDGE_GAP;
        int end = targetCenterZ - targetRadius - PATH_EDGE_GAP;
        if (start > end) {
            return;
        }

        int sourceTopY = OdysseyBlockPopulator.islandTopY(
                worldInfo, spacingChunks, cellX * spacingChunks, cellZ * spacingChunks, spawnTopY);
        int targetTopY = OdysseyBlockPopulator.islandTopY(
                worldInfo, spacingChunks, cellX * spacingChunks, (cellZ + 1) * spacingChunks, spawnTopY);
        List<OdysseyParkourPath.Platform> platforms =
                OdysseyParkourPath.plan(start, end, sourceTopY, targetTopY);
        if (platforms.isEmpty() || platforms.get(platforms.size() - 1).y() != targetTopY) {
            return;
        }

        for (int index = 0; index < platforms.size(); index++) {
            OdysseyParkourPath.Platform platform = platforms.get(index);
            int x = sourceCenterX + platform.sway();
            int z = platform.position();
            if (!isInChunk(x, z, chunkX, chunkZ)) {
                continue;
            }
            placePlatform(region, x, platform.y(), z, index);
        }
    }

    private static boolean isInChunk(int x, int z, int chunkX, int chunkZ) {
        return Math.floorDiv(x, ISLAND_BLOCK_SIZE) == chunkX
                && Math.floorDiv(z, ISLAND_BLOCK_SIZE) == chunkZ;
    }

    private static void placePlatform(LimitedRegion region, int x, int y, int z, int index) {
        if (!region.isInRegion(x, y, z) || !region.getType(x, y, z).isAir()) {
            return;
        }
        region.setType(x, y, z, platformMaterial(index));
    }

    static Material platformMaterial(int index) {
        if (Math.floorMod(index, 13) == 6) {
            return Material.GLASS;
        }
        if (Math.floorMod(index, 19) == 11) {
            return Material.CRYING_OBSIDIAN;
        }
        return Material.OBSIDIAN;
    }
}
