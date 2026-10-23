package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Bloque 4 — el depósito al banco con temporada sellada (ENDED) no acredita
 * ni muta el fondo; en RUNNING el depósito normal sigue funcionando.
 * Núcleo puro sin Bukkit: la guarda de inventario vive en
 * SeasonEconomyService.depositFromInventory (rechazo antes de tocar items y
 * devolución de lo no acreditado; verificación en cliente Paper PENDIENTE).
 * Aquí se fija el contrato acreditable vía executeBankDeposit.
 */
final class SeasonBankEndedDepositTest {

    private static SeasonState runningState() {
        return new SeasonState("temporada-b4", 1, 40);
    }

    private static SeasonState endedState() {
        SeasonState state = new SeasonState("temporada-b4", 40, 40);
        assertTrue(state.setFinale("DEFEAT", 40, "actor-b4", "sin sol"));
        return state;
    }

    @Test
    void endedDepositCreditsNothingAndMutatesNothing() {
        SeasonState state = endedState();
        EclipseBank bank = new EclipseBank();
        assertEquals(0, SeasonService.executeBankDeposit(state, bank, 5));
        assertEquals(0, bank.balance());
        assertEquals(0, bank.totalDeposited());
    }

    @Test
    void endedDepositKeepsExistingFundUntouched() {
        SeasonState state = endedState();
        EclipseBank bank = new EclipseBank();
        bank.deposit(30);
        int before = bank.balance();
        int depositedBefore = bank.totalDeposited();
        assertEquals(0, SeasonService.executeBankDeposit(state, bank, 7));
        assertEquals(before, bank.balance());
        assertEquals(depositedBefore, bank.totalDeposited());
    }

    @Test
    void runningDepositStillCreditsFund() {
        SeasonState state = runningState();
        EclipseBank bank = new EclipseBank();
        assertEquals(5, SeasonService.executeBankDeposit(state, bank, 5));
        assertEquals(5, bank.balance());
        assertEquals(5, bank.totalDeposited());
        assertEquals(2, SeasonService.executeBankDeposit(state, bank, 2));
        assertEquals(7, bank.balance());
        assertEquals(7, bank.totalDeposited());
    }

    @Test
    void invalidAmountsRejectedWithoutMutation() {
        SeasonState state = runningState();
        EclipseBank bank = new EclipseBank();
        assertEquals(0, SeasonService.executeBankDeposit(state, bank, 0));
        assertEquals(0, SeasonService.executeBankDeposit(state, bank, -3));
        assertEquals(0, bank.balance());
        assertEquals(0, bank.totalDeposited());
    }
}
