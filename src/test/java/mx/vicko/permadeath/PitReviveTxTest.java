package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Bloque 3 — escrow de vuelta: intento persistido, idempotencia y tolerancia. */
public final class PitReviveTxTest {

    @Test
    void beginIsSingleFlight() {
        SeasonState state = new SeasonState("s3", 1, 40);
        UUID uuid = UUID.randomUUID();
        assertFalse(state.hasReviveTx(uuid));
        assertTrue(state.beginReviveTx(uuid, 2, true, 123L));
        assertTrue(state.hasReviveTx(uuid));
        assertFalse(state.beginReviveTx(uuid, 2, true, 123L));
        assertEquals(2, state.reviveTxCostOf(uuid));
        assertTrue(state.reviveTxTookCore(uuid));
        assertEquals(123L, state.reviveTxDeathOf(uuid));
    }

    @Test
    void beginRejectsInvalidWithoutMutating() {
        SeasonState state = new SeasonState("s3", 1, 40);
        assertFalse(state.beginReviveTx(null, 1, false, 1L));
        assertFalse(state.beginReviveTx(UUID.randomUUID(), 0, false, 1L));
        assertFalse(state.beginReviveTx(UUID.randomUUID(), -2, false, 1L));
        assertTrue(state.reviveTxIds().isEmpty());
    }

    @Test
    void clearReportsWhetherTxWasOpen() {
        SeasonState state = new SeasonState("s3", 1, 40);
        UUID uuid = UUID.randomUUID();
        assertFalse(state.clearReviveTx(uuid));
        assertFalse(state.clearReviveTx(null));
        assertTrue(state.beginReviveTx(uuid, 1, false, -1L));
        assertTrue(state.clearReviveTx(uuid));
        assertFalse(state.hasReviveTx(uuid));
        assertFalse(state.clearReviveTx(uuid));
    }

    @Test
    void loadIgnoresGarbageAndKeepsFirst() {
        SeasonState state = new SeasonState("s3", 1, 40);
        UUID uuid = UUID.randomUUID();
        state.loadReviveTx(List.of(
                uuid + "=2:1:777",
                "no-es-uuid=1:0:1",
                "sin-igual",
                uuid + "=9:0:9",
                UUID.randomUUID() + "=0:0:0",
                UUID.randomUUID() + "=abc:0:0"));
        assertTrue(state.hasReviveTx(uuid));
        assertEquals(2, state.reviveTxCostOf(uuid));
        assertTrue(state.reviveTxTookCore(uuid));
        assertEquals(777L, state.reviveTxDeathOf(uuid));
        assertEquals(1, state.reviveTxIds().size());
        state.loadReviveTx(null);
        assertTrue(state.hasReviveTx(uuid));
    }

    @Test
    void tokensRoundTripThroughCopy() {
        SeasonState state = new SeasonState("s3", 1, 40);
        UUID uuid = UUID.randomUUID();
        assertTrue(state.beginReviveTx(uuid, 3, false, 555L));
        SeasonState copy = state.copy();
        assertEquals(state, copy);
        assertTrue(copy.hasReviveTx(uuid));
        assertEquals(3, copy.reviveTxCostOf(uuid));
        assertFalse(copy.reviveTxTookCore(uuid));
        assertEquals(555L, copy.reviveTxDeathOf(uuid));
        assertEquals(state.hashCode(), copy.hashCode());
    }

    @Test
    void spendRefundAndIncrementStayConsistent() {
        SeasonState state = new SeasonState("s3", 1, 40);
        UUID uuid = UUID.randomUUID();
        state.addPitEarned(uuid, 10L);
        state.addPitEarned(uuid, 11L);
        assertTrue(state.spendPit(uuid, 1));
        assertEquals(1, state.pitBalanceOf(uuid));
        state.refundPit(uuid, 1);
        assertEquals(2, state.pitBalanceOf(uuid));
        assertFalse(state.spendPit(uuid, 5));
        assertEquals(0, state.revivalsOf(uuid));
        state.incrementRevivals(uuid);
        assertEquals(1, state.revivalsOf(uuid));
    }
}
