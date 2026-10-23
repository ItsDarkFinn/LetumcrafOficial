package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bloque 2 — Ancla: el destino se valida ANTES de borrar/cobrar.
 * Puro (sin Bukkit): mundo con coma/apóstrofe, campos mal, coords no numéricas
 * y no-consumo en fallo; el banco cobra exactamente 1 con saldo 1.
 */
class SeasonAnchorTest {

    @Test
    void parseSimpleWorld() {
        SeasonService.AnchorTarget target = SeasonService.parseAnchorPacked("reserva,10,64,10");
        assertNotNull(target);
        assertEquals("reserva", target.worldName());
        assertEquals(10, target.x());
        assertEquals(64, target.y());
        assertEquals(10, target.z());
    }

    @Test
    void parseWorldWithCommaAndApostrophe() {
        SeasonService.AnchorTarget target = SeasonService.parseAnchorPacked("mundo, raro,10,64,10");
        assertNotNull(target);
        assertEquals("mundo, raro", target.worldName());
        assertEquals(10, target.x());
        assertEquals(64, target.y());
        assertEquals(10, target.z());

        SeasonService.AnchorTarget quoted = SeasonService.parseAnchorPacked("l'ile,1,2,3");
        assertNotNull(quoted);
        assertEquals("l'ile", quoted.worldName());
        assertEquals(1, quoted.x());
        assertEquals(2, quoted.y());
        assertEquals(3, quoted.z());
    }

    @Test
    void parseRejectsBadFieldCounts() {
        assertNull(SeasonService.parseAnchorPacked(null));
        assertNull(SeasonService.parseAnchorPacked("   "));
        assertNull(SeasonService.parseAnchorPacked("solo-dos-campos"));
        assertNull(SeasonService.parseAnchorPacked("mundo,10,64"));
        assertNull(SeasonService.parseAnchorPacked("mundo,,64,10"));
        assertNull(SeasonService.parseAnchorPacked(",10,64,10"));
    }

    @Test
    void parseRejectsNonNumericCoords() {
        assertNull(SeasonService.parseAnchorPacked("mundo,NaN,64,10"));
        assertNull(SeasonService.parseAnchorPacked("mundo,10,Infinity,10"));
        assertNull(SeasonService.parseAnchorPacked("mundo,10.5,64,10"));
        assertNull(SeasonService.parseAnchorPacked("mundo,abc,64,10"));
        assertNull(SeasonService.parseAnchorPacked("mundo,10,64,"));
    }

    @Test
    void peekDoesNotConsumeAndTakeRemoves() {
        SeasonState state = new SeasonState("s1");
        UUID uuid = UUID.randomUUID();
        state.putAnchorDeath(uuid, "mundo, raro,10,64,10");
        assertEquals("mundo, raro,10,64,10", state.peekAnchorDeath(uuid));
        // Peek no consume: sigue presente.
        assertEquals("mundo, raro,10,64,10", state.peekAnchorDeath(uuid));
        assertEquals("mundo, raro,10,64,10", state.takeAnchorDeath(uuid));
        assertNull(state.peekAnchorDeath(uuid));
        assertNull(state.takeAnchorDeath(uuid));
    }

    @Test
    void invalidEntrySurvivesStoreRoundTripByteEqual(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 20, 43);
        UUID uuid = UUID.randomUUID();
        // Entrada pendiente ilegible: el fichero la conserva tal cual.
        state.putAnchorDeath(uuid, "solo-dos-campos");
        store.save(state);
        byte[] first = Files.readAllBytes(store.stateFile());
        SeasonState loaded = store.load("s1");
        assertEquals("solo-dos-campos", loaded.anchorDeaths().get(uuid.toString()));
        // El parse puro la rechaza (no se consumiría ni cobraría en runtime).
        assertNull(SeasonService.parseAnchorPacked(loaded.anchorDeaths().get(uuid.toString())));
        store.save(loaded);
        assertEquals(new String(first, StandardCharsets.UTF_8),
                Files.readString(store.stateFile(), StandardCharsets.UTF_8));
    }

    @Test
    void bankConsumesExactlyOneWithBalanceOne() {
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(1));
        assertTrue(bank.consume(1));
        assertEquals(0, bank.balance());
        assertEquals(1, bank.totalSpent());
        // Sin overdraft: segundo consumo rechazado sin mutación.
        assertFalse(bank.consume(1));
        assertEquals(0, bank.balance());
        assertEquals(1, bank.totalSpent());
    }
}
