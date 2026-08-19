package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResetWarningTrackerTest {

    private static final String FARMWORLD = "overworld";
    private static final Instant NEXT_RESET = Instant.parse("2026-09-01T12:00:00Z");
    private static final List<Duration> WARNINGS = List.of(
            Duration.ofHours(1),
            Duration.ofMinutes(30),
            Duration.ofMinutes(10),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1)
    );

    @Test
    void emitsNormalWarningSequenceExactlyOnce() {
        ResetWarningTracker tracker = initializedTracker(Duration.ofHours(2));

        assertDue(tracker, Duration.ofHours(1), Duration.ofHours(1));
        assertDue(tracker, Duration.ofMinutes(30), Duration.ofMinutes(30));
        assertDue(tracker, Duration.ofMinutes(10), Duration.ofMinutes(10));
        assertDue(tracker, Duration.ofMinutes(5), Duration.ofMinutes(5));
        assertDue(tracker, Duration.ofMinutes(1), Duration.ofMinutes(1));
        assertEquals(List.of(), evaluate(tracker, Duration.ofSeconds(30)));
    }

    @Test
    void acceptsLateSchedulerCheckWithoutExactDurationEquality() {
        ResetWarningTracker tracker = initializedTracker(Duration.ofHours(2));
        assertDue(tracker, Duration.ofHours(1), Duration.ofHours(1));
        assertDue(tracker, Duration.ofMinutes(30), Duration.ofMinutes(30));
        assertDue(tracker, Duration.ofMinutes(10), Duration.ofMinutes(10));

        assertDue(
                tracker,
                Duration.ofMinutes(5).minusSeconds(17),
                Duration.ofMinutes(5)
        );
    }

    @Test
    void doesNotEmitDuplicateWithinSameThreshold() {
        ResetWarningTracker tracker = initializedTracker(Duration.ofHours(2));
        assertDue(tracker, Duration.ofHours(1), Duration.ofHours(1));
        assertDue(tracker, Duration.ofMinutes(30), Duration.ofMinutes(30));
        assertDue(tracker, Duration.ofMinutes(10), Duration.ofMinutes(10));

        assertDue(tracker, Duration.ofMinutes(5), Duration.ofMinutes(5));
        assertEquals(List.of(), evaluate(tracker, Duration.ofMinutes(4)));
        assertEquals(List.of(), evaluate(tracker, Duration.ofMinutes(2)));
    }

    @Test
    void startsFreshCycleWhenNextResetChanges() {
        ResetWarningTracker tracker = initializedTracker(Duration.ofHours(2));
        assertDue(tracker, Duration.ofHours(1), Duration.ofHours(1));

        Instant newNextReset = NEXT_RESET.plus(Duration.ofDays(30));
        assertEquals(List.of(), tracker.evaluate(
                FARMWORLD,
                newNextReset,
                newNextReset.minus(Duration.ofHours(2)),
                WARNINGS
        ));
        assertEquals(List.of(Duration.ofHours(1)), tracker.evaluate(
                FARMWORLD,
                newNextReset,
                newNextReset.minus(Duration.ofHours(1)),
                WARNINGS
        ));
    }

    @Test
    void startupCatchUpEmitsOnlyClosestReachedThreshold() {
        ResetWarningTracker tracker = new ResetWarningTracker();

        assertEquals(
                List.of(Duration.ofMinutes(10)),
                evaluate(tracker, Duration.ofMinutes(7))
        );
        assertDue(tracker, Duration.ofMinutes(5), Duration.ofMinutes(5));
        assertDue(tracker, Duration.ofMinutes(1), Duration.ofMinutes(1));
        assertEquals(List.of(), evaluate(tracker, Duration.ofSeconds(30)));
    }

    @Test
    void startupShortlyBeforeResetStillEmitsAtMostOneCatchUp() {
        ResetWarningTracker tracker = new ResetWarningTracker();

        assertEquals(
                List.of(Duration.ofMinutes(1)),
                evaluate(tracker, Duration.ofSeconds(40))
        );
        assertEquals(List.of(), evaluate(tracker, Duration.ofSeconds(20)));
    }

    @Test
    void dueOrPastResetNeverEmitsCountdownWarning() {
        ResetWarningTracker tracker = new ResetWarningTracker();

        assertEquals(List.of(), tracker.evaluate(
                FARMWORLD,
                NEXT_RESET,
                NEXT_RESET,
                WARNINGS
        ));
        assertEquals(List.of(), tracker.evaluate(
                FARMWORLD,
                NEXT_RESET,
                NEXT_RESET.plusSeconds(1),
                WARNINGS
        ));
    }

    @Test
    void changedWarningConfigurationUsesOneCatchUpAndNewFutureThresholds() {
        ResetWarningTracker tracker = new ResetWarningTracker();
        assertEquals(
                List.of(Duration.ofMinutes(30)),
                evaluate(tracker, Duration.ofMinutes(20), List.of(
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(5)
                ))
        );

        assertEquals(
                List.of(Duration.ofMinutes(10)),
                evaluate(tracker, Duration.ofMinutes(7), List.of(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1)
                ))
        );
        assertEquals(
                List.of(Duration.ofMinutes(1)),
                evaluate(tracker, Duration.ofMinutes(1), List.of(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1)
                ))
        );
    }

    @Test
    void emptyWarningListDoesNothingAndResetsInitializationState() {
        ResetWarningTracker tracker = initializedTracker(Duration.ofHours(2));

        assertEquals(List.of(), evaluate(tracker, Duration.ofMinutes(4), List.of()));
        assertEquals(
                List.of(Duration.ofMinutes(5)),
                evaluate(tracker, Duration.ofMinutes(4), WARNINGS)
        );
    }

    private ResetWarningTracker initializedTracker(Duration initialRemaining) {
        ResetWarningTracker tracker = new ResetWarningTracker();
        assertEquals(List.of(), evaluate(tracker, initialRemaining));
        return tracker;
    }

    private void assertDue(
            ResetWarningTracker tracker,
            Duration remaining,
            Duration expectedWarning
    ) {
        assertEquals(List.of(expectedWarning), evaluate(tracker, remaining));
        assertEquals(List.of(), evaluate(tracker, remaining.minusSeconds(1)));
    }

    private List<Duration> evaluate(ResetWarningTracker tracker, Duration remaining) {
        return evaluate(tracker, remaining, WARNINGS);
    }

    private List<Duration> evaluate(
            ResetWarningTracker tracker,
            Duration remaining,
            List<Duration> warnings
    ) {
        return tracker.evaluate(
                FARMWORLD,
                NEXT_RESET,
                NEXT_RESET.minus(remaining),
                warnings
        );
    }
}
