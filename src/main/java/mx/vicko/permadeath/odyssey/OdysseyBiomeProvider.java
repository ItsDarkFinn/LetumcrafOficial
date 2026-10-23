package mx.vicko.permadeath.odyssey;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

/**
 * Bioma único del vacío. Evita que el generador vanilla introduzca biomas de
 * superficie en los chunks nuevos de La Falla.
 */
public final class OdysseyBiomeProvider extends BiomeProvider {
    private static final List<Biome> BIOMES = List.of(Biome.THE_END);

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return Biome.THE_END;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return BIOMES;
    }
}
