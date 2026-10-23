package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bloque 4 — Tests puros del final (fecha, saldo, extensión, tope,
 * idempotencia/persistencia, estados rechazados). Sin Bukkit ni Paper.
 */
class SeasonFinaleTest {

    @Test
    void finalDayUsesEffectiveLengthWithSingleExtension() {
        assertEquals(40, SeasonFinale.finalDay(40, false));
        assertEquals(43, SeasonFinale.finalDay(40, true));
        assertEquals(33, SeasonFinale.finalDay(30, true));
    }

    @Test
    void extensionNeverExceeds45() {
        assertEquals(45, SeasonFinale.finalDay(43, true));
        assertEquals(45, SeasonFinale.finalDay(45, true));
        assertEquals(45, SeasonFinale.finalDay(45, false));
    }

    @Test
    void compat100KeepsFinalAt100() {
        assertEquals(100, SeasonFinale.finalDay(100, false));
        assertEquals(100, SeasonFinale.finalDay(100, true));
    }

    @Test
    void noFinalAnnouncementBeforeEffectiveEnd() {
        // Base 40 + extensión = fin 43: el día 40 aún no dispara el final.
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(40, 43, false, false));
        assertFalse(SeasonFinale.canTrigger(40, 43, "RUNNING"));
        assertTrue(SeasonFinale.canTrigger(43, 43, "RUNNING"));
    }

    @Test
    void sunBalanceDecidesVictoryOrDefeat() {
        assertEquals(SeasonFinale.Status.VICTORY,
                SeasonFinale.decide(43, 43, true, false));
        assertEquals(SeasonFinale.Status.DEFEAT,
                SeasonFinale.decide(43, 43, false, false));
    }

    @Test
    void zeroAliveSignalsEarlyDefeatWithoutWaitingFinalDay() {
        assertEquals(SeasonFinale.Status.DEFEAT,
                SeasonFinale.decide(10, 40, false, true));
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(10, 40, false, false));
    }

    @Test
    void extinctionSealsOnlyWithNobodyOnline() {
        // B4: 0 vivos del roster + alguien conectado (último muerto en La Fosa,
        // válvula de 5 días) mantiene RUNNING antes del plazo.
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(10, 40, false, 0, 1));
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(10, 40, false, 0, 5));
        // 0 vivos y nadie conectado sí sella DEFEAT anticipada.
        assertEquals(SeasonFinale.Status.DEFEAT,
                SeasonFinale.decide(10, 40, false, 0, 0));
        // Con vivos no hay extinción aunque nadie esté conectado.
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(10, 40, false, 1, 0));
        assertEquals(SeasonFinale.Status.RUNNING,
                SeasonFinale.decide(10, 40, false, 8, 0));
    }

    @Test
    void finalDayAlwaysClosesWithBankResult() {
        // El día final cierra haya o no conectados, con el resultado del banco.
        assertEquals(SeasonFinale.Status.DEFEAT,
                SeasonFinale.decide(43, 43, false, 0, 3));
        assertEquals(SeasonFinale.Status.VICTORY,
                SeasonFinale.decide(43, 43, true, 0, 3));
        assertEquals(SeasonFinale.Status.DEFEAT,
                SeasonFinale.decide(43, 43, false, 0, 0));
        assertEquals(SeasonFinale.Status.VICTORY,
                SeasonFinale.decide(43, 43, true, 5, 0));
    }

    @Test
    void phaseEffectsStopWhenSeasonEnded() {
        assertTrue(SeasonService.phaseActiveEffective(true, true, false, false));
        assertFalse(SeasonService.phaseActiveEffective(true, true, false, true));
        assertFalse(SeasonService.phaseActiveEffective(true, true, true, false));
        assertFalse(SeasonService.phaseActiveEffective(false, true, false, false));
        assertFalse(SeasonService.phaseActiveEffective(true, false, false, false));
    }

    @Test
    void onlineCountProbeSeamCountsWithoutBukkit() {
        SeasonService.onlineCountProbe = () -> 2;
        try {
            assertEquals(2, SeasonService.onlineCount());
            SeasonService.onlineCountProbe = () -> 0;
            assertEquals(0, SeasonService.onlineCount());
        } finally {
            SeasonService.onlineCountProbe = null;
        }
    }

    @Test
    void finaleSnapshotFreezesStatsAgainstLaterMutations() {
        SeasonState state = new SeasonState("temporada-1", 10, 40);
        state.noteFirstSeen("uuid-1", 3);
        state.addLifetimeDeath("uuid-1");
        assertTrue(state.setFinale("DEFEAT", 40, "actor-1", "sin sol"));
        assertTrue(state.freezeFinale(0, 2, List.of("Nadie"),
                state.lifetimeDeaths(), state.firstSeenDays(), 10, 30, List.of("PORTAL")));
        // Segunda foto no pisa la primera.
        assertFalse(state.freezeFinale(5, 5, List.of("Otro"),
                Map.of(), Map.of(), 0, 0, List.of()));
        // Mutaciones post-sellado (muertes, hooks, banco) no alteran la foto.
        state.addLifetimeDeath("uuid-1");
        state.addLifetimeDeath("uuid-2");
        state.noteFirstSeen("uuid-9", 39);
        state.setBankTotalDeposited(999);
        state.setBankTotalSpent(999);
        assertTrue(state.hasFinaleSnapshot());
        assertEquals(0, state.finaleAliveN());
        assertEquals(2, state.finaleAliveM());
        assertEquals(List.of("Nadie"), state.finaleAliveNames());
        assertEquals(Map.of("uuid-1", 1), state.finaleLifetimeDeaths());
        assertEquals(Map.of("uuid-1", 3), state.finaleFirstSeenDays());
        assertEquals(10, state.finaleBankDeposited());
        assertEquals(30, state.finaleBankSpent());
        assertEquals(List.of("PORTAL"), state.finaleEvents());
    }

    @Test
    void finaleSnapshotRoundTripsThroughStore(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("temporada-9", 5, 40);
        assertTrue(state.setFinale("VICTORY", 43, "actor-9", "sol encendido"));
        assertTrue(state.freezeFinale(2, 5, List.of("Ana", "Beto"),
                Map.of("uuid-1", 2), Map.of("uuid-1", 3, "uuid-2", 40),
                40, 30, List.of("PORTAL")));
        store.save(state);
        SeasonState loaded = store.load("temporada-9");
        assertTrue(loaded.hasFinaleSnapshot());
        assertEquals(2, loaded.finaleAliveN());
        assertEquals(5, loaded.finaleAliveM());
        assertEquals(List.of("Ana", "Beto"), loaded.finaleAliveNames());
        assertEquals(Map.of("uuid-1", 2), loaded.finaleLifetimeDeaths());
        assertEquals(Map.of("uuid-1", 3, "uuid-2", 40), loaded.finaleFirstSeenDays());
        assertEquals(40, loaded.finaleBankDeposited());
        assertEquals(30, loaded.finaleBankSpent());
        assertEquals(List.of("PORTAL"), loaded.finaleEvents());
        // Sin sellar no hay foto (archivo sin foto = tolerante, sin inventar).
        SeasonState plain = new SeasonState("temporada-9", 5, 40);
        store.save(plain);
        assertFalse(store.load("temporada-9").hasFinaleSnapshot());
    }

    @Test
    void renderedFinaleReproducesSnapshotAfterLiveMutations() {
        Map<String, Integer> snapDeaths = new java.util.LinkedHashMap<>(Map.of("uuid-1", 2));
        Map<String, Integer> snapSeen = new java.util.LinkedHashMap<>(Map.of("uuid-1", 3));
        List<String> first = SeasonService.renderFinaleLines("temporada-9", "VICTORY", 43,
                "actor-9", "sol encendido", 1, 5, 40, 30, snapDeaths,
                Map.of(), List.of("PORTAL"), snapSeen, List.of("Ana"));
        assertFalse(first.isEmpty());
        // El estado vivo muta después (muerte post-END, otro visto, banco).
        snapDeaths.put("uuid-1", 99);
        snapDeaths.put("uuid-2", 7);
        snapSeen.put("uuid-9", 43);
        List<String> replay = SeasonService.renderFinaleLines("temporada-9", "VICTORY", 43,
                "actor-9", "sol encendido", 1, 5, 40, 30, Map.of("uuid-1", 2),
                Map.of(), List.of("PORTAL"), Map.of("uuid-1", 3), List.of("Ana"));
        assertEquals(first, replay);
        String all = String.join("\n", replay);
        assertTrue(all.contains("Ana"));
    }

    @Test
    void sunPaymentIsIdempotentWithDeterministicTx() {
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(30));
        assertTrue(SeasonFinale.tryLightSun(bank, "temporada-1", 40));
        assertTrue(bank.isSunReserved());
        int spentOnce = bank.totalSpent();
        // Replay tras reinicio: mismo tx, sin doble cobro.
        assertTrue(SeasonFinale.tryLightSun(bank, "temporada-1", 40));
        assertEquals(spentOnce, bank.totalSpent());
        assertEquals(0, bank.balance());
    }

    @Test
    void sunPaymentFailsWithoutFundsAndWithoutMutation() {
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(29));
        assertFalse(SeasonFinale.tryLightSun(bank, "temporada-1", 40));
        assertFalse(bank.isSunReserved());
        assertEquals(29, bank.balance());
    }

    @Test
    void endedStatesRejectFurtherTransitions() {
        assertTrue(SeasonFinale.isEnded("VICTORY"));
        assertTrue(SeasonFinale.isEnded("DEFEAT"));
        assertFalse(SeasonFinale.isEnded("RUNNING"));
        assertFalse(SeasonFinale.canTrigger(50, 40, "VICTORY"));
        assertFalse(SeasonFinale.canTrigger(50, 40, "DEFEAT"));
        assertEquals("RUNNING", SeasonFinale.normalizeStatus("???"));
        assertEquals("VICTORY", SeasonFinale.normalizeStatus("VICTORY"));
    }

    @Test
    void finalePersistsOnceInSeasonState() {
        SeasonState state = new SeasonState("temporada-1", 1, 40);
        assertEquals("RUNNING", state.finaleStatus());
        assertTrue(state.setFinale("VICTORY", 43, "actor-1", "sol encendido"));
        assertEquals("VICTORY", state.finaleStatus());
        assertEquals(43, state.finaleDay());
        // Una sola vez: no reabre ni cambia a derrota.
        assertFalse(state.setFinale("DEFEAT", 43, "otro", "tardío"));
        assertFalse(state.setFinale("RUNNING", 43, "otro", "reabrir"));
        assertEquals("VICTORY", state.finaleStatus());
    }

    @Test
    void statsSurviveReviveHistoryLoss() {
        SeasonState state = new SeasonState("temporada-1", 1, 40);
        state.noteFirstSeen("uuid-1", 3);
        state.noteFirstSeen("uuid-1", 9);
        state.addLifetimeDeath("uuid-1");
        state.addLifetimeDeath("uuid-1");
        // El primer visto no se sobrescribe y las muertes solo crecen.
        assertEquals(3, state.firstSeenDays().get("uuid-1").intValue());
        assertEquals(2, state.lifetimeDeaths().get("uuid-1").intValue());
        assertEquals(41, SeasonStatistics.daysSurvived(3, 43));
        assertEquals(0, SeasonStatistics.daysSurvived(99, 43));
    }

    @Test
    void finaleAndStatsRoundTripThroughStore(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("temporada-9", 5, 40);
        state.noteFirstSeen("uuid-1", 2);
        state.addLifetimeDeath("uuid-1");
        assertTrue(state.setFinale("DEFEAT", 40, "actor-9", "sin sol"));
        store.save(state);
        SeasonState loaded = store.load("temporada-9");
        assertEquals("DEFEAT", loaded.finaleStatus());
        assertEquals(40, loaded.finaleDay());
        assertEquals("actor-9", loaded.finaleActor());
        assertEquals(2, loaded.firstSeenDays().get("uuid-1").intValue());
        assertEquals(1, loaded.lifetimeDeaths().get("uuid-1").intValue());
        List<String> lines = SeasonStatistics.buildLines(loaded.seasonId(),
                loaded.finaleStatus(), loaded.finaleDay(), loaded.finaleActor(),
                loaded.finaleReason(), 0, 2, 10, 30,
                loaded.lifetimeDeaths(), Map.of(), List.of("PORTAL"));
        assertFalse(lines.isEmpty());
    }

    @Test
    void pendingSunEscrowCountsAsPaidAtFinalDay() {
        // Defecto 4: reserva en dos pasos sin confirmar (/pd banco comprar sol
        // deja 30N en escrow, saldo 0). Al día final equivale al pago.
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(30));
        assertTrue(bank.reserveSun("sol-tx-1"));
        assertEquals(0, bank.balance());
        assertFalse(bank.isSunReserved());
        assertTrue(SeasonFinale.confirmPendingSun(bank));
        assertTrue(bank.isSunReserved());
        assertEquals(30, bank.totalSpent());
        assertEquals(0, bank.balance());
        // Idempotente: repetir no cobra dos veces.
        assertTrue(SeasonFinale.confirmPendingSun(bank));
        assertEquals(30, bank.totalSpent());
        assertEquals(SeasonFinale.Status.VICTORY,
                SeasonFinale.decide(43, 43, bank.isSunReserved(), false));
    }

    @Test
    void pendingSunEscrowSurvivesRestartWithoutDoubleCharge() {
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(30));
        assertTrue(bank.reserveSun("sol-tx-9"));
        // Reinicio: rehidrata desde season-state.yml con escrow pendiente.
        EclipseBank reloaded = EclipseBank.fromParts(bank.balance(), bank.totalDeposited(),
                bank.totalSpent(), false, false, bank.isSunReserved(),
                bank.processedTx(), bank.escrow());
        assertTrue(SeasonFinale.confirmPendingSun(reloaded));
        assertTrue(reloaded.isSunReserved());
        assertEquals(30, reloaded.totalSpent());
        assertTrue(SeasonFinale.confirmPendingSun(reloaded));
        assertEquals(30, reloaded.totalSpent());
    }

    @Test
    void finaleTxReservedBeforeCrashConfirmsOnRetry() {
        // Crash entre reserve y confirm del auto-encendido: el replay del
        // mismo tx determinista confirma sin doble cobro.
        EclipseBank bank = new EclipseBank();
        assertTrue(bank.deposit(30));
        String tx = SeasonFinale.finaleTxId("temporada-1", 40);
        assertTrue(bank.reserveSun(tx));
        assertTrue(SeasonFinale.tryLightSun(bank, "temporada-1", 40));
        assertTrue(bank.isSunReserved());
        assertEquals(30, bank.totalSpent());
    }

    @Test
    void finaleStatsShowDaysSurvivedAndAliveNames() {
        // Defecto 5: días sobrevividos y nombres de quien llegó vivo, no
        // solo el par vivo/total ni una lista no vacía.
        List<String> lines = SeasonStatistics.buildLines("temporada-9", "VICTORY", 43,
                "actor-9", "sol encendido", 2, 5, 40, 30,
                Map.of("uuid-1", 2, "uuid-2", 0), Map.of("uuid-3", "Abrigo"),
                List.of("PORTAL"), Map.of("uuid-1", 3, "uuid-2", 40),
                List.of("Ana", "Beto"));
        String all = String.join("\n", lines);
        assertTrue(all.contains("Quién llegó vivo"));
        assertTrue(all.contains("Ana"));
        assertTrue(all.contains("Beto"));
        assertTrue(all.contains("Días sobrevividos"));
        assertTrue(all.contains("uuid-1"));
        assertTrue(all.contains("41"));
        assertTrue(all.contains("uuid-2"));
        assertTrue(all.contains("4 días"));
    }
}
