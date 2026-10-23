package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import mx.vicko.permadeath.FortressHuntEvent.HuntPhase;
import mx.vicko.permadeath.FortressHuntEvent.HuntSession;

/**
 * Paso 3 (MiniMax B): regresiones de la Fortaleza cerrada.
 *
 * <p>La logica de oleadas vive en {@link HuntSession}, pura y sin Bukkit, porque el harness del
 * proyecto es solo JUnit 5 sin MockBukkit (ver README Paso 1). Estos tests fijan el contrato que el
 * codigo productivo maneja: cofre bloqueado en combate Y en espera entre oleadas, recompensa solo
 * tras la tercera oleada, y tareas de oleada vinculadas a la sesion que las programo.
 */
class FortressHuntEventTest {

    private static HuntSession newHunt(long id) {
        return new HuntSession(id);
    }

    private static void killGuards(HuntSession session, int count) {
        for (int i = 0; i < count; i++) session.onGuardDeath();
    }

    @Test
    void chestStaysLockedBetweenWavesWithZeroGuards() {
        // Regresion del bug: el bloqueo anterior miraba guards.isEmpty(), asi que el cofre se abria
        // durante los 40 ticks entre la ultima muerte y la siguiente oleada. El bloqueo debe seguir
        // la fase de la sesion, no el conteo de guardias.
        HuntSession session = newHunt(1);
        session.onWaveSpawned(1, 4);
        killGuards(session, 4);
        assertEquals(HuntPhase.WAITING, session.phase());
        assertTrue(session.isChestLocked(), "el cofre debe seguir bloqueado en la espera entre oleadas");
        assertFalse(session.rewardUnlocked());
    }

    @Test
    void fullThreeWaveTransitionToReward() {
        HuntSession session = newHunt(3);
        session.onWaveSpawned(1, 4);
        killGuards(session, 3);
        assertEquals(HuntPhase.COMBAT, session.phase());
        assertTrue(session.isChestLocked());
        assertEquals(HuntSession.GuardDeathOutcome.WAIT_NEXT_WAVE, session.onGuardDeath());
        assertEquals(HuntPhase.WAITING, session.phase());

        session.onWaveSpawned(2, 5);
        assertEquals(HuntPhase.COMBAT, session.phase());
        killGuards(session, 5);
        assertEquals(HuntPhase.WAITING, session.phase());
        assertTrue(session.isChestLocked());

        session.onWaveSpawned(3, 3);
        killGuards(session, 2);
        assertTrue(session.isChestLocked());
        assertEquals(HuntSession.GuardDeathOutcome.REWARD_READY, session.onGuardDeath());
        assertEquals(HuntPhase.REWARD, session.phase());
        assertTrue(session.rewardUnlocked());
        assertFalse(session.isChestLocked(), "el cofre solo se abre tras derrotar la tercera oleada");
    }

    @Test
    void rewardOnlyAfterThirdWave() {
        HuntSession session = newHunt(4);
        session.onWaveSpawned(1, 4);
        killGuards(session, 3);
        assertEquals(HuntSession.GuardDeathOutcome.WAIT_NEXT_WAVE, session.onGuardDeath());
        assertFalse(session.rewardUnlocked());

        session.onWaveSpawned(2, 5);
        killGuards(session, 5);
        assertFalse(session.rewardUnlocked());
        assertTrue(session.isChestLocked());
    }

    @Test
    void staleWaveTaskTokenRejectedAfterRestart() {
        // La tarea de 40 ticks captura el id de sesion: un callback heredado de una caceria anterior
        // nunca debe disparar la oleada de la sesion nueva.
        HuntSession first = newHunt(10);
        first.onWaveSpawned(1, 4);
        killGuards(first, 4);
        assertTrue(first.acceptsWaveTask(10));

        HuntSession restarted = newHunt(11);
        restarted.onWaveSpawned(1, 4);
        killGuards(restarted, 4);
        assertFalse(restarted.acceptsWaveTask(10), "callback de la sesion anterior debe ignorarse");
        assertTrue(restarted.acceptsWaveTask(11));
    }

    @Test
    void cancelDuringWaitGrantsNoReward() {
        HuntSession session = newHunt(20);
        session.onWaveSpawned(2, 5);
        killGuards(session, 5);
        assertEquals(HuntPhase.WAITING, session.phase());

        session.abort();
        assertEquals(HuntPhase.IDLE, session.phase());
        assertFalse(session.rewardUnlocked(), "cancelacion/timeout no deben regalar loot");
        assertFalse(session.acceptsWaveTask(20), "la tarea pendiente debe invalidarse al cancelar");
    }

    @Test
    void guardDeathOutsideCombatIsNoOp() {
        HuntSession session = newHunt(30);
        session.onWaveSpawned(1, 4);
        killGuards(session, 4);
        assertEquals(HuntSession.GuardDeathOutcome.STILL_FIGHTING, session.onGuardDeath());
        assertEquals(HuntPhase.WAITING, session.phase());
        assertTrue(session.isChestLocked());
    }

    @Test
    void emptySpawnNeverUnlocksEarly() {
        // Si el spawn falla y no hay guardias, las oleadas 1-2 quedan en espera (bloqueadas),
        // nunca en recompensa; solo la oleada 3 vacia puede resolver a recompensa.
        HuntSession session = newHunt(40);
        session.onWaveSpawned(1, 0);
        assertEquals(HuntPhase.WAITING, session.phase());
        assertTrue(session.isChestLocked());
        assertFalse(session.rewardUnlocked());

        session.onWaveSpawned(2, 0);
        assertEquals(HuntPhase.WAITING, session.phase());
        assertFalse(session.rewardUnlocked());

        session.onWaveSpawned(3, 0);
        assertEquals(HuntPhase.REWARD, session.phase());
        assertTrue(session.rewardUnlocked());
    }

    @Test
    void titleRadiusBoundaryInclusive() {
        assertTrue(FortressHuntEvent.shouldSendWaveTitle(true, 0.0));
        assertTrue(FortressHuntEvent.shouldSendWaveTitle(true, 20.0 * 20.0), "radio 20 inclusivo recibe title");
        assertFalse(FortressHuntEvent.shouldSendWaveTitle(true, 20.1 * 20.1), "20.1 no recibe title");
        assertFalse(FortressHuntEvent.shouldSendWaveTitle(false, 0.0), "otro mundo no recibe title");
    }

    @Test
    void waveTitleFormat() {
        assertEquals("Oleada 1/3", FortressHuntEvent.waveTitle(1));
        assertEquals("Oleada 2/3", FortressHuntEvent.waveTitle(2));
        assertEquals("Oleada 3/3", FortressHuntEvent.waveTitle(3));
    }
}
