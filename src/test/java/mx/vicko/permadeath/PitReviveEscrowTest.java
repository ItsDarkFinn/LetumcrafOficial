package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bloque 3 — secuencia transaccional del escrow de vuelta (no solo tokens).
 * PREPARED durable → registro → COMMITTED; fallo inyectado en cada escritura;
 * vivo = commit una vez, muerto = refund una vez; repetir no recobra ni
 * recuenta; preparación no persistida NO autoriza; el forzado admin no cuenta.
 */
public final class PitReviveEscrowTest {

    private static final Logger LOG = Logger.getLogger("PitReviveEscrowTest");
    private static final long DAY = 86_400_000L;

    private static void dumpBank(SeasonState state, EclipseBank bank) {
        state.setBankBalance(bank.balance());
        state.setBankTotalDeposited(bank.totalDeposited());
        state.setBankTotalSpent(bank.totalSpent());
        state.setBankExtensionUsed(bank.isExtensionUsed());
        state.setBankRitualOpen(bank.isRitualOpen());
        state.setBankSunReserved(bank.isSunReserved());
    }

    private static SeasonService.ReviveTxPorts realPorts(SeasonStore store, SeasonState state,
            EclipseBank bank, DeathRegistry deaths) {
        return new SeasonService.ReviveTxPorts() {
            @Override
            public boolean persist() {
                dumpBank(state, bank);
                try {
                    store.save(state);
                    return true;
                } catch (Exception exception) {
                    return false;
                }
            }

            @Override
            public boolean registryRevive(UUID target) {
                return deaths.reviveSeasonalChecked(target);
            }
        };
    }

    private static UUID deadWithChispas(DeathRegistry deaths, SeasonState state, int earned) {
        UUID uuid = UUID.randomUUID();
        deaths.record(uuid, "Muerto" + uuid.toString().substring(0, 4), "fosa");
        for (int day = 0; day < earned; day++) {
            state.addPitEarned(uuid, 10L + day);
        }
        state.noteSeasonDeath(uuid, System.currentTimeMillis() - 6 * DAY);
        return uuid;
    }

    private static EclipseBank bankFrom(SeasonState state) {
        return EclipseBank.fromParts(state.bankBalance(), state.bankTotalDeposited(),
                state.bankTotalSpent(), state.isBankExtensionUsed(), state.isBankRitualOpen(),
                state.isBankSunReserved(), state.bankTxIds(), state.bankEscrow());
    }

    @Test
    void happyPathCommitsOnceWithExactCharge(@TempDir Path dir) {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        SeasonStore store = new SeasonStore(dir);
        UUID uuid = deadWithChispas(deaths, state, 3);
        long deathTime = state.seasonDeathTimeOf(uuid);

        SeasonService.ReviveTxOutcome out = SeasonService.executeReviveTx(state, bank, uuid,
                1, false, deathTime, realPorts(store, state, bank, deaths));

        assertEquals(SeasonService.ReviveTxOutcome.COMMITTED, out);
        assertEquals(1, state.revivalsOf(uuid));
        assertEquals(2, state.pitBalanceOf(uuid));
        assertFalse(state.hasReviveTx(uuid));
        assertFalse(deaths.isDead(uuid));
        // Repetir el reconcile no recuenta ni recobra.
        assertFalse(SeasonService.reconcileTxOutcome(state, bank, uuid, false));
        assertEquals(1, state.revivalsOf(uuid));
        assertEquals(2, state.pitBalanceOf(uuid));
    }

    @Test
    void registryFailureRefundsWithoutCounting(@TempDir Path dir) {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        SeasonStore store = new SeasonStore(dir);
        UUID uuid = deadWithChispas(deaths, state, 3);
        long deathTime = state.seasonDeathTimeOf(uuid);
        SeasonService.ReviveTxPorts failingRegistry = new SeasonService.ReviveTxPorts() {
            @Override
            public boolean persist() {
                dumpBank(state, bank);
                try {
                    store.save(state);
                    return true;
                } catch (Exception exception) {
                    return false;
                }
            }

            @Override
            public boolean registryRevive(UUID target) {
                return false;
            }
        };

        SeasonService.ReviveTxOutcome out = SeasonService.executeReviveTx(state, bank, uuid,
                1, false, deathTime, failingRegistry);

        assertEquals(SeasonService.ReviveTxOutcome.DENIED_REGISTRY, out);
        assertTrue(deaths.isDead(uuid));
        assertEquals(3, state.pitBalanceOf(uuid));
        assertEquals(0, state.revivalsOf(uuid));
        assertFalse(state.hasReviveTx(uuid));
    }

