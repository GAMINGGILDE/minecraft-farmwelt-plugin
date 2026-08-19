package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResetNotificationConfigTest {

    @Test
    void warningListIsNormalizedAndDefensivelyCopied() {
        List<Duration> warnings = new ArrayList<>(List.of(
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                Duration.ofMinutes(5)
        ));
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();

        ResetNotificationConfig config = new ResetNotificationConfig(
                true,
                warnings,
                defaults.warningMessage(),
                defaults.resetStart(),
                defaults.resetSuccess(),
                defaults.resetFailure(),
                defaults.evacuation()
        );
        warnings.clear();

        assertEquals(
                List.of(Duration.ofHours(1), Duration.ofMinutes(5)),
                config.warnings()
        );
        assertThrows(UnsupportedOperationException.class, () -> config.warnings().clear());
    }
}
