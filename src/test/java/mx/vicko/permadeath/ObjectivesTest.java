package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import mx.vicko.permadeath.SeasonObjectives.Listener;
import mx.vicko.permadeath.SeasonObjectives.Objective;
import mx.vicko.permadeath.SeasonObjectives.Reward;
import mx.vicko.permadeath.SeasonObjectives.RewardConfig;
import mx.vicko.permadeath.SeasonObjectives.SeasonClock;
import mx.vicko.permadeath.SeasonObjectives.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloque 1.6 (B1): contrato de {@link SeasonObjectives}. Modelo puro, sin Bukkit ni MockBukkit: los
 * eventos se inyectan a mano, el clock es inyectado y la persistencia se prueba con el mapa que
 * {@code SeasonStore} escribira en {@code season-state.yml}.
 */
class ObjectivesTest {

    /** Registro de todo lo que el modelo emitiria hacia bossbar/chat/log. */
    private static final class Recorder implements Listener {
        private final List<Objective> announced = new ArrayList<>();
        private final List<Objective> completed = new ArrayList<>();
        private final List<Reward> granted = new ArrayList<>();
        private final List<Reward> pendingRewards = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();

        @Override
        public void onAnnounce(Objective objective, String message) {
            announced.add(objective);
            messages.add(message);
        }

        @Override
        public void onComplete(Objective objective, UUID actor, String message) {
            completed.add(objective);
            messages.add(message);
        }

        @Override
        public void onReward(Objective objective, Reward reward, boolean ok, UUID actor, String message) {
            (ok ? granted : pendingRewards).add(reward);
            messages.add(message);
        }

        @Override
        public void onProgress(Objective objective, String message) {
            messages.add(message);
        }
    }

    private static SeasonClock clock(AtomicLong now) {
        return now::get;
    }

    /** Temporada con proveedor de pagos conectado: el camino normal de una temporada en curso. */
    private static SeasonObjectives model(int act, Recorder recorder, AtomicLong now) {
        return SeasonObjectives.forNewSeason("temporada-test", act, RewardConfig.defaults(), clock(now), recorder)
                .bindRewardProvider((actor, reward) -> true);
    }

    /** Temporada sin proveedor de pagos (banco/chispas aun no conectados). */
    private static SeasonObjectives modelWithoutProvider(int act, Recorder recorder, AtomicLong now) {
        return SeasonObjectives.forNewSeason("temporada-test", act, RewardConfig.defaults(), clock(now), recorder);
    }

    @Test
    void announcementHappensOnceAndOnlyForReachedActs() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(1, recorder, new AtomicLong(1_000L));

        assertEquals(List.of(Objective.PORTAL_RESERVA), recorder.announced,
                "al arrancar la temporada solo se anuncia el objetivo del acto I");

        model.onActChanged(1);
        model.onActChanged(1);
        assertEquals(1, recorder.announced.size(), "repetir el acto no reanuncia");

