package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Irreversibilidad del sacrificio por UUID + persistencia de cuenta sin muerte/reset. */
class SacrificeRegistryTest {

    @Test
    void firstChoiceWinsAndSecondIsRejected() {
        SacrificeRegistry registry = new SacrificeRegistry();
        UUID uuid = UUID.randomUUID();
        assertTrue(registry.choose(uuid, SacrificeType.ABRIGO));
        assertFalse(registry.choose(uuid, SacrificeType.OJO));
        assertEquals(SacrificeType.ABRIGO, registry.get(uuid).orElseThrow());
    }

    @Test
    void choiceSurvivesDeathResetAndReload() {
        SacrificeRegistry registry = new SacrificeRegistry();
        UUID uuid = UUID.randomUUID();
        assertTrue(registry.choose(uuid, SacrificeType.CORAZON));

        // Muerte/reset: rehidratar desde la foto persistida conserva la elección.
        Map<String, String> snapshot = registry.toStringMap();
        SacrificeRegistry reloaded = SacrificeRegistry.fromStringMap(snapshot);
        assertEquals(SacrificeType.CORAZON, reloaded.get(uuid).orElseThrow());
        // Y sigue siendo irreversible tras recargar.
        assertFalse(reloaded.choose(uuid, SacrificeType.CAMA));
        assertEquals(SacrificeType.CORAZON, reloaded.get(uuid).orElseThrow());
    }

    @Test
    void choicesArePerUuid() {
        SacrificeRegistry registry = new SacrificeRegistry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(registry.choose(a, SacrificeType.PULMON));
        assertTrue(registry.choose(b, SacrificeType.ESTOMAGO));
        assertEquals(SacrificeType.PULMON, registry.get(a).orElseThrow());
        assertEquals(SacrificeType.ESTOMAGO, registry.get(b).orElseThrow());
        assertEquals(2, registry.size());
    }

    @Test
    void corruptEntriesAreIgnoredNeverThrow() {
        SacrificeRegistry registry = SacrificeRegistry.fromStringMap(Map.of(
                "no-es-uuid", "ABRIGO",
                UUID.randomUUID().toString(), "NO_EXISTE"));
        assertEquals(0, registry.size());
    }
}
