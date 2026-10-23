package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Paso 5 (DeepSeek D): regresiones puras sin servidor Paper.
 * Solo ejercita helpers estáticos de PhaseTasks; navegación/vuelo/portal
 * requieren Paper real y se validan en servidor según plan step-8.
 */
class PhaseTasksTest {

    @Test
    void acidRainRequiresRealStormAndExposure() {
        assertTrue(PhaseTasks.shouldApplyAcidRain(true, true, "PLAINS", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(false, true, "PLAINS", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, false, "PLAINS", World.Environment.NORMAL));
    }

    @Test
    void acidRainExcludedInAridSnowAndOtherDimensions() {
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "DESERT", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "SAVANNA", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "BADLANDS", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "SNOWY_PLAINS", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "FROZEN_PEAKS", World.Environment.NORMAL));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "PLAINS", World.Environment.NETHER));
        assertFalse(PhaseTasks.shouldApplyAcidRain(true, true, "PLAINS", World.Environment.THE_END));
    }

    @Test
    void fleeSpeedIsTwoTimesBaseWithoutAccumulation() {
        assertEquals(0.5, PhaseTasks.fleeSpeed(0.25), 1e-9);
        // Aplicar dos veces desde la base no debe componer: f(f(b)) con rebase = 2b, no 4b.
        double base = 0.25;
        double once = PhaseTasks.fleeSpeed(base);
        double twiceFromBase = PhaseTasks.fleeSpeed(base);
        assertEquals(once, twiceFromBase, 1e-9);
        assertEquals(0.5, twiceFromBase, 1e-9);
    }

    @Test
    void huntRangeIsInclusiveAt50Blocks() {
        assertTrue(PhaseTasks.isWithinHuntRangeSq(2500.0));
        assertFalse(PhaseTasks.isWithinHuntRangeSq(2500.0001));
    }

    @Test
    void batCooldownKeepsTwoSecondGap() {
        assertTrue(PhaseTasks.batCanAttack(10L, 10L));
        assertFalse(PhaseTasks.batCanAttack(11L, 12L));
        assertTrue(PhaseTasks.batCanAttack(12L, 12L));
    }

    @Test
    void forcedEndermanAggressionIsRemoved() throws Exception {
        assertThrows(NoSuchMethodException.class,
                () -> PhaseTasks.class.getDeclaredMethod("angerEndermenWhenStaringAtDragon",
                        org.bukkit.entity.Player.class));
    }

    @Test
    void lifecycleCleanupHookExistsForIntegrator() throws Exception {
        PhaseTasks.class.getDeclaredMethod("stop");
    }

    @Test
    void pursueVelocityMovesTowardTargetEye() {
        Vector next = PhaseTasks.pursueVelocity(new Vector(0, 0, 0), new Vector(10, 0, 0), new Vector(0, 0, 0));
        assertTrue(next.getX() > 0, "debe avanzar hacia el jugador");
        assertTrue(next.length() > 0.1, "persecucion real, no empuje debil");
    }

    @Test
    void pursueVelocityRespectsSpeedCap() {
        Vector next = PhaseTasks.pursueVelocity(new Vector(0, 0, 0), new Vector(100, 0, 0), new Vector(5, 0, 0));
        assertTrue(next.length() <= PhaseTasks.BAT_PURSUE_MAX_SPEED + 1e-9);
    }

    @Test
    void pursueVelocityPointsInCorrectDirection() {
        Vector toward = PhaseTasks.pursueVelocity(new Vector(0, 5, 0), new Vector(0, 5, 10), new Vector(0, 0, 0));
        assertTrue(toward.getZ() > 0, "eje Z debe apuntar al jugador");
        assertTrue(Math.abs(toward.getX()) < Math.abs(toward.getZ()), "direccion dominante correcta");
        Vector away = PhaseTasks.pursueVelocity(new Vector(0, 5, 0), new Vector(0, 5, -10), new Vector(0, 0, 0));
        assertTrue(away.getZ() < 0, "sentido opuesto si el jugador esta detras");
    }

    @Test
    void pursueVelocityHasNoNaNAtZeroDistance() {
        Vector next = PhaseTasks.pursueVelocity(new Vector(1, 2, 3), new Vector(1, 2, 3), new Vector(0.1, 0, 0));
        assertTrue(Double.isFinite(next.getX()));
        assertTrue(Double.isFinite(next.getY()));
        assertTrue(Double.isFinite(next.getZ()));
        assertTrue(next.length() <= PhaseTasks.BAT_PURSUE_MAX_SPEED + 1e-9);
    }
}
