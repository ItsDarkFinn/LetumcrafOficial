package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Bloque 3 — Chispas: costes, topes y válvula (puro, tiempo inyectado). */
public final class PitLedgerTest {

    @Test
    void nextCostEscalatesOneTwoThree() {
        assertEquals(1, PitLedger.nextCost(0));
        assertEquals(2, PitLedger.nextCost(1));
        assertEquals(3, PitLedger.nextCost(2));
    }

    @Test
    void fourthRevivalBlockedEvenWithAssets() {
        assertTrue(PitLedger.isRevivalMaxed(3));
        assertTrue(PitLedger.isRevivalMaxed(9));
        assertFalse(PitLedger.isRevivalMaxed(2));
    }

    @Test
    void earnOnePerRunOnePerRealDay() {
        long day = PitLedger.epochDay(1_700_000_000_000L);
        assertTrue(PitLedger.canEarn(0, -1L, day));
        assertFalse(PitLedger.canEarn(1, day, day));
        assertTrue(PitLedger.canEarn(1, day, day + 1));
    }

    @Test
    void sixEarnedCapBlocksEvenAcrossRestarts() {
        long day = 20_000L;
        assertFalse(PitLedger.canEarn(6, day - 1, day));
        assertFalse(PitLedger.canEarn(7, day - 1, day));
        assertTrue(PitLedger.canEarn(5, day - 1, day));
    }

    @Test
    void spentDoesNotReopenEarnCap() {
        assertEquals(4, PitLedger.balance(6, 2));
        assertFalse(PitLedger.canEarn(6, 0L, 1L));
    }

    @Test
    void runDurationClampedTenToFifteenDefaultTwelve() {
        assertEquals(12, PitLedger.clampRunMinutes(12));
        assertEquals(10, PitLedger.clampRunMinutes(10));
        assertEquals(15, PitLedger.clampRunMinutes(15));
        assertEquals(12, PitLedger.clampRunMinutes(5));
        assertEquals(12, PitLedger.clampRunMinutes(60));
    }

    @Test
    void valveNeedsFiveRealDaysAndEnoughChispas() {
        assertTrue(PitLedger.valveApplies(5, 1, 1));
        assertFalse(PitLedger.valveApplies(4, 1, 1));
        assertFalse(PitLedger.valveApplies(5, 0, 1));
        assertEquals(5L, PitLedger.daysSinceDeath(0L + 1_000L, 1_000L + 5 * PitLedger.MILLIS_PER_DAY));
        assertEquals(4L, PitLedger.daysSinceDeath(1_000L, 1_000L + 4 * PitLedger.MILLIS_PER_DAY));
    }
}