        model.onActChanged(2);
        model.onActChanged(3);
        assertEquals(List.of(Objective.PORTAL_RESERVA, Objective.NOCHE_INSOMNE, Objective.PRIMER_NUCLEO),
                recorder.announced, "cada acto alcanzado anuncia su objetivo una unica vez");
        assertFalse(recorder.announced.contains(Objective.FORTALEZA),
                "el objetivo del acto IV no se anuncia antes de llegar");
    }

    @Test
    void completionHappensOncePerObjective() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(7, recorder, new AtomicLong(1_000L));
        UUID player = UUID.randomUUID();

        model.onPortalOpened(player, "minecraft:reserva");
        model.onPortalOpened(player, "minecraft:reserva");
        assertEquals(List.of(Objective.PORTAL_RESERVA), recorder.completed);

        model.onFortressCompleted(player);
        model.onFortressCompleted(player);
        model.onNightHuntCompleted(player);
        assertEquals(List.of(Objective.PORTAL_RESERVA, Objective.FORTALEZA, Objective.CACERIA),
                recorder.completed);
        assertEquals(Status.REWARDED, model.status(Objective.FORTALEZA));
    }

    @Test
    void portalOutsideTheReservaDoesNotCompleteNorReplaceTheGoal() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(1, recorder, new AtomicLong(1_000L));

        model.onPortalOpened(UUID.randomUUID(), "minecraft:the_nether");
        assertTrue(recorder.completed.isEmpty(), "solo el portal de la Reserva cumple la meta I");
        assertEquals(Status.ANNOUNCED, model.status(Objective.PORTAL_RESERVA));
    }

    @Test
    void rewardIsPaidExactlyOnce() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(7, recorder, new AtomicLong(1_000L));
        AtomicInteger payments = new AtomicInteger();
        model.bindRewardProvider((actor, reward) -> {
            payments.incrementAndGet();
            return true;
        });

        model.onFirstCore(UUID.randomUUID());
        assertEquals(1, payments.get());
        assertEquals(1, recorder.granted.size());

        model.onFirstCore(UUID.randomUUID());
        assertEquals(1, payments.get(), "una segunda senal no vuelve a pagar");
        assertEquals(0, model.retryPendingRewards(), "no queda nada pendiente");
        assertEquals(1, payments.get());
        assertTrue(model.isRewarded(Objective.PRIMER_NUCLEO));
    }

    @Test
    void absentProviderLeavesRewardPendingWithoutExceptionOrDoublePayment() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = modelWithoutProvider(7, recorder, new AtomicLong(1_000L));

        model.onFortressCompleted(UUID.randomUUID());
        assertEquals(Status.COMPLETED, model.status(Objective.FORTALEZA),
                "sin proveedor el objetivo queda cumplido y el pago pendiente");
        assertEquals(List.of(Objective.FORTALEZA), model.pendingRewards());
        assertEquals(1, recorder.pendingRewards.size(), "se avisa con mensaje, sin excepcion");

        AtomicInteger payments = new AtomicInteger();
        model.bindRewardProvider((actor, reward) -> payments.incrementAndGet() > 0);
        assertEquals(1, model.retryPendingRewards());
        assertEquals(1, payments.get());
        assertEquals(Status.REWARDED, model.status(Objective.FORTALEZA));

        assertEquals(0, model.retryPendingRewards());
        assertEquals(1, payments.get(), "reintentar no duplica el pago");
    }

    @Test
    void failingProviderIsContainedAndRetriedLater() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(7, recorder, new AtomicLong(1_000L));
        AtomicInteger calls = new AtomicInteger();
        model.bindRewardProvider((actor, reward) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("proveedor caido");
            }
            return true;
        });

        model.onNightHuntCompleted(UUID.randomUUID());
        assertEquals(Status.COMPLETED, model.status(Objective.CACERIA), "la excepcion no rompe el estado");
        assertEquals(1, model.retryPendingRewards());
        assertTrue(model.isRewarded(Objective.CACERIA));
    }

    @Test
    void unconfiguredRewardDegradesToMessage() {
        Recorder recorder = new Recorder();
        RewardConfig config = RewardConfig.defaults().without(Objective.PRIMER_NUCLEO);
        SeasonObjectives model = SeasonObjectives.forNewSeason("temporada-test", 3, config,
                clock(new AtomicLong(1_000L)), recorder);
        AtomicInteger payments = new AtomicInteger();
        model.bindRewardProvider((actor, reward) -> {
            payments.incrementAndGet();
            return true;
        });

        model.onFirstCore(UUID.randomUUID());
        assertEquals(0, payments.get(), "sin recompensa configurada no se llama al proveedor");
        assertTrue(model.isRewarded(Objective.PRIMER_NUCLEO), "el objetivo queda cerrado, no reintentable");
        assertEquals(1, recorder.pendingRewards.size());
        assertTrue(recorder.messages.get(recorder.messages.size() - 1).contains("sin recompensa"));
    }

    @Test
    void streakOfThreeRealDaysWithFewAliveIsCutBackAtFive() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(6, recorder, new AtomicLong(1_000L));

        model.onDayEvaluated(10, 4);
        model.onDayEvaluated(10, 4);
        assertEquals(1, model.currentStreak(), "un doble rollover del mismo dia no suma dos veces");

        model.onDayEvaluated(11, 3);
        assertEquals(2, model.currentStreak());

        model.onDayEvaluated(12, 5);
        assertEquals(0, model.currentStreak(), "la vuelta a 5 vivos corta la racha");
        assertFalse(model.isCompleted(Objective.TRES_DIAS_DIEZMADOS));

        model.onDayEvaluated(13, 4);
        model.onDayEvaluated(14, 4);
        model.onDayEvaluated(15, 4);
        assertEquals(Status.REWARDED, model.status(Objective.TRES_DIAS_DIEZMADOS));
        assertEquals(1, recorder.completed.size(), "el objetivo se cumple una sola vez");

        model.onDayEvaluated(16, 1);
        assertEquals(1, recorder.completed.size());
    }

    @Test
    void nightObjectiveNeedsBothSleeplessNightAndSporeSample() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(2, recorder, new AtomicLong(1_000L));
        UUID player = UUID.randomUUID();

        model.onEsporaSample(player);
        assertFalse(model.isCompleted(Objective.NOCHE_INSOMNE), "la muestra sola no basta");

        model.onNightEnded(5, true, player);
        assertFalse(model.isCompleted(Objective.NOCHE_INSOMNE), "una noche con alguien durmiendo no cuenta");

        model.onNightEnded(6, false, player);
        assertEquals(Status.REWARDED, model.status(Objective.NOCHE_INSOMNE));
        assertEquals(1, recorder.completed.size());
    }

    @Test
    void bankGoalOnlyCountsBeforeTheFinalDay() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(7, recorder, new AtomicLong(1_000L));
        UUID player = UUID.randomUUID();

        model.onBankFunded(player, 40, 40, 12);
        assertFalse(model.isCompleted(Objective.BANCO_ANTES_DEL_FINAL), "el ultimo dia llega tarde");

        model.onBankFunded(player, 37, 40, 0);
        assertFalse(model.isCompleted(Objective.BANCO_ANTES_DEL_FINAL), "un fondo vacio no cumple");

        model.onBankFunded(player, 37, 40, 12);
        assertEquals(Status.REWARDED, model.status(Objective.BANCO_ANTES_DEL_FINAL));
    }

    @Test
    void rehydrationDoesNotRepeatAnnounceCompleteOrReward() {
        AtomicLong now = new AtomicLong(1_000L);
        Recorder first = new Recorder();
        SeasonObjectives original = SeasonObjectives.forNewSeason("temporada-test", 4,
                RewardConfig.defaults(), clock(now), first);
        AtomicInteger payments = new AtomicInteger();
        original.bindRewardProvider((actor, reward) -> {
            payments.incrementAndGet();
            return true;
        });
        original.onFirstCore(UUID.randomUUID());
        original.onFortressCompleted(UUID.randomUUID());
        original.onDayEvaluated(20, 2);
        // Dos objetivos cumplidos con proveedor conectado: dos pagos, uno por objetivo.
        now.set(5_000L);

        Map<String, Object> snapshot = new HashMap<>(original.snapshot());
        Recorder second = new Recorder();
        AtomicInteger reloadedPayments = new AtomicInteger();
        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(snapshot, "temporada-test",
                RewardConfig.defaults(), clock(now), second);
        reloaded.bindRewardProvider((actor, reward) -> {
            reloadedPayments.incrementAndGet();
            return true;
        });

        assertTrue(second.announced.isEmpty(), "la rehidratacion no reanuncia");
        assertTrue(second.completed.isEmpty(), "la rehidratacion no recumple");
        assertEquals(2, payments.get(), "un pago por objetivo cumplido, ninguno extra");
        assertEquals(0, reloadedPayments.get(), "la rehidratacion no repaga");
        assertEquals(Status.REWARDED, reloaded.status(Objective.PRIMER_NUCLEO));
        assertEquals(Status.REWARDED, reloaded.status(Objective.FORTALEZA));
        assertEquals(1, reloaded.currentStreak(), "la racha persiste entre arranques");
        assertEquals(4, reloaded.currentAct());
        assertEquals("temporada-test", reloaded.seasonId());
    }

    @Test
    void rehydrationOfAPartialStateAnnouncesOnlyWhatWasMissing() {
        Recorder first = new Recorder();
        SeasonObjectives original = SeasonObjectives.forNewSeason("temporada-test", 2,
                RewardConfig.defaults(), clock(new AtomicLong(1_000L)), first);
        Map<String, Object> snapshot = new HashMap<>(original.snapshot());

        Recorder second = new Recorder();
        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(snapshot, "temporada-test",
                RewardConfig.defaults(), clock(new AtomicLong(1_000L)), second);

        assertTrue(second.announced.isEmpty(), "acto I y II ya estaban anunciados");
        reloaded.onActChanged(3);
        assertEquals(List.of(Objective.PRIMER_NUCLEO), second.announced);
    }

    @Test
    void corruptedStateIsToleratedWithoutThrowing() {
        Recorder recorder = new Recorder();
        Map<String, Object> broken = new HashMap<>();
        broken.put("seasonId", 42);
        broken.put("currentAct", "basura");
        broken.put("objectives", "no es un mapa");

        SeasonObjectives model = SeasonObjectives.fromSnapshot(broken, "temporada-test",
                RewardConfig.defaults(), clock(new AtomicLong(1_000L)), recorder);

        assertEquals("temporada-test", model.seasonId(), "sin seasonId valido se usa el esperado");
        assertEquals(1, model.currentAct(), "un acto ilegible cae al acto I, no rompe");
        assertEquals(Status.ANNOUNCED, model.status(Objective.PORTAL_RESERVA));
        assertEquals(7, model.openObjectives().size(), "nada se pierde ni se marca cumplido por error");
    }

    @Test
    void openObjectivesPersistAcrossActsUntilTheirEventArrives() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = model(1, recorder, new AtomicLong(1_000L));

        model.onActChanged(2);
        model.onActChanged(3);
        assertEquals(Status.ANNOUNCED, model.status(Objective.PORTAL_RESERVA),
                "el portal del acto I sigue abierto: la Reserva se genera al dia 8 y no se adelanta");
        assertTrue(model.openObjectives().contains(Objective.PORTAL_RESERVA));

        model.onPortalOpened(UUID.randomUUID(), "reserva:reserva");
        assertEquals(Status.REWARDED, model.status(Objective.PORTAL_RESERVA),
                "cumplirlo en un acto posterior es valido");
    }
}
