package mx.vicko.permadeath;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import mx.vicko.permadeath.SeasonObjectives.Listener;
import mx.vicko.permadeath.SeasonObjectives.Objective;
import mx.vicko.permadeath.SeasonObjectives.Reward;
import mx.vicko.permadeath.SeasonObjectives.RewardConfig;
import mx.vicko.permadeath.SeasonObjectives.Status;

/**
 * Bloque 1 (B1): persistencia real de objetivos en {@code season-state.yml}.
 *
 * <p>Cubre los seis hallazgos del revisor sin Bukkit: el beneficiario (UUID) de
 * pagos pendientes sobrevive al reload, la muestra de esporas / noche sin dormir
 * / racha no se pierden ni se reanuncian, el retry no duplica, el proveedor que
 * lanza (NPE de arranque) queda contenido y la config de recompensas aplica
 * overrides sin romper defaults.
 */
class SeasonPersistenceTest {

    private static final class Recorder implements Listener {
        final List<Objective> announced = new ArrayList<>();
        final List<Objective> completed = new ArrayList<>();
        final List<Reward> granted = new ArrayList<>();
        final List<Reward> pending = new ArrayList<>();

        @Override
        public void onAnnounce(Objective objective, String message) {
            announced.add(objective);
        }

        @Override
        public void onComplete(Objective objective, UUID actor, String message) {
            completed.add(objective);
        }

        @Override
        public void onReward(Objective objective, Reward reward, boolean ok, UUID actor, String message) {
            (ok ? granted : pending).add(reward);
        }
    }

    /** Replica el sync SeasonService: snapshot vivo -> SeasonState (sets + detalles). */
    private static void syncToState(SeasonObjectives model, SeasonState state) {
        Map<String, Object> snap = model.snapshot();
        Object objs = snap.get("objectives");
        if (!(objs instanceof Map<?, ?> map)) {
            return;
        }
        for (Objective objective : Objective.values()) {
            Object entry = map.get(objective.name());
            if (entry instanceof Map<?, ?> entryMap) {
                state.setObjectiveDetail(objective.name(),
                        SeasonState.ObjectiveDetail.fromMap(entryMap));
                Status status = Status.valueOf(entryMap.get("status").toString());
                if (status != Status.PENDING) {
                    state.markObjectiveAnnounced(objective.name());
                }
                if (status == Status.COMPLETED || status == Status.REWARDED) {
                    state.markObjectiveCompleted(objective.name());
                }
                if (status == Status.REWARDED) {
                    state.markRewardPaid(objective.name());
                }
            }
        }
    }

    /** Replica el snapshot SeasonService: detalles reales + fallback a sets. */
    private static Map<String, Object> snapshotFromState(SeasonState state, int currentAct) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("seasonId", state.seasonId());
        snap.put("currentAct", currentAct);
        Map<String, Object> objs = new LinkedHashMap<>();
        for (Objective objective : Objective.values()) {
            String name = objective.name();
            SeasonState.ObjectiveDetail detail = state.objectiveDetail(name);
            if (detail != null) {
                objs.put(name, detail.toMap());
                continue;
            }
            Status status = state.paidRewards().contains(name) ? Status.REWARDED
                    : state.completedObjectives().contains(name) ? Status.COMPLETED
                    : state.announcedObjectives().contains(name) ? Status.ANNOUNCED
                    : Status.PENDING;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", status.name());
            entry.put("announcedAt", 0L);
            entry.put("completedAt", 0L);
            entry.put("rewardedAt", 0L);
            entry.put("actor", "");
            entry.put("flags", List.of());
            entry.put("streak", 0);
            entry.put("lastStreakDay", -1);
            entry.put("lastNightDay", -1);
            objs.put(name, entry);
        }
        snap.put("objectives", objs);
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryOf(SeasonObjectives model, Objective objective) {
        Map<String, Object> snap = model.snapshot();
        return (Map<String, Object>) ((Map<?, ?>) snap.get("objectives")).get(objective.name());
    }

    @Test
    void partialProgressSurvivesReloadWithoutRepeatOrLoss(@TempDir Path dir) throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        Recorder first = new Recorder();
        // Sin proveedor: el pago queda pendiente con beneficiario persistente.
        SeasonObjectives model = SeasonObjectives.forNewSeason("s1", 2, RewardConfig.defaults(),
                now::get, first);
        UUID actor = UUID.randomUUID();

