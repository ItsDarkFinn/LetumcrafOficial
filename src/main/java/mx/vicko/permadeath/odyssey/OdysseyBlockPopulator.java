package mx.vicko.permadeath.odyssey;

import java.util.SplittableRandom;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Islas fracturadas separadas por vacío. Cada isla vive completa dentro de su
 * chunk ancla para no depender del orden en que Paper genere los vecinos.
 */
public final class OdysseyBlockPopulator extends BlockPopulator {
    static final int MIN_SPACING_CHUNKS = 4;
    static final int SPAWN_ISLAND_RADIUS = 6;
    // La banda vertical se reduce a proposito: las rutas de parkour suben como
    // maximo un bloque por plataforma y deben poder alcanzar la isla vecina.
    private static final int MIN_ISLAND_Y = 104;
    private static final int ISLAND_Y_RANGE = 32;
    static final int CHEST_OFFSET = 2;

    private final int spacingChunks;
    private final int spawnTopY;
    private final int spawnCenterX;
    private final int spawnCenterZ;

    public OdysseyBlockPopulator(int spacingChunks, int spawnTopY, double spawnX, double spawnZ) {
        this.spacingChunks = Math.max(MIN_SPACING_CHUNKS, spacingChunks);
        this.spawnTopY = spawnTopY;
        this.spawnCenterX = (int) Math.floor(spawnX);
        this.spawnCenterZ = (int) Math.floor(spawnZ);
    }

    @Override
    public void populate(WorldInfo worldInfo, java.util.Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (Math.floorMod(chunkX, spacingChunks) != 0 || Math.floorMod(chunkZ, spacingChunks) != 0) {
            return;
        }

        boolean spawnIsland = chunkX == 0 && chunkZ == 0;
        long cellX = Math.floorDiv(chunkX, spacingChunks);
        long cellZ = Math.floorDiv(chunkZ, spacingChunks);
        long islandSeed = worldInfo.getSeed()
                ^ (cellX * 341_873_128_712L)
                ^ (cellZ * 132_897_987_541L);
        SplittableRandom islandRandom = new SplittableRandom(islandSeed);

        int centerX = spawnIsland ? spawnCenterX : chunkX * 16 + 8;
        int centerZ = spawnIsland ? spawnCenterZ : chunkZ * 16 + 8;
        int radius = spawnIsland ? SPAWN_ISLAND_RADIUS : 4 + islandRandom.nextInt(4);
        int topY = spawnIsland ? spawnTopY : MIN_ISLAND_Y + islandRandom.nextInt(ISLAND_Y_RANGE);
        topY = clamp(topY, worldInfo.getMinHeight() + 6, worldInfo.getMaxHeight() - 4);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double edgeNoise = 0.86
                        + 0.12 * Math.sin(Math.atan2(dz, dx) * 5.0 + islandRandom.nextDouble())
                        + 0.06 * Math.sin(Math.atan2(dz, dx) * 9.0 - islandRandom.nextDouble());
                double distance = Math.sqrt(dx * dx + dz * dz) / radius;
                if (distance > edgeNoise) {
                    continue;
                }
                if (!spawnIsland && islandRandom.nextDouble() < 0.12) {
                    continue;
                }

                int x = centerX + dx;
                int z = centerZ + dz;
                if (x < chunkX * 16 || x > chunkX * 16 + 15
                        || z < chunkZ * 16 || z > chunkZ * 16 + 15) {
                    continue;
                }

                int thickness = spawnIsland ? 3 + islandRandom.nextInt(2) : 2 + islandRandom.nextInt(3);
                for (int dy = 0; dy < thickness; dy++) {
                    int y = topY - dy;
                    if (y <= worldInfo.getMinHeight() + 1) {
                        break;
                    }
                    if (!region.isInRegion(x, y, z)) {
                        continue;
                    }

                    Material material;
                    if (dy == 0) {
                        if (spawnIsland) {
                            material = distance < 0.72 ? Material.OBSIDIAN : Material.WHITE_TERRACOTTA;
                        } else {
                            material = topMaterial(islandRandom);
                        }
                    } else {
                        material = shellMaterial(islandRandom);
                    }
                    region.setType(x, y, z, material);
                }
            }
        }

        // Un cofre por isla. El soporte se fuerza para que nunca quede flotando
        // sobre un hueco del borde y para que la ubicacion sea determinista.
        int chestX = centerX + CHEST_OFFSET;
        int chestZ = centerZ + CHEST_OFFSET;
        int chestY = topY + 1;
        if (region.isInRegion(chestX, topY, chestZ) && region.isInRegion(chestX, chestY, chestZ)) {
            region.setType(chestX, topY, chestZ, Material.OBSIDIAN);
            region.setType(chestX, chestY, chestZ, Material.CHEST);
        }
    }

    /**
     * Radio determinista de una isla. Replica el primer valor que consume el
     * populator y permite que las rutas de parkour conozcan el borde sin
     * depender del orden de generacion de los chunks vecinos.
     */
    static int islandRadius(WorldInfo worldInfo, int spacingChunks, int chunkX, int chunkZ) {
        if (chunkX == 0 && chunkZ == 0) {
            return SPAWN_ISLAND_RADIUS;
        }
        return 4 + islandRandom(worldInfo, spacingChunks, chunkX, chunkZ).nextInt(4);
    }

    /**
     * Altura superior determinista de una isla. El segundo valor aleatorio es
     * el mismo que usa {@link #populate}; no se modifica la secuencia original.
     */
    static int islandTopY(WorldInfo worldInfo, int spacingChunks, int chunkX, int chunkZ, int spawnTopY) {
        if (chunkX == 0 && chunkZ == 0) {
            return spawnTopY;
        }
        SplittableRandom random = islandRandom(worldInfo, spacingChunks, chunkX, chunkZ);
        random.nextInt(4); // radio, consumido igual que en populate()
        int topY = MIN_ISLAND_Y + random.nextInt(ISLAND_Y_RANGE);
        return clamp(topY, worldInfo.getMinHeight() + 6, worldInfo.getMaxHeight() - 4);
    }

    private static SplittableRandom islandRandom(WorldInfo worldInfo, int spacingChunks, int chunkX, int chunkZ) {
        int spacing = Math.max(MIN_SPACING_CHUNKS, spacingChunks);
        long cellX = Math.floorDiv(chunkX, spacing);
        long cellZ = Math.floorDiv(chunkZ, spacing);
        long islandSeed = worldInfo.getSeed()
                ^ (cellX * 341_873_128_712L)
                ^ (cellZ * 132_897_987_541L);
        return new SplittableRandom(islandSeed);
    }

    private static Material topMaterial(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 45) {
            return Material.WHITE_TERRACOTTA;
        }
        if (roll < 70) {
            return Material.OBSIDIAN;
        }
        if (roll < 90) {
            return Material.GLASS;
        }
        return Material.COMMAND_BLOCK;
    }

    private static Material shellMaterial(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 45) {
            return Material.OBSIDIAN;
        }
        if (roll < 70) {
            return Material.GLASS;
        }
        if (roll < 95) {
            return Material.WHITE_TERRACOTTA;
        }
        return Material.COMMAND_BLOCK;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
