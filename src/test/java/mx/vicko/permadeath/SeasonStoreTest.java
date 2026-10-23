package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** season-state.yml: frescura, round-trip, idempotencia, corrupcion diagnosticada. */
class SeasonStoreTest {

    @Test
    void missingFileReturnsFreshState(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = store.load("s1");
        assertEquals("s1", state.seasonId());
        assertTrue(state.announcedActs().isEmpty());
        assertFalse(Files.exists(store.stateFile()));
    }

    @Test
    void roundTripPersistsActsObjectivesRewards(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 14, 40);
        state.markActAnnounced(3);
        state.markObjectiveAnnounced("ACT3_NUCLEO");
        state.markObjectiveCompleted("ACT3_NUCLEO");
        state.markRewardPaid("ACT3_NUCLEO");
        store.save(state);

        SeasonState loaded = store.load("s1");
        assertEquals(state, loaded);
        assertTrue(loaded.isActAnnounced(3));
        assertTrue(loaded.isObjectiveCompleted("ACT3_NUCLEO"));
        assertTrue(loaded.isRewardPaid("ACT3_NUCLEO"));
        assertEquals(14, loaded.day());
    }

    @Test
    void markingsAreIdempotentAndSaveIsByteIdempotent(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1");
        assertTrue(state.markActAnnounced(2));
        assertFalse(state.markActAnnounced(2));
        assertTrue(state.markObjectiveAnnounced("X"));
        assertFalse(state.markObjectiveAnnounced("X"));

        store.save(state);
        byte[] first = Files.readAllBytes(store.stateFile());
        store.save(store.load("s1"));
        assertEquals(new String(first, StandardCharsets.UTF_8),
                Files.readString(store.stateFile(), StandardCharsets.UTF_8));
    }

    @Test
    void roundTripPersistsB2BankEscrowSacrificesAnchorAndSolarFall(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 20, 43);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        assertTrue(state.setSacrifice(uuid, SacrificeType.ANCLA));
        state.setBankBalance(30);
        state.setBankTotalDeposited(60);
        state.setBankTotalSpent(30);
        state.setBankExtensionUsed(true);
        state.setBankRitualOpen(true);
        state.addBankTx("tx-ext");
        state.addBankTx("tx-sol-A");
        state.putBankEscrow("tx-sol-A", 30);
        state.putAnchorDeath(uuid, "reserva,10,64,10");
        state.setSolarFallDone(true);
        store.save(state);

        SeasonState loaded = store.load("s1");
        assertEquals(state, loaded);
        assertEquals("ANCLA", loaded.sacrifices().get(uuid.toString()));
        assertEquals(30, loaded.bankBalance());
        assertEquals(60, loaded.bankTotalDeposited());
        assertEquals(30, loaded.bankTotalSpent());
        assertTrue(loaded.isBankExtensionUsed());
        assertTrue(loaded.isBankRitualOpen());
        assertTrue(loaded.bankTxIds().contains("tx-ext"));
        assertEquals(30, loaded.bankEscrow().get("tx-sol-A"));
        assertEquals("reserva,10,64,10", loaded.anchorDeaths().get(uuid.toString()));
        assertTrue(loaded.isSolarFallDone());

        store.save(loaded);
        assertEquals(state, store.load("s1"));
    }

    @Test
    void roundTripPersistsAnchorDeathsWithCommasAndQuotes(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 20, 43);
        java.util.UUID uuidA = java.util.UUID.randomUUID();
        java.util.UUID uuidB = java.util.UUID.randomUUID();
        state.putAnchorDeath(uuidA, "mundo, raro,10,64,10");
        state.putAnchorDeath(uuidB, "l'ile,1,2,3");
        store.save(state);

        SeasonState loaded = store.load("s1");
        assertEquals(state, loaded);
        assertEquals("mundo, raro,10,64,10", loaded.anchorDeaths().get(uuidA.toString()));
        assertEquals("l'ile,1,2,3", loaded.anchorDeaths().get(uuidB.toString()));

        store.save(loaded);
        assertEquals(state, store.load("s1"));
    }

    @Test
    void differentSeasonIdDoesNotMixData(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 20, 40);
        state.markActAnnounced(4);
        store.save(state);

        SeasonState other = store.load("s2");
        assertEquals("s2", other.seasonId());
        assertTrue(other.announcedActs().isEmpty());
        // El archivo de s1 sigue intacto.
        assertEquals(state, store.load("s1"));
    }

    @Test
    void corruptFileThrowsDiagnosedExceptionAndKeepsFile(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        Files.createDirectories(store.stateFile().getParent());
        Files.writeString(store.stateFile(), "::: basura sin clave valor {{{",
                StandardCharsets.UTF_8);
        assertThrows(SeasonStore.CorruptStateException.class, () -> store.load("s1"));
        assertTrue(Files.exists(store.stateFile()));
    }

    @Test
    void missingSeasonIdIsCorrupt(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        Files.createDirectories(store.stateFile().getParent());
        Files.writeString(store.stateFile(), "day: 5\n", StandardCharsets.UTF_8);
        assertThrows(SeasonStore.CorruptStateException.class, () -> store.load("s1"));
    }

    @Test
    void corruptBytesSurviveGuardedStartSaveShutdown(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        Files.createDirectories(store.stateFile().getParent());
        Files.writeString(store.stateFile(), "::: basura sin clave valor {{{",
                StandardCharsets.UTF_8);
        byte[] corrupt = Files.readAllBytes(store.stateFile());

        // Orquestacion load -> start/save/shutdown con politica pura (sin Bukkit):
        // al fallar la carga se sella y ningun guardado debe intentarse.
        SeasonStore.WriteGate gate = new SeasonStore.WriteGate();
        try {
            store.archiveIfSeasonChanged("s1");
            store.load("s1");
            gate.unseal();
        } catch (SeasonStore.CorruptStateException expected) {
            gate.seal(expected.getMessage());
        }
        assertTrue(gate.isSealed());
        if (!gate.isSealed()) {
            store.save(new SeasonState("s1"));
        }
        assertArrayEquals(corrupt, Files.readAllBytes(store.stateFile()));

        // Solo una recarga correcta y explicita libera la escritura.
        SeasonState repaired = new SeasonState("s1", 7, 40);
        repaired.markActAnnounced(2);
        store.save(repaired); // reparacion externa del admin, fuera del servicio sellado
        try {
            store.archiveIfSeasonChanged("s1");
            SeasonState reloaded = store.load("s1");
            gate.unseal();
            assertEquals(repaired, reloaded);
        } catch (SeasonStore.CorruptStateException failed) {
            gate.seal(failed.getMessage());
        }
        assertFalse(gate.isSealed());
        store.save(store.load("s1"));
        assertEquals(repaired, store.load("s1"));
    }

    @Test
    void differentSeasonIdArchivesHistoryBeforeNewSave(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState old = new SeasonState("s1", 20, 40);
        old.markActAnnounced(4);
        store.save(old);
        byte[] oldBytes = Files.readAllBytes(store.stateFile());

        Optional<Path> backup = store.archiveIfSeasonChanged("s2");
        assertTrue(backup.isPresent());
        assertTrue(Files.exists(backup.get()));
        assertArrayEquals(oldBytes, Files.readAllBytes(backup.get()));

        SeasonState fresh = store.load("s2");
        assertEquals("s2", fresh.seasonId());
        assertTrue(fresh.announcedActs().isEmpty());
        store.save(fresh); // guardado del arranque de la nueva temporada

        assertArrayEquals(oldBytes, Files.readAllBytes(backup.get()));
        assertEquals(old, new SeasonStore(backup.get()).load("s1"));
        assertEquals("s2", store.load("s2").seasonId());
    }
}
