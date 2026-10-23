package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Banco Eclipse: saldos nunca negativos, sin overdraft, sin repetición de tx,
 * precios 12/8/30, extensión única con tope 45 (base 40 → 43) y escrow/reembolso idempotente.
 */
class EclipseBankTest {

    @Test
    void depositsRejectNegativeAndZeroWithoutMutation() {
        EclipseBank bank = new EclipseBank();
        assertFalse(bank.deposit(0));
        assertFalse(bank.deposit(-5));
        assertEquals(0, bank.balance());
        assertEquals(0, bank.totalDeposited());
    }

    @Test
    void overdraftNeverGoesNegative() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(7);
        assertFalse(bank.purchaseRitual("tx-1"));
        assertEquals(7, bank.balance());
        assertFalse(bank.isRitualOpen());
    }

    @Test
    void ritualCosts8OnceAndReplayIsIdempotent() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(8);
        assertTrue(bank.purchaseRitual("tx-ritual"));
        assertEquals(0, bank.balance());
        assertTrue(bank.isRitualOpen());
        // Replay del mismo tx: éxito sin cobrar dos veces.
        assertTrue(bank.purchaseRitual("tx-ritual"));
        assertEquals(0, bank.balance());
        assertEquals(8, bank.totalSpent());
        // Segundo ritual con otro tx: rechazado (único).
        bank.deposit(8);
        assertFalse(bank.purchaseRitual("tx-ritual-2"));
        assertEquals(8, bank.balance());
    }

    @Test
    void extensionCosts12OnceBase40To43() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(12);
        assertTrue(bank.purchaseExtension("tx-ext", 40));
        assertEquals(0, bank.balance());
        assertTrue(bank.isExtensionUsed());
        assertEquals(43, EclipseBank.extendedLength(40));
        // Replay idempotente sin doble cobro.
        assertTrue(bank.purchaseExtension("tx-ext", 40));
        assertEquals(0, bank.balance());
        // Segunda extensión: rechazada aunque haya fondos.
        bank.deposit(12);
        assertFalse(bank.purchaseExtension("tx-ext-2", 43));
        assertEquals(12, bank.balance());
    }

    @Test
    void extensionRejectedWhenItDoesNotFitNoChargeNoClamp() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(12);
        // 44 + 3 > 45: se rechaza SIN cobrar (el saldo queda intacto, sin clamp).
        assertFalse(bank.purchaseExtension("tx-ext-full", 44));
        assertEquals(12, bank.balance());
        assertFalse(bank.isExtensionUsed());
    }

    @Test
    void secondSunReserveRejectedAndConfirmChargesOnce() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(60);
        assertTrue(bank.reserveSun("tx-sol-A"));
        assertEquals(30, bank.balance());
        assertFalse(bank.reserveSun("tx-sol-B"));
        assertEquals(30, bank.balance());
        assertTrue(bank.confirmSun("tx-sol-A"));
        assertTrue(bank.isSunReserved());
        assertEquals(30, bank.totalSpent());
        assertFalse(bank.confirmSun("tx-sol-B"));
        assertEquals(30, bank.totalSpent());
        assertTrue(bank.confirmSun("tx-sol-A"));
        assertEquals(30, bank.totalSpent());
    }

    @Test
    void sunReserveConfirmCancelAreIdempotent() {
        EclipseBank bank = new EclipseBank();
        bank.deposit(30);
        assertTrue(bank.reserveSun("tx-sol"));
        assertEquals(0, bank.balance());
        assertTrue(bank.confirmSun("tx-sol"));
        assertTrue(bank.isSunReserved());
        // Confirm repetido: éxito sin cobrar de más.
        assertTrue(bank.confirmSun("tx-sol"));
        assertEquals(30, bank.totalSpent());

        EclipseBank other = new EclipseBank();
        other.deposit(30);
        assertTrue(other.reserveSun("tx-sol-2"));
        assertTrue(other.cancelSun("tx-sol-2"));
        assertEquals(30, other.balance());
        assertFalse(other.isSunReserved());
        assertFalse(other.cancelSun("tx-sol-2"));
    }
}
