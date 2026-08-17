package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EndDragonFightCompatibilityTest {

    @Test
    void minecraft2612IsExplicitlySupported() {
        EndDragonFightCompatibility compatibility =
                new EndDragonFightCompatibility(() -> "26.1.2");

        assertTrue(compatibility.isSupported());
        assertDoesNotThrow(compatibility::requireSupported);
    }

    @Test
    void unknownMinecraftVersionIsRejectedWithClearMessage() {
        EndDragonFightCompatibility compatibility =
                new EndDragonFightCompatibility(() -> "26.2");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                compatibility::requireSupported
        );

        assertFalse(compatibility.isSupported());
        assertTrue(exception.getMessage().contains("26.2"));
        assertTrue(exception.getMessage().contains("26.1.2"));
    }
}