        model.onEsporaSample(actor);
        model.onNightEnded(6, false, actor);
        assertEquals(Status.COMPLETED, model.status(Objective.NOCHE_INSOMNE));
        model.onDayEvaluated(20, 2);
        model.onDayEvaluated(21, 2);
        assertEquals(2, model.currentStreak());

        SeasonState state = new SeasonState("s1", 6, 40);
        syncToState(model, state);
        SeasonStore store = new SeasonStore(dir);
        store.save(state);

        SeasonState loaded = store.load("s1");
        assertEquals(state, loaded);
        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(
                snapshotFromState(loaded, 2), "s1", RewardConfig.defaults(), now::get, new Recorder());

        Map<String, Object> night = entryOf(reloaded, Objective.NOCHE_INSOMNE);
        assertEquals("COMPLETED", night.get("status"));
        assertEquals(actor.toString(), night.get("actor"));
        assertTrue(night.get("flags").toString().contains("NOCHE_SIN_DORMIR"));
        assertTrue(night.get("flags").toString().contains("ESPORA_MUESTREADA"));
        assertEquals(6, ((Number) night.get("lastNightDay")).intValue());
        Map<String, Object> streak = entryOf(reloaded, Objective.TRES_DIAS_DIEZMADOS);
        assertEquals(2, ((Number) streak.get("streak")).intValue());
        assertEquals(21, ((Number) streak.get("lastStreakDay")).intValue());

        // Sin reanuncios ni recompletions al rehidratar en estado parcial.
        Recorder second = new Recorder();
        SeasonObjectives quiet = SeasonObjectives.fromSnapshot(
                snapshotFromState(loaded, 2), "s1", RewardConfig.defaults(), now::get, second);
        assertTrue(second.announced.isEmpty());
        assertTrue(second.completed.isEmpty());