    @Test
    void preparePersistFailureAuthorizesNothing(@TempDir Path dir) {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        UUID uuid = deadWithChispas(deaths, state, 3);
        long deathTime = state.seasonDeathTimeOf(uuid);
        boolean[] registryTouched = {false};
        SeasonService.ReviveTxPorts deadDisk = new SeasonService.ReviveTxPorts() {
            @Override
            public boolean persist() {
                return false;
            }

            @Override
            public boolean registryRevive(UUID target) {
                registryTouched[0] = true;
                return deaths.reviveSeasonalChecked(target);
            }
        };

        SeasonService.ReviveTxOutcome out = SeasonService.executeReviveTx(state, bank, uuid,
                1, false, deathTime, deadDisk);

        assertEquals(SeasonService.ReviveTxOutcome.DENIED_PREPARE_PERSIST, out);
        assertFalse(registryTouched[0], "sin PREPARED durable no se toca DeathRegistry");
        assertTrue(deaths.isDead(uuid));
        assertEquals(3, state.pitBalanceOf(uuid), "sin cobro si el intento no persistió");
        assertEquals(0, state.revivalsOf(uuid));
        assertFalse(state.hasReviveTx(uuid));
    }

    @Test
    void crashAfterRegistryCommitsExactlyOnceOnRestart(@TempDir Path dir) throws Exception {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        bank.deposit(5);
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        SeasonStore store = new SeasonStore(dir);
        UUID uuid = deadWithChispas(deaths, state, 3);
        long deathTime = state.seasonDeathTimeOf(uuid);

        // Fase 1: cobro + peaje + tx + persist PREPARED + registro; kill -9 antes del commit.
        assertTrue(state.spendPit(uuid, 1));
        assertTrue(bank.consume(1));
        assertTrue(state.beginReviveTx(uuid, 1, true, deathTime));
        dumpBank(state, bank);
        store.save(state);
        assertTrue(deaths.reviveSeasonalChecked(uuid));

        // Fase 2: restart: todo se recarga de disco, la memoria se perdió.
        SeasonState reloaded = store.load("s3");
        DeathRegistry deaths2 = new DeathRegistry(dir.toFile(), LOG);
        deaths2.load();
        EclipseBank bank2 = bankFrom(reloaded);
        assertTrue(reloaded.hasReviveTx(uuid), "la tx PREPARED sobrevive al crash");
        assertFalse(deaths2.isDead(uuid), "el registro sí ocurrió antes del crash");

        assertTrue(SeasonService.reconcileTxOutcome(reloaded, bank2, uuid, deaths2.isDead(uuid)));
        assertEquals(1, reloaded.revivalsOf(uuid), "la vuelta cuenta exactamente una vez");
        assertEquals(2, reloaded.pitBalanceOf(uuid), "el cobro queda, sin doble cobro");
        assertEquals(4, bank2.balance(), "el peaje se conserva: la vuelta sí ocurrió");
        assertFalse(reloaded.hasReviveTx(uuid));
        // Repetir no recuenta.
        assertFalse(SeasonService.reconcileTxOutcome(reloaded, bank2, uuid, false));
        assertEquals(1, reloaded.revivalsOf(uuid));
        assertEquals(4, bank2.balance());
    }

