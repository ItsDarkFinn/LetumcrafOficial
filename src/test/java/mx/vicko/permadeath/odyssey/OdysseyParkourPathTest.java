package mx.vicko.permadeath.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class OdysseyParkourPathTest {
    private static final int MIN_TOP_Y = 104;
    private static final int MAX_TOP_Y = 135;
    private static final int MIN_PATH_LENGTH = 108;
    private static final int MAX_PATH_LENGTH = 114;

    @Test
    void everyHeightPairCanReachTheTargetIsland() {
        for (int length = MIN_PATH_LENGTH; length <= MAX_PATH_LENGTH; length++) {
            for (int startY = MIN_TOP_Y; startY <= MAX_TOP_Y; startY++) {
                for (int endY = MIN_TOP_Y; endY <= MAX_TOP_Y; endY++) {
                    List<OdysseyParkourPath.Platform> platforms =
                            OdysseyParkourPath.plan(0, length, startY, endY);

                    assertFalse(platforms.isEmpty(), "sin plataformas");
                    assertEquals(startY, platforms.get(0).y());
                    assertEquals(endY, platforms.get(platforms.size() - 1).y(),
                            "la ruta no alcanza la isla destino: " + startY + " -> " + endY);
                    assertJumpsAreReachable(platforms);
                }
            }
        }
    }

    @Test
    void gapsStayBetweenTwoAndFourBlocks() {
        List<OdysseyParkourPath.Platform> platforms =
                OdysseyParkourPath.plan(0, 108, MIN_TOP_Y, MAX_TOP_Y);
        for (int index = 1; index < platforms.size(); index++) {
            int gap = platforms.get(index).position() - platforms.get(index - 1).position();
            assertTrue(gap >= OdysseyParkourPath.MIN_GAP, "hueco demasiado corto: " + gap);
            assertTrue(gap <= OdysseyParkourPath.MAX_GAP, "hueco demasiado largo: " + gap);
        }
    }

    @Test
    void platformsAlternateRecognizableMaterials() {
        assertEquals(Material.CRYING_OBSIDIAN, OdysseyParkourPopulator.platformMaterial(11));
        assertEquals(Material.GLASS, OdysseyParkourPopulator.platformMaterial(6));
        assertEquals(Material.OBSIDIAN, OdysseyParkourPopulator.platformMaterial(0));
    }

    private static void assertJumpsAreReachable(List<OdysseyParkourPath.Platform> platforms) {
        for (int index = 1; index < platforms.size(); index++) {
            OdysseyParkourPath.Platform previous = platforms.get(index - 1);
            OdysseyParkourPath.Platform current = platforms.get(index);
            int gap = current.position() - previous.position();
            int verticalChange = current.y() - previous.y();
            assertTrue(gap >= 1 && gap <= OdysseyParkourPath.MAX_GAP,
                    "salto horizontal invalido: " + gap);
            assertTrue(Math.abs(verticalChange) <= 1,
                    "salto vertical invalido: " + verticalChange);
        }
    }
}
