package mx.vicko.permadeath;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paso 4 (MiniMax C): una resurrección normal por UUID y override forzado con historial. */
class DeathRegistryTest {

    private static DeathRegistry registry(Path dir) {
        return new DeathRegistry(dir.toFile(), Logger.getLogger("DeathRegistryTest"));
    }

    @Test
    void normalReviveIsLimitedToOne(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        UUID uuid = UUID.randomUUID();
        registry.record(uuid, "Steve", "caída");

        assertTrue(registry.revive(uuid));
        assertTrue(registry.hasBeenRevived(uuid));
        assertFalse(registry.revive(uuid), "doble revive normal debe bloquearse");
    }

    @Test
    void secondDeathStaysBlockedForNormalRoute(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        UUID uuid = UUID.randomUUID();
        registry.record(uuid, "Alex", "lava");
        assertTrue(registry.revive(uuid));

        registry.record(uuid, "Alex", "zombi");
        assertFalse(registry.revive(uuid), "tras reboot y segunda muerte la vía normal sigue bloqueada");
        assertTrue(registry.hasBeenRevived(uuid));
    }

    @Test
    void forcedReviveRestoresButPreservesHistory(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        UUID uuid = UUID.randomUUID();
        registry.record(uuid, "Herobrine", "dragón");
        assertTrue(registry.revive(uuid));

        registry.record(uuid, "Herobrine", "wither");
        assertTrue(registry.reviveForced(uuid), "la vía forzada restaura aunque ya hubo revive normal");
        assertTrue(registry.hasBeenRevived(uuid), "el historial no debe borrarse con la vía forzada");
        assertTrue(registry.takePendingRevival(uuid), "la vía forzada deja retorno pendiente");

        registry.record(uuid, "Herobrine", "guardián");
        assertFalse(registry.revive(uuid), "la siguiente muerte sigue bloqueada para vías normales");
    }

    @Test
    void forcedReviveWithoutPriorNormalKeepsLimit(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        UUID uuid = UUID.randomUUID();
        registry.record(uuid, "Nico", "ahogamiento");

        assertTrue(registry.reviveForced(uuid));
        assertTrue(registry.hasBeenRevived(uuid));

        registry.record(uuid, "Nico", "fuego");
        assertFalse(registry.revive(uuid));
        assertFalse(registry.reviveForced(UUID.randomUUID()), "forzar un UUID sin muerte registrada falla");
    }

    @Test
    void nameResolutionDelegatesToUuid(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        UUID uuid = UUID.randomUUID();
        registry.record(uuid, "Valen", "enderman");

        assertNotNull(registry.findDead(uuid), "búsqueda directa por UUID");
        assertTrue(registry.revive("valen"), "los nombres solo resuelven la entrada; la mutación es por UUID");
        assertFalse(registry.revive("Valen"));
    }

    @Test
    void historyAndPendingSurviveReload(@TempDir Path dir) {
        UUID uuid = UUID.randomUUID();
        DeathRegistry first = registry(dir);
        first.record(uuid, "Aria", "rayo");
        assertTrue(first.revive(uuid));

        DeathRegistry second = registry(dir);
        second.load();
        assertTrue(second.hasBeenRevived(uuid), "revived.* persiste entre reinicios");
        assertEquals(0, second.list().size(), "el muerto revivido ya no lista como eliminado");
        assertTrue(second.takePendingRevival(uuid));
        assertFalse(second.takePendingRevival(uuid), "el retorno pendiente se consume una sola vez");
    }

    @Test
    void unknownTargetsFail(@TempDir Path dir) {
        DeathRegistry registry = registry(dir);
        assertFalse(registry.revive(UUID.randomUUID()));
        assertFalse(registry.revive("nadie"));
        assertFalse(registry.reviveForced("nadie"));
    }
}
