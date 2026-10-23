package mx.vicko.permadeath.odyssey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class OdysseyGravityZoneTest {
    private final OdysseyGravityController.Zone zone =
            new OdysseyGravityController.Zone(UUID.randomUUID(), 10.0, 10.0, 70, 90, 5.0, 0L);

    @Test
    void containsPlayerInsideCylinder() {
        assertTrue(zone.contains(new Location(null, 10.5, 70.0, 10.5)));
        assertTrue(zone.contains(new Location(null, 14.9, 90.0, 10.0)));
    }

    @Test
    void rejectsPlayerAboveOrOutside() {
        assertFalse(zone.contains(new Location(null, 10.0, 90.01, 10.0)));
        assertFalse(zone.contains(new Location(null, 10.0, 69.99, 10.0)));
        assertFalse(zone.contains(new Location(null, 15.01, 80.0, 10.0)));
        assertFalse(zone.contains(null));
    }
}
