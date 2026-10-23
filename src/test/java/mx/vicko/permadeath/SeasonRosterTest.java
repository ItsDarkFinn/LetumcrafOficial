package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M real vs fallback, N sin conectados, sin autoincluir, gate unico. */
class SeasonRosterTest {

    private static UUID uuid(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    @Test
    void explicitListIsSourceOfTruthForM() {
        SeasonRoster roster = new SeasonRoster(List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5)));
        assertEquals(5, roster.size());
        assertEquals(5, roster.effectiveSize(16));
        assertEquals(5, roster.effectiveSize());
    }

    @Test
    void missingListUsesNumericFallbackWithoutInventingUuids() {
        SeasonRoster roster = SeasonRoster.empty();
        assertTrue(roster.isEmpty());
        assertEquals(16, roster.effectiveSize());
        assertEquals(9, roster.effectiveSize(9));
        assertTrue(roster.members().isEmpty());
    }

    @Test
    void duplicatesAndNullsCountOnce() {
        SeasonRoster roster = new SeasonRoster(List.of(uuid(1), uuid(1), uuid(2)));
        assertEquals(2, roster.size());
    }

    @Test
    void aliveExcludesEliminatedAndIgnoresOutsiders() {
        SeasonRoster roster = new SeasonRoster(List.of(uuid(1), uuid(2), uuid(3), uuid(4)));
        assertEquals(4, roster.aliveCount(null));
        assertEquals(3, roster.aliveCount(Set.of(uuid(2))));
        // Eliminado fuera del roster no resta.
        assertEquals(4, roster.aliveCount(Set.of(uuid(99))));
    }

    @Test
    void noAutoInclude() {
        SeasonRoster roster = new SeasonRoster(List.of(uuid(1), uuid(2)));
        assertFalse(roster.isMember(uuid(3)));
        assertFalse(roster.wouldAdmit(uuid(3)));
        assertTrue(roster.wouldAdmit(uuid(1)));
    }

    @Test
    void gateIsMaxOfFloorPercentAndConfigured() {
        // 16 al 40% -> ceil(6.4)=7.
        assertEquals(7, SeasonRoster.requiredOnline(16, 40, 0));
        // minimum-online-players mayor prevalece: el 15 heredado domina al 7.
        assertEquals(15, SeasonRoster.requiredOnline(16, 40, 15));
        // Piso minimo 2 aunque el porcentaje de 0.
        assertEquals(2, SeasonRoster.requiredOnline(1, 0, 0));
        // 16 al 40% -> 7; minimum-online-players=1 no domina al porcentaje.
        assertEquals(7, SeasonRoster.requiredOnline(16, 40, 1));
        // Config domina cuando supera al porcentaje y al piso.
        assertEquals(5, SeasonRoster.requiredOnline(1, 0, 5));
    }

    @Test
    void eligibleOnlineCountsOnlyRosterNonEliminated() {
        SeasonRoster roster = new SeasonRoster(List.of(uuid(1), uuid(2), uuid(3)));
        // uuid(9) conectado pero fuera del roster no cuenta; uuid(3) eliminado no cuenta.
        assertEquals(1, roster.eligibleOnlineCount(
                List.of(uuid(1), uuid(3), uuid(9)), Set.of(uuid(3))));
        assertEquals(0, SeasonRoster.empty().eligibleOnlineCount(List.of(uuid(1)), Set.of()));
    }

    @Test
    void effectsSuspendedBelow4() {
        assertTrue(SeasonRoster.areEffectsSuspended(3));
        assertFalse(SeasonRoster.areEffectsSuspended(4));
    }
}
