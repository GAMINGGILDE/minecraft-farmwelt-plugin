package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ResetNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T08:00:00Z");
    private static final FarmworldResetState STORED_STATE = new FarmworldResetState(
            "overworld",
            Optional.of(Instant.parse("2026-08-01T08:00:00Z")),
            Instant.parse("2026-09-01T08:00:00Z")
    );

    @Test
    void exposesReloadedNotificationSnapshotWithoutChangingResetState() {
        FixedResetStateRepository repository = new FixedResetStateRepository(Map.of(
                "overworld", STORED_STATE
        ));
        FarmworldResetService resetService = new FarmworldResetService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                quietLogger()
        );
        ResetNotificationService notificationService = new ResetNotificationService(resetService);
        ResetNotificationConfig initialNotifications = notifications(false, "erste Nachricht");
        ResetNotificationConfig reloadedNotifications = notifications(true, "zweite Nachricht");

        assertTrue(resetService.reload(List.of(config(initialNotifications))));
        assertEquals(initialNotifications, notificationService.getConfig("overworld").orElseThrow());

        assertTrue(resetService.reload(List.of(config(reloadedNotifications))));
        assertEquals(reloadedNotifications, notificationService.getConfig("overworld").orElseThrow());
        assertEquals(STORED_STATE, resetService.getState("overworld").orElseThrow());
        assertEquals(0, repository.saveCount);
        assertTrue(notificationService.getConfig("unknown").isEmpty());
    }

    private FarmworldResetConfig config(ResetNotificationConfig notifications) {
        return new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                true,
                Duration.ofDays(30),
                FarmworldType.OVERWORLD,
                PostResetConfig.none(),
                notifications
        );
    }

    private ResetNotificationConfig notifications(boolean enabled, String warningMessage) {
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        return new ResetNotificationConfig(
                enabled,
                List.of(Duration.ofMinutes(5)),
                warningMessage,
                defaults.resetStart(),
                defaults.resetSuccess(),
                defaults.resetFailure(),
                defaults.evacuation()
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("ResetNotificationServiceTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static final class FixedResetStateRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states;
        private int saveCount;

        private FixedResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
            this.states = new LinkedHashMap<>(states);
            saveCount++;
        }
    }
}
