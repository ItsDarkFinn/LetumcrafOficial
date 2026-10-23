package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tablas del plan 30/40, monotonia, fase 100 al final y compatibilidad 100. */
class SeasonCalendarTest {

    private static final int[] EXPECTED_40 = {4, 8, 10, 14, 16, 20, 24, 28, 32, 36, 40};
    private static final int[] EXPECTED_30 = {3, 6, 8, 11, 12, 15, 18, 21, 24, 27, 30};

    @Test
    void table40MatchesPlan() {
        for (int i = 0; i < SeasonCalendar.SUPPORTED_PHASES.length; i++) {
            assertEquals(EXPECTED_40[i],
                    SeasonCalendar.phaseDay(SeasonCalendar.SUPPORTED_PHASES[i], 40),
                    "fase " + SeasonCalendar.SUPPORTED_PHASES[i] + " con 40 dias");
        }
    }

    @Test
    void table30MatchesPlan() {
        for (int i = 0; i < SeasonCalendar.SUPPORTED_PHASES.length; i++) {
            assertEquals(EXPECTED_30[i],
                    SeasonCalendar.phaseDay(SeasonCalendar.SUPPORTED_PHASES[i], 30),
                    "fase " + SeasonCalendar.SUPPORTED_PHASES[i] + " con 30 dias");
        }
    }

    @Test
    void strictlyMonotonicForAllValidLengths() {
        for (int len = 30; len <= 45; len++) {
            int prev = 0;
            for (int phase : SeasonCalendar.SUPPORTED_PHASES) {
                int day = SeasonCalendar.phaseDay(phase, len);
                assertTrue(day > prev, "len=" + len + " fase=" + phase);
                prev = day;
            }
        }
    }

    @Test
    void phase100IsAlwaysLastDay() {
        for (int len = 30; len <= 45; len++) {
            assertEquals(len, SeasonCalendar.phaseDay(100, len), "len=" + len);
        }
        assertEquals(100, SeasonCalendar.phaseDay(100, 100));
    }

    @Test
    void length100ReproducesLegacyThresholds() {
        for (int phase : SeasonCalendar.SUPPORTED_PHASES) {
            assertEquals(phase, SeasonCalendar.phaseDay(phase, 100),
                    "compat fase " + phase);
        }
    }

    @Test
    void day8UnlocksPhase20With40Days() {
        assertTrue(SeasonCalendar.isPhaseUnlocked(20, 8, 40));
        assertTrue(SeasonCalendar.isPhaseUnlocked(10, 8, 40));
        assertFalse(SeasonCalendar.isPhaseUnlocked(25, 8, 40));
        assertFalse(SeasonCalendar.isPhaseUnlocked(20, 7, 40));
    }

    @Test
    void actsWith40DaysMatchPlan() {
        assertEquals(1, SeasonCalendar.actForDay(1, 40));
        assertEquals(1, SeasonCalendar.actForDay(3, 40));
        assertEquals(2, SeasonCalendar.actForDay(4, 40));
        assertEquals(2, SeasonCalendar.actForDay(13, 40));
        assertEquals(3, SeasonCalendar.actForDay(14, 40));
        assertEquals(3, SeasonCalendar.actForDay(15, 40));
        assertEquals(4, SeasonCalendar.actForDay(16, 40));
        assertEquals(4, SeasonCalendar.actForDay(23, 40));
        assertEquals(5, SeasonCalendar.actForDay(24, 40));
        assertEquals(5, SeasonCalendar.actForDay(31, 40));
        assertEquals(6, SeasonCalendar.actForDay(32, 40));
        assertEquals(6, SeasonCalendar.actForDay(39, 40));
        assertEquals(7, SeasonCalendar.actForDay(40, 40));
    }

    @Test
    void actBoundsRoundTrip() {
        for (int len = 30; len <= 45; len++) {
            int cursor = 1;
            for (int act = 1; act <= SeasonCalendar.ACT_COUNT; act++) {
                assertEquals(cursor, SeasonCalendar.actStartDay(act, len),
                        "len=" + len + " act=" + act);
                int end = SeasonCalendar.actEndDay(act, len);
                assertTrue(end >= cursor, "len=" + len + " act=" + act);
                assertEquals(act, SeasonCalendar.actForDay(cursor, len));
                assertEquals(act, SeasonCalendar.actForDay(end, len));
                cursor = end + 1;
            }
            assertEquals(len + 1, cursor, "los actos cubren 1..len sin huecos (len=" + len + ")");
        }
    }

    @Test
    void extensionDelaysEndWithoutRelockingPhases() {
        assertEquals(8, SeasonCalendar.phaseDay(20, 40));
        assertTrue(SeasonCalendar.isPhaseUnlocked(20, 8, 40));
        assertEquals(9, SeasonCalendar.phaseDay(20, 43));
        assertTrue(SeasonCalendar.isPhaseUnlocked(20, 8, 40));
    }

    @Test
    void effectsSuspendedBelow4Alive() {
        assertTrue(SeasonCalendar.arePhaseEffectsSuspended(3));
        assertFalse(SeasonCalendar.arePhaseEffectsSuspended(4));
    }
}
