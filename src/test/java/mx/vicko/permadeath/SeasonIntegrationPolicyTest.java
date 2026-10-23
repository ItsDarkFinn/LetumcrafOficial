package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B1 — Política pura de temporada (sin Bukkit, sin cliente, sin stubs).
 * Cubre compresión de calendario, roster cerrado, gate único, suspensión N&lt;4
 * e idempotencia del estado durable.
 */
final class SeasonIntegrationPolicyTest {
    @Test
    void legacyDaysCompressWithForty() {
        assertEquals(8, SeasonCalendar.phaseDay(20, 40));
        assertEquals(14, SeasonCalendar.phaseDay(35, 40));
        assertEquals(4, SeasonCalendar.phaseDay(10, 40));
        assertEquals(2, SeasonCalendar.phaseDay(5, 40));
    }

    @Test
    void hundredDaysKeepsLegacyThresholds() {
        for (int phase : SeasonCalendar.SUPPORTED_PHASES) {
            assertEquals(phase, SeasonCalendar.phaseDay(phase, 100));
        }
    }

    @Test
    void actsWithFortyMatchPlan() {
        assertEquals(1, SeasonCalendar.actForDay(1, 40));
        assertEquals(1, SeasonCalendar.actForDay(3, 40));
        assertEquals(2, SeasonCalendar.actForDay(4, 40));
        assertEquals(3, SeasonCalendar.actForDay(14, 40));
        assertEquals(4, SeasonCalendar.actForDay(16, 40));
        assertEquals(5, SeasonCalendar.actForDay(24, 40));
        assertEquals(6, SeasonCalendar.actForDay(32, 40));
        assertEquals(7, SeasonCalendar.actForDay(40, 40));
    }

    @Test
    void rosterClosedListWinsOverFallback() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SeasonRoster roster = new SeasonRoster(List.of(a, b));
        assertEquals(2, roster.effectiveSize(16));
        assertTrue(roster.isMember(a));
        assertFalse(roster.wouldAdmit(UUID.randomUUID()));
        assertEquals(1, roster.aliveCount(Set.of(b)));
    }

    @Test
    void emptyRosterFallsBackWithoutInventingUuids() {
        SeasonRoster roster = SeasonRoster.empty();
        assertEquals(16, roster.effectiveSize());
        assertEquals(0, roster.aliveCount(Set.of(UUID.randomUUID())));
        assertFalse(roster.wouldAdmit(UUID.randomUUID()));
    }

    @Test
    void gateIsMaxOfPercentAndConfiguredMin() {
        // M=16 al 40% -> 7, pero el minimum-online-players heredado (15) prevalece.
        assertEquals(7, SeasonRoster.requiredOnline(16, 40, 0));
        assertEquals(15, SeasonRoster.requiredOnline(16, 40, 15));
        assertEquals(2, SeasonRoster.requiredOnline(1, 0, 0));
    }

    @Test
    void eligibleCountsOnlyRosterMinusEliminated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        SeasonRoster roster = new SeasonRoster(List.of(a, b));
        assertEquals(1, roster.eligibleOnlineCount(List.of(a, b, outsider), Set.of(b)));
        assertEquals(0, roster.eligibleOnlineCount(List.of(outsider), Set.of()));
    }

    @Test
    void suspensionOnlyAffectsEffects() {
        assertTrue(SeasonCalendar.arePhaseEffectsSuspended(3));
        assertFalse(SeasonCalendar.arePhaseEffectsSuspended(4));
        // El desbloqueo narrativo no depende de N.
        assertTrue(SeasonCalendar.isPhaseUnlocked(20, 8, 40));
    }

    @Test
    void stateMarksAreIdempotent() {
        SeasonState state = new SeasonState("temporada-1", 1, 40);
        assertTrue(state.markActAnnounced(2));
        assertFalse(state.markActAnnounced(2));
        assertTrue(state.markObjectiveCompleted("FORTALEZA"));
        assertFalse(state.markObjectiveCompleted("FORTALEZA"));
        assertTrue(state.markRewardPaid("FORTALEZA"));
        assertFalse(state.markRewardPaid("FORTALEZA"));
        assertEquals(state, state.copy());
    }
}