        // El retry paga una sola vez al conectar proveedor.
        AtomicInteger payments = new AtomicInteger();
        quiet.bindRewardProvider((a, r) -> {
            payments.incrementAndGet();
            return true;
        });
        assertEquals(1, quiet.retryPendingRewards());
        assertEquals(0, quiet.retryPendingRewards());
        assertEquals(1, payments.get());
        assertEquals(Status.REWARDED, quiet.status(Objective.NOCHE_INSOMNE));
    }

    @Test
    void rewardedObjectivesAreNotRepaidAfterReload(@TempDir Path dir) throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        SeasonObjectives model = SeasonObjectives.forNewSeason("s1", 3, RewardConfig.defaults(),
                now::get, Listener.silent());
        model.bindRewardProvider((a, r) -> true);
        model.onFirstCore(UUID.randomUUID());
        assertEquals(Status.REWARDED, model.status(Objective.PRIMER_NUCLEO));

        SeasonState state = new SeasonState("s1", 10, 40);
        syncToState(model, state);
        SeasonStore store = new SeasonStore(dir);
        store.save(state);

        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(
                snapshotFromState(store.load("s1"), 3), "s1", RewardConfig.defaults(),
                now::get, Listener.silent());
        AtomicInteger payments = new AtomicInteger();
        reloaded.bindRewardProvider((a, r) -> {
            payments.incrementAndGet();
            return true;
        });
        assertEquals(0, reloaded.retryPendingRewards());
        assertEquals(0, payments.get());
    }

    @Test
    void throwingProviderIsContainedAndRetriedLater() {
        Recorder recorder = new Recorder();
        SeasonObjectives model = SeasonObjectives.forNewSeason("s1", 5, RewardConfig.defaults(),
                new AtomicLong(1_000L)::get, recorder);
        AtomicInteger calls = new AtomicInteger();
        model.bindRewardProvider((a, r) -> {
            if (calls.incrementAndGet() == 1) {
                throw new NullPointerException("items aún no inicializados");
            }
            return true;
        });

        model.onNightHuntCompleted(UUID.randomUUID());
        assertEquals(Status.COMPLETED, model.status(Objective.CACERIA));
        assertEquals(1, model.retryPendingRewards());
        assertEquals(Status.REWARDED, model.status(Objective.CACERIA));
        assertEquals(0, model.retryPendingRewards());
    }

    @Test
    void rewardConfigKeepsDefaultsAndAppliesOverrides() {
        RewardConfig defaults = SeasonService.buildRewardConfig(null, null, null);
        for (Objective objective : Objective.values()) {
            assertEquals(SeasonObjectives.Currency.ESQUIRLAS,
                    defaults.rewardFor(objective).currency());
            assertEquals(1, defaults.rewardFor(objective).amount());
        }

        Map<String, String> currencies = Map.of("PORTAL_RESERVA", "nucleos");
        Map<String, Integer> amounts = Map.of("PORTAL_RESERVA", 5);
        RewardConfig custom = SeasonService.buildRewardConfig(currencies, amounts, null);
        assertEquals(SeasonObjectives.Currency.NUCLEOS,
                custom.rewardFor(Objective.PORTAL_RESERVA).currency());
        assertEquals(5, custom.rewardFor(Objective.PORTAL_RESERVA).amount());
        assertEquals(1, custom.rewardFor(Objective.FORTALEZA).amount());

        RewardConfig invalid = SeasonService.buildRewardConfig(
                Map.of("FORTALEZA", "MONEDA_FALSA"), Map.of(), null);
        assertEquals(SeasonObjectives.Currency.ESQUIRLAS,
                invalid.rewardFor(Objective.FORTALEZA).currency());

        RewardConfig zeroed = SeasonService.buildRewardConfig(
                Map.of(), Map.of("CACERIA", 0), null);
        assertTrue(!zeroed.rewardFor(Objective.CACERIA).payable());
    }

    @Test
    void storeRoundTripKeepsDetailsAndIsByteIdempotent(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 14, 40);
        state.markActAnnounced(3);
        state.markObjectiveAnnounced("NOCHE_INSOMNE");
        SeasonState.ObjectiveDetail detail = new SeasonState.ObjectiveDetail();
        detail.status = "COMPLETED";
        detail.actor = UUID.randomUUID().toString();
        detail.flags.add("ESPORA_MUESTREADA");
        detail.flags.add("NOCHE_SIN_DORMIR");
        detail.streak = 2;
        detail.lastStreakDay = 21;
        detail.lastNightDay = 6;
        state.setObjectiveDetail("NOCHE_INSOMNE", detail);
        store.save(state);

        SeasonState loaded = store.load("s1");
        assertEquals(state, loaded);
        assertEquals(detail, loaded.objectiveDetail("NOCHE_INSOMNE"));

        byte[] first = Files.readAllBytes(store.stateFile());
        store.save(store.load("s1"));
        assertEquals(new String(first, StandardCharsets.UTF_8),
                Files.readString(store.stateFile(), StandardCharsets.UTF_8));
    }

    @Test
    void nonPayableRewardCallbackCarriesPersistentActor() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicReference<UUID> seen = new AtomicReference<>();
        AtomicReference<Boolean> seenOk = new AtomicReference<>();
        Listener listener = new Listener() {
            @Override
            public void onReward(Objective objective, Reward reward, boolean ok, UUID actor, String message) {
                seen.set(actor);
                seenOk.set(ok);
            }
        };
        RewardConfig config = RewardConfig.defaults().without(Objective.PRIMER_NUCLEO);
        SeasonObjectives model = SeasonObjectives.forNewSeason("s1", 3, config, now::get, listener);
        UUID actor = UUID.randomUUID();
        model.onFirstCore(actor);
        assertEquals(Status.REWARDED, model.status(Objective.PRIMER_NUCLEO));
        assertEquals(actor, seen.get());
        assertEquals(Boolean.FALSE, seenOk.get());

        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(model.snapshot(), "s1",
                RewardConfig.defaults().without(Objective.PRIMER_NUCLEO), now::get, Listener.silent());
        assertEquals(Status.REWARDED, reloaded.status(Objective.PRIMER_NUCLEO));
        assertEquals(actor.toString(), entryOf(reloaded, Objective.PRIMER_NUCLEO).get("actor"));
    }

    @Test
    void nullProviderCallbackCarriesPersistentActorAndRehydrates() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicReference<UUID> seen = new AtomicReference<>();
        Listener listener = new Listener() {
            @Override
            public void onReward(Objective objective, Reward reward, boolean ok, UUID actor, String message) {
                seen.set(actor);
            }
        };
        SeasonObjectives model = SeasonObjectives.forNewSeason("s1", 3, RewardConfig.defaults(),
                now::get, listener);
        UUID actor = UUID.randomUUID();
        model.onFirstCore(actor);
        assertEquals(Status.COMPLETED, model.status(Objective.PRIMER_NUCLEO));
        assertEquals(actor, seen.get());

        AtomicReference<UUID> seenAfterReload = new AtomicReference<>();
        Listener second = new Listener() {
            @Override
            public void onReward(Objective objective, Reward reward, boolean ok, UUID actor, String message) {
                seenAfterReload.set(actor);
            }
        };
        SeasonObjectives reloaded = SeasonObjectives.fromSnapshot(model.snapshot(), "s1",
                RewardConfig.defaults(), now::get, second);
        assertEquals(Status.COMPLETED, reloaded.status(Objective.PRIMER_NUCLEO));
        assertEquals(0, reloaded.retryPendingRewards());
        assertEquals(actor, seenAfterReload.get());
        assertEquals(actor.toString(), entryOf(reloaded, Objective.PRIMER_NUCLEO).get("actor"));
    }

    @Test
    void sealedGateKeepsDetailedProgressUntilExplicitReload(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState state = new SeasonState("s1", 14, 40);
        state.markActAnnounced(3);
        SeasonState.ObjectiveDetail detail = new SeasonState.ObjectiveDetail();
        detail.status = "COMPLETED";
        detail.actor = UUID.randomUUID().toString();
        detail.flags.add("ESPORA_MUESTREADA");
        detail.streak = 2;
        detail.lastStreakDay = 21;
        detail.lastNightDay = 6;
        state.setObjectiveDetail("NOCHE_INSOMNE", detail);
        store.save(state);

        // El archivo se corrompe en disco (corte de luz, edicion manual).
        byte[] corrupt = ":: basura sin sentido @@##".getBytes(StandardCharsets.UTF_8);
        Files.write(store.stateFile(), corrupt);
        SeasonStore.WriteGate gate = new SeasonStore.WriteGate();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                store.archiveIfSeasonChanged("s1");
                store.load("s1");
                gate.unseal();
            } catch (SeasonStore.CorruptStateException expected) {
                gate.seal(expected.getMessage());
            }
        }
        assertTrue(gate.isSealed());
        assertThrows(SeasonStore.CorruptStateException.class, () -> store.load("s1"));
        // Arranques/apagados sellados: ningun save; bytes intactos para diagnostico.
        assertArrayEquals(corrupt, Files.readAllBytes(store.stateFile()));

        // Reload explicito tras restaurar el historial: recupera todo, sin perdidas.
        store.save(state);
        try {
            store.archiveIfSeasonChanged("s1");
            SeasonState reloaded = store.load("s1");
            gate.unseal();
            assertEquals(state, reloaded);
            assertEquals(detail, reloaded.objectiveDetail("NOCHE_INSOMNE"));
        } catch (SeasonStore.CorruptStateException failed) {
            gate.seal(failed.getMessage());
        }
        assertFalse(gate.isSealed());
    }

    @Test
    void seasonChangeArchivesDetailedHistory(@TempDir Path dir) throws Exception {
        SeasonStore store = new SeasonStore(dir);
        SeasonState old = new SeasonState("s1", 14, 40);
        old.markActAnnounced(3);
        SeasonState.ObjectiveDetail detail = new SeasonState.ObjectiveDetail();
        detail.status = "REWARDED";
        detail.actor = UUID.randomUUID().toString();
        old.setObjectiveDetail("PRIMER_NUCLEO", detail);
        store.save(old);

        Optional<Path> backup = store.archiveIfSeasonChanged("s2");
        assertTrue(backup.isPresent());
        SeasonState fresh = store.load("s2");
        assertTrue(fresh.announcedActs().isEmpty());
        store.save(fresh);

        SeasonState kept = new SeasonStore(backup.get()).load("s1");
        assertEquals(old, kept);
        assertEquals(detail, kept.objectiveDetail("PRIMER_NUCLEO"));
        assertEquals("s2", store.load("s2").seasonId());
    }
}
