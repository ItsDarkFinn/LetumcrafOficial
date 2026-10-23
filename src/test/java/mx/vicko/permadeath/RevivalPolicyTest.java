package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Bloque 3 — Política de vueltas: caminos por roster, válvula y topes (puro). */
public final class RevivalPolicyTest {

    @Test
    void fullRitualNeedsTwoAlivePlusSealAndOpenRitual() {
        RevivalPolicy.Decision ok = RevivalPolicy.evaluate(9, 0, 1, true, 0, 0, true);
        assertTrue(ok.allowed());
        assertEquals(RevivalPolicy.Path.RITUAL_FULL, ok.path());
        assertEquals(1, ok.cost());
    }

    @Test
    void offlineAliveDoNotDiscountRosterPrice() {
        RevivalPolicy.Decision withEight = RevivalPolicy.evaluate(8, 0, 1, true, 0, 0, true);
        assertTrue(withEight.allowed());
        assertEquals(RevivalPolicy.Path.RITUAL_FULL, withEight.path());
        RevivalPolicy.Decision withoutRitual = RevivalPolicy.evaluate(8, 0, 1, false, 0, 0, true);
        assertFalse(withoutRitual.allowed());
    }

    @Test
    void twoAliveNeedsOneAlivePlusSeal() {
        RevivalPolicy.Decision ok = RevivalPolicy.evaluate(2, 1, 2, true, 0, 0, true);
        assertTrue(ok.allowed());
        assertEquals(RevivalPolicy.Path.RITUAL_MIN, ok.path());
        assertEquals(2, ok.cost());
        assertEquals(1, RevivalPolicy.requiredParticipants(2));
        assertEquals(2, RevivalPolicy.requiredParticipants(9));
    }

    @Test
    void oneOrZeroAliveUsesSoloAltarWithOneCoreToll() {
        RevivalPolicy.Decision solo = RevivalPolicy.evaluate(1, 2, 3, false, 1, 0, false);
        assertTrue(solo.allowed());
        assertEquals(RevivalPolicy.Path.ALTAR_SOLO, solo.path());
        assertEquals(3, solo.cost());
        RevivalPolicy.Decision noCore = RevivalPolicy.evaluate(0, 0, 1, false, 0, 0, false);
        assertFalse(noCore.allowed());
    }

    @Test
    void fiveDayValveAllowsSoloAltarEvenWithAlive() {
        RevivalPolicy.Decision valve = RevivalPolicy.evaluate(9, 0, 1, false, 1, 5, false);
        assertTrue(valve.allowed());
        assertEquals(RevivalPolicy.Path.VALVE_SOLO, valve.path());
        RevivalPolicy.Decision early = RevivalPolicy.evaluate(9, 0, 1, false, 1, 4, false);
        assertFalse(early.allowed());
        // La válvula no exige peaje de Núcleo: con banco a 0 y Chispas suficientes autoriza.
        RevivalPolicy.Decision noToll = RevivalPolicy.evaluate(9, 0, 1, false, 0, 9, false);
        assertTrue(noToll.allowed());
        assertEquals(RevivalPolicy.Path.VALVE_SOLO, noToll.path());
    }

    @Test
    void thirdRevivalCostsThreeAndFourthBlocked() {
        RevivalPolicy.Decision third = RevivalPolicy.evaluate(1, 2, 3, false, 5, 0, false);
        assertTrue(third.allowed());
        assertEquals(3, third.cost());
        RevivalPolicy.Decision fourth = RevivalPolicy.evaluate(1, 3, 6, false, 99, 99, true);
        assertFalse(fourth.allowed());
        assertEquals(RevivalPolicy.Path.DENIED, fourth.path());
    }

    @Test
    void missingChispasReportedWithoutSpam() {
        RevivalPolicy.Decision poor = RevivalPolicy.evaluate(9, 1, 1, true, 0, 0, true);
        assertFalse(poor.allowed());
        assertEquals(2, poor.cost());
        assertFalse(poor.missing().isEmpty());
    }

    @Test
    void seasonEndSignalledOnlyWithZeroRosterAlive() {
        assertTrue(RevivalPolicy.shouldSignalSeasonEnd(0));
        assertFalse(RevivalPolicy.shouldSignalSeasonEnd(1));
        assertFalse(RevivalPolicy.shouldSignalSeasonEnd(8));
    }
}
