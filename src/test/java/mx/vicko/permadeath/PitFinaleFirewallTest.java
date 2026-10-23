package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Bloque 4 — firewall de comandos de la Fosa: /pd final accesible a todo el
 * roster incluidos muertos; el resto ajeno sigue bloqueado.
 */
class PitFinaleFirewallTest {

    @Test
    void finaleVisibleToContainedDead() {
        assertTrue(PitController.isAllowedWhileContained("/pd final"));
        assertTrue(PitController.isAllowedWhileContained("/pd final pagina 2"));
        assertTrue(PitController.isAllowedWhileContained("/permadeath final"));
    }

    @Test
    void pitCommandsStillAllowed() {
        assertTrue(PitController.isAllowedWhileContained("/pd fosa"));
        assertTrue(PitController.isAllowedWhileContained("/pd fosa empezar"));
        assertTrue(PitController.isAllowedWhileContained("/pd temporada"));
        assertTrue(PitController.isAllowedWhileContained("/pd actos"));
        assertTrue(PitController.isAllowedWhileContained("/permadeath fosa estado"));
        assertTrue(PitController.isAllowedWhileContained("/permadeath temporada"));
    }

    @Test
    void unrelatedCommandsStillBlocked() {
        assertFalse(PitController.isAllowedWhileContained("/pd banco"));
        assertFalse(PitController.isAllowedWhileContained("/pd banco sol confirmar x"));
        assertFalse(PitController.isAllowedWhileContained("/spawn"));
        assertFalse(PitController.isAllowedWhileContained("/home"));
        assertFalse(PitController.isAllowedWhileContained(null));
        assertFalse(PitController.isAllowedWhileContained(""));
    }
}