    @Test
    void crashBeforeRegistryRefundsOnceOnRestart(@TempDir Path dir) throws Exception {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        bank.deposit(5);
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        SeasonStore store = new SeasonStore(dir);
        UUID uuid = deadWithChispas(deaths, state, 3);
        long deathTime = state.seasonDeathTimeOf(uuid);

        // Fase 1: cobro + tx + persist PREPARED; kill -9 antes del registro.
        assertTrue(state.spendPit(uuid, 1));
        assertTrue(bank.consume(1));
        assertTrue(state.beginReviveTx(uuid, 1, true, deathTime));
        dumpBank(state, bank);
        store.save(state);

        // Fase 2: restart con el jugador todavía muerto.
        SeasonState reloaded = store.load("s3");
        DeathRegistry deaths2 = new DeathRegistry(dir.toFile(), LOG);
        deaths2.load();
        EclipseBank bank2 = bankFrom(reloaded);
        assertTrue(deaths2.isDead(uuid));

        assertTrue(SeasonService.reconcileTxOutcome(reloaded, bank2, uuid, deaths2.isDead(uuid)));
        assertEquals(0, reloaded.revivalsOf(uuid), "sin registro no hay vuelta");
        assertEquals(3, reloaded.pitBalanceOf(uuid), "refund único de Chispas");
        assertEquals(5, bank2.balance(), "refund único del Núcleo de peaje");
        assertFalse(reloaded.hasReviveTx(uuid));
        // Repetir no recobra de más.
        assertFalse(SeasonService.reconcileTxOutcome(reloaded, bank2, uuid, true));
        assertEquals(3, reloaded.pitBalanceOf(uuid));
        assertEquals(5, bank2.balance());
    }

    @Test
    void adminForcePathCountsNothingAndCostsNothing(@TempDir Path dir) {
        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        UUID uuid = deadWithChispas(deaths, state, 3);

        // Escrow abierto y cancelado ANTES del forzado (protocolo de /pd revivirforzado).
        assertTrue(state.spendPit(uuid, 1));
        assertTrue(state.beginReviveTx(uuid, 1, false, state.seasonDeathTimeOf(uuid)));
        assertTrue(SeasonService.reconcileTxOutcome(state, bank, uuid, true));
        assertEquals(3, state.pitBalanceOf(uuid));
        assertFalse(state.hasReviveTx(uuid));

        assertTrue(deaths.reviveForced(uuid));
        assertFalse(deaths.isDead(uuid));
        // Un reconcile posterior no comete nada: el forzado no cuenta ni cuesta.
        assertFalse(SeasonService.reconcileTxOutcome(state, bank, uuid, deaths.isDead(uuid)));
        assertEquals(0, state.revivalsOf(uuid));
        assertEquals(3, state.pitBalanceOf(uuid));
    }

    @Test
    void valveNeedsNoCoreAndTollOnlyAltarSolo(@TempDir Path dir) {
        // Válvula con banco a 0 autoriza sin peaje.
        RevivalPolicy.Decision valve = RevivalPolicy.evaluate(9, 0, 1, false, 0, 5, false);
        assertTrue(valve.allowed());
        assertEquals(RevivalPolicy.Path.VALVE_SOLO, valve.path());

        SeasonState state = new SeasonState("s3", 1, 40);
        EclipseBank bank = new EclipseBank();
        DeathRegistry deaths = new DeathRegistry(dir.toFile(), LOG);
        SeasonStore store = new SeasonStore(dir);
        UUID uuid = deadWithChispas(deaths, state, 1);
        long deathTime = state.seasonDeathTimeOf(uuid);

        SeasonService.ReviveTxOutcome out = SeasonService.executeReviveTx(state, bank, uuid,
                1, false, deathTime, realPorts(store, state, bank, deaths));
        assertEquals(SeasonService.ReviveTxOutcome.COMMITTED, out);
        assertEquals(0, bank.balance(), "la válvula no consume Núcleo del banco");

        // ALTAR_SOLO con banco a 0 se deniega con Chispas intactas.
        SeasonState poor = new SeasonState("s3", 1, 40);
        EclipseBank emptyBank = new EclipseBank();
        UUID uuid2 = deadWithChispas(new DeathRegistry(dir.toFile(), LOG), poor, 3);
        SeasonService.ReviveTxOutcome tollDenied = SeasonService.executeReviveTx(poor, emptyBank,
                uuid2, 1, true, poor.seasonDeathTimeOf(uuid2), realPorts(store, poor, emptyBank,
                        new DeathRegistry(dir.toFile(), LOG)));
        assertEquals(SeasonService.ReviveTxOutcome.DENIED_TOLL, tollDenied);
        assertEquals(3, poor.pitBalanceOf(uuid2));
    }
}
