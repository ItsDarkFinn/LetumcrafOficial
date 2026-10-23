package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** Regresión del día 35: configs 0.2.44 con per-enderman:1 conservan 1:1. */
class EndThreatControllerTest {

    @Test
    void legacyConfigWithoutModernKeyUsesPerEnderman() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(EndThreatController.LEGACY_RATIO_KEY, 1);

        assertFalse(EndThreatController.usesModernRatio(config));
        assertEquals(1, EndThreatController.legacyCreepersPerEnderman(config));
    }

    @Test
    void modernKeyTakesPriorityAndKeepsDensity() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(EndThreatController.LEGACY_RATIO_KEY, 1);
        config.set(EndThreatController.MODERN_RATIO_KEY, 5);

        assertTrue(EndThreatController.usesModernRatio(config));
        assertEquals(5, EndThreatController.modernEndermansPerCreeper(config));
    }

    @Test
    void invalidOrMissingValuesFallBackSafely() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(EndThreatController.MODERN_RATIO_KEY, 0);
        config.set(EndThreatController.LEGACY_RATIO_KEY, 0);

        assertEquals(1, EndThreatController.modernEndermansPerCreeper(config));
        assertEquals(1, EndThreatController.legacyCreepersPerEnderman(config));
        assertFalse(EndThreatController.usesModernRatio(null));
        assertEquals(5, EndThreatController.modernEndermansPerCreeper(null));
        assertEquals(1, EndThreatController.legacyCreepersPerEnderman(null));
    }
}
