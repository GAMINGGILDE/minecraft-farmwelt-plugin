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
    private static final Instant NEXT_RESET = Instant.parse("2026-09-01T12:00:00Z");
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
        ResetNotificationService notificationService = new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                ignored -> { },
                ZoneOffset.UTC,
                quietLogger()
        );
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

    @Test
    void broadcastsLateWarningWithConfiguredThresholdAndPlaceholders() {
        FarmworldResetState state = state(NEXT_RESET);
        FixedResetStateRepository repository = repository(state);
        FarmworldResetService resetService = resetService(repository);
        List<String> messages = new java.util.ArrayList<>();
        ResetNotificationService notificationService = notificationService(
                resetService,
                messages::add
        );
        ResetNotificationConfig notifications = notifications(
                true,
                List.of(Duration.ofMinutes(10), Duration.ofMinutes(5)),
                "&e{world}: {time} / {next-reset}"
        );
        assertTrue(resetService.reload(List.of(config(true, notifications))));

        notificationService.broadcastDueWarnings(
                NEXT_RESET.minus(Duration.ofMinutes(5)).plusSeconds(17)
        );

        assertEquals(
                List.of("&eTest-Farmwelt: 5 Minuten / 01.09.2026 12:00"),
                messages
        );
        assertEquals(state, resetService.getState("overworld").orElseThrow());
        assertEquals(0, repository.saveCount);
    }

    @Test
    void disabledNotificationsAndEmptyWarningsDoNotBroadcast() {
        FixedResetStateRepository repository = repository(state(NEXT_RESET));
        FarmworldResetService resetService = resetService(repository);
        List<String> messages = new java.util.ArrayList<>();
        ResetNotificationService notificationService = notificationService(
                resetService,
                messages::add
        );

        assertTrue(resetService.reload(List.of(config(true, notifications(
                false,
                List.of(Duration.ofMinutes(5)),
                "{time}"
        )))));
        notificationService.broadcastDueWarnings(NEXT_RESET.minusSeconds(30));

        assertTrue(resetService.reload(List.of(config(true, notifications(
                true,
                List.of(),
                "{time}"
        )))));
        notificationService.reload();
        notificationService.broadcastDueWarnings(NEXT_RESET.minusSeconds(30));

        assertEquals(List.of(), messages);
        assertEquals(0, repository.saveCount);
    }

    @Test
    void reloadUsesChangedWarningsWithoutSendingOldSeries() {
        FixedResetStateRepository repository = repository(state(NEXT_RESET));
        FarmworldResetService resetService = resetService(repository);
        List<String> messages = new java.util.ArrayList<>();
        ResetNotificationService notificationService = notificationService(
                resetService,
                messages::add
        );

        assertTrue(resetService.reload(List.of(config(true, notifications(
                true,
                List.of(Duration.ofMinutes(30), Duration.ofMinutes(5)),
                "{time}"
        )))));
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(20)));

        assertTrue(resetService.reload(List.of(config(true, notifications(
                true,
                List.of(Duration.ofMinutes(10), Duration.ofMinutes(1)),
                "{time}"
        )))));
        notificationService.reload();
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(7)));
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(1)));

        assertEquals(List.of("30 Minuten", "10 Minuten", "1 Minute"), messages);
        assertEquals(0, repository.saveCount);
    }

    @Test
    void disablingAndReenablingStartsSingleFreshCatchUp() {
        FixedResetStateRepository repository = repository(state(NEXT_RESET));
        FarmworldResetService resetService = resetService(repository);
        List<String> messages = new java.util.ArrayList<>();
        ResetNotificationService notificationService = notificationService(
                resetService,
                messages::add
        );
        List<Duration> warnings = List.of(
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );

        assertTrue(resetService.reload(List.of(config(true, notifications(
                true,
                warnings,
                "{time}"
        )))));
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(20)));

        assertTrue(resetService.reload(List.of(config(true, notifications(
                false,
                warnings,
                "{time}"
        )))));
        notificationService.reload();
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(7)));

        assertTrue(resetService.reload(List.of(config(true, notifications(
                true,
                warnings,
                "{time}"
        )))));
        notificationService.reload();
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(7)));
        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(5)));

        assertEquals(List.of("30 Minuten", "10 Minuten", "5 Minuten"), messages);
        assertEquals(0, repository.saveCount);
    }

    @Test
    void disabledResetScheduleDoesNotBroadcast() {
        FixedResetStateRepository repository = repository(state(NEXT_RESET));
        FarmworldResetService resetService = resetService(repository);
        List<String> messages = new java.util.ArrayList<>();
        ResetNotificationService notificationService = notificationService(
                resetService,
                messages::add
        );
        assertTrue(resetService.reload(List.of(config(false, notifications(
                true,
                List.of(Duration.ofMinutes(5)),
                "{time}"
        )))));

        notificationService.broadcastDueWarnings(NEXT_RESET.minus(Duration.ofMinutes(5)));

        assertEquals(List.of(), messages);
        assertEquals(0, repository.saveCount);
    }

    private FarmworldResetConfig config(ResetNotificationConfig notifications) {
        return config(true, notifications);
    }

    private FarmworldResetConfig config(
            boolean enabled,
            ResetNotificationConfig notifications
    ) {
        return new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                "Test-Farmwelt",
                enabled,
                Duration.ofDays(30),
                FarmworldType.OVERWORLD,
                PostResetConfig.none(),
                notifications
        );
    }

    private ResetNotificationConfig notifications(boolean enabled, String warningMessage) {
        return notifications(
                enabled,
                List.of(Duration.ofMinutes(5)),
                warningMessage
        );
    }

    private ResetNotificationConfig notifications(
            boolean enabled,
            List<Duration> warnings,
            String warningMessage
    ) {
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        return new ResetNotificationConfig(
                enabled,
                warnings,
                warningMessage,
                defaults.resetStart(),
                defaults.resetSuccess(),
                defaults.resetFailure(),
                defaults.evacuation()
        );
    }

    private FarmworldResetService resetService(FixedResetStateRepository repository) {
        return new FarmworldResetService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                quietLogger()
        );
    }

    private ResetNotificationService notificationService(
            FarmworldResetService resetService,
            ResetNotificationAudience audience
    ) {
        return new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                audience,
                ZoneOffset.UTC,
                quietLogger()
        );
    }

    private FixedResetStateRepository repository(FarmworldResetState state) {
        return new FixedResetStateRepository(Map.of(state.farmworldKey(), state));
    }

    private FarmworldResetState state(Instant nextReset) {
        return new FarmworldResetState("overworld", Optional.empty(), nextReset);
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
