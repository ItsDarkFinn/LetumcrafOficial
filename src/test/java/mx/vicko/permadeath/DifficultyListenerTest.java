package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

/**
 * Paso 2 (MiniMax A): regresiones de herramientas, cooldown, portales y Enderman.
 * Sin MockBukkit: solo lógica estática pura compatible con harness JUnit mínimo.
 */
class DifficultyListenerTest {

    @Test
    void anyNonAxeBreaksLogs() {
        // Todas las hachas válidas exentas, sin confundir pico/espada/mano.
        assertTrue(DifficultyListener.isAxe(Material.WOODEN_AXE));
        assertTrue(DifficultyListener.isAxe(Material.STONE_AXE));
        assertTrue(DifficultyListener.isAxe(Material.DIAMOND_AXE));
        assertTrue(DifficultyListener.isAxe(Material.NETHERITE_AXE));

        assertFalse(DifficultyListener.isAxe(Material.AIR));
        assertFalse(DifficultyListener.isAxe(Material.STICK));
        assertFalse(DifficultyListener.isAxe(Material.DIAMOND_PICKAXE));
        assertFalse(DifficultyListener.isAxe(Material.DIAMOND_SWORD));
        assertFalse(DifficultyListener.isAxe(Material.DIAMOND_SHOVEL));
    }

    @Test
    void goldenAppleCooldownIsEightSeconds() {
        // Manzana normal fase 70: 8s = 160 ticks; encantada no afectada (tipo distinto).
        assertEquals(160, DifficultyListener.GOLDEN_APPLE_COOLDOWN_TICKS);
    }

    @Test
    void endBlocksNeverTransformed() {
        assertTrue(DifficultyListener.isEndProtected(Material.END_PORTAL));
        assertTrue(DifficultyListener.isEndProtected(Material.END_GATEWAY));
        assertTrue(DifficultyListener.isEndProtected(Material.END_PORTAL_FRAME));

        assertFalse(DifficultyListener.isEndProtected(Material.NETHERRACK));
        assertFalse(DifficultyListener.isEndProtected(Material.OBSIDIAN));
        assertFalse(DifficultyListener.isEndProtected(Material.NETHER_PORTAL));
    }

    @Test
    void onlyAuthenticNetherPortalTriggers() {
        assertTrue(DifficultyListener.isNetherPortalCause(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));

        assertFalse(DifficultyListener.isNetherPortalCause(PlayerTeleportEvent.TeleportCause.END_PORTAL));
        assertFalse(DifficultyListener.isNetherPortalCause(PlayerTeleportEvent.TeleportCause.END_GATEWAY));
        assertFalse(DifficultyListener.isNetherPortalCause(PlayerTeleportEvent.TeleportCause.UNKNOWN));
    }
}
