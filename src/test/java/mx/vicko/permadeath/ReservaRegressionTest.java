package mx.vicko.permadeath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Paso 6 (DeepSeek E): regresiones de la Reserva.
 * Los tests unitarios cubren matemática pura del generador y contratos públicos,
 * sin servidor. Las verificaciones con mundo vivo requieren Paper y quedan
 * {@code @Disabled} con pasos manuales (los mocks no acreditan comportamiento visual).
 */
@DisplayName("ReservaRegressionTest (Paso 6)")
class ReservaRegressionTest {

    @Test
    @DisplayName("floorHeight es determinista por columna")
    void floorIsDeterministic() {
        assertEquals(ReservaPodrida.getReserveFloorHeight(10, -20), ReservaPodrida.getReserveFloorHeight(10, -20));
        assertEquals(ReservaPodrida.getReserveFloorHeight(-300, 400), ReservaPodrida.getReserveFloorHeight(-300, 400));
    }

    @Test
    @DisplayName("floorHeight continuo entre columnas vecinas (sin huecos)")
    void floorIsContinuous() {
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z += 7) {
                int a = ReservaPodrida.getReserveFloorHeight(x, z);
                int b = ReservaPodrida.getReserveFloorHeight(x + 1, z);
                int c = ReservaPodrida.getReserveFloorHeight(x, z + 1);
                assertTrue(Math.abs(a - b) <= 3, "salto en x=" + x + ",z=" + z);
                assertTrue(Math.abs(a - c) <= 3, "salto en z=" + x + "," + z);
            }
        }
    }

    @Test
    @DisplayName("floorHeight dentro de rango jugable")
    void floorInRange() {
        for (int x = -200; x <= 200; x += 13) {
            for (int z = -200; z <= 200; z += 17) {
                int floor = ReservaPodrida.getReserveFloorHeight(x, z);
                assertTrue(floor >= 25 && floor <= 65, "floor fuera de rango: " + floor);
            }
        }
    }

    @Test
    @DisplayName("techo de caverna continuo en bordes de chunk (tapa bedrock sin huecos)")
    void roofContinuousAcrossChunks() {
        for (int z = -64; z < 64; z++) {
            int west = ReservaPodrida.reserveRoofHeight(15, z);
            int east = ReservaPodrida.reserveRoofHeight(16, z);
            assertTrue(Math.abs(west - east) <= 1, "hueco de tapa en z=" + z);
        }
    }

    @Test
    @DisplayName("tapa bedrock por encima del techo real en toda columna")
    void bedrockCapAboveRoof() {
        for (int x = -48; x <= 48; x += 5) {
            for (int z = -48; z <= 48; z += 5) {
                int cap = ReservaPodrida.reserveRoofHeight(x, z) + 3;
                int floor = ReservaPodrida.getReserveFloorHeight(x, z);
                assertTrue(cap > floor + 3, "tapa bajo el suelo en " + x + "," + z);
            }
        }
    }

    @Test
    @DisplayName("contrato isCustomPortal existe y es público (DifficultyListener lo usa por reflexión)")
    void customPortalContractExists() throws Exception {
        var method = ReservaPortalController.class.getMethod("isCustomPortal", org.bukkit.Location.class);
        assertEquals(boolean.class, method.getReturnType());
    }

    @Test
    @DisplayName("contrato createMask es público (fábrica para /pd mascara)")
    void createMaskContractExists() throws Exception {
        var method = ReservaPodrida.class.getMethod("createMask");
        assertEquals(org.bukkit.inventory.ItemStack.class, method.getReturnType());
    }

    @Test
    @Disabled("Requiere servidor Paper vivo: 20 viajes ida/vuelta caminando, corriendo, mismo bloque, espera y reconexión; verificar que nunca se sale a Nether ni hay explosión/flick.")
    void portalRoundTrips() {
        fail("solo servidor");
    }

    @Test
    @Disabled("Requiere servidor Paper vivo: cofres nuevos con loot una sola vez, sin refill al vaciar ni loot en cofres de jugador; cofres dobles y legacy solo en ruinas identificables.")
    void chestLootIdempotence() {
        fail("solo servidor");
    }

    @Test
    @Disabled("Requiere servidor Paper vivo: receta HONEYCOMB+CHARCOAL+PAPER produce 2 cartuchos, visible en libro tras join; textura del ZIP cargado en cliente.")
    void filterRecipeAndPack() {
        fail("solo servidor");
    }

    @Test
    @Disabled("Requiere servidor Paper vivo: bedrock continuo en fondo y sobre techo en chunks nuevos, mundo v7 intacto, sin regenerar construcciones.")
    void bedrockInGame() {
        fail("solo servidor");
    }

    @Test
    @DisplayName("rango Y del barrido cubre chestY = floor+1 y respeta limites 30..95")
    void chestScanRangeCoversRuinChests() {
        assertTrue(ReservaPodrida.isReserveChestScanY(30));
        assertTrue(ReservaPodrida.isReserveChestScanY(60));
        assertTrue(ReservaPodrida.isReserveChestScanY(94));
        assertFalse(ReservaPodrida.isReserveChestScanY(29));
        assertFalse(ReservaPodrida.isReserveChestScanY(95));
        for (int x = -200; x <= 200; x += 13) {
            for (int z = -200; z <= 200; z += 17) {
                int chestY = ReservaPodrida.getReserveFloorHeight(x, z) + 1;
                assertTrue(ReservaPodrida.isReserveChestScanY(chestY), "cofre de ruina fuera del barrido: y=" + chestY);
            }
        }
    }

    @Test
    @DisplayName("relleno unico: solo null+vacio+ruina se rellena; 0 o 1 nunca se rellena")
    void chestFillOnceDecision() {
        // Unico caso que rellena: cofre sin bandera, vacio y en ruina identificable.
        assertTrue(ReservaPodrida.shouldFillReserveChest(null, true, true));
        // Las otras 3 combinaciones sin bandera no rellenan.
        assertFalse(ReservaPodrida.shouldFillReserveChest(null, true, false));
        assertFalse(ReservaPodrida.shouldFillReserveChest(null, false, true));
        assertFalse(ReservaPodrida.shouldFillReserveChest(null, false, false));
        // Un cofre con bandera 0 (saqueado) o 1 (rellenado una vez) nunca se rellena.
        assertFalse(ReservaPodrida.shouldFillReserveChest((byte) 1, true, true));
        assertFalse(ReservaPodrida.shouldFillReserveChest((byte) 0, true, true));
        assertFalse(ReservaPodrida.shouldFillReserveChest((byte) 1, false, true));
        assertFalse(ReservaPodrida.shouldFillReserveChest((byte) 0, false, true));
    }
}
