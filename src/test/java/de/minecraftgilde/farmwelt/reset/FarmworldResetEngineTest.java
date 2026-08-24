package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FarmworldResetEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-17T07:00:00Z");
    private static final long OLD_SEED = 123L;
    private static final long NEW_SEED = 456L;

    private final List<String> calls = new ArrayList<>();
    private final TestResetStateRepository repository = new TestResetStateRepository(calls);
    private final World originalWorld = world(
            "farmwelt", World.Environment.NORMAL, "old-farmwelt", OLD_SEED
    );
    private final World regeneratedWorld = world(
            "farmwelt", World.Environment.NORMAL, "new-farmwelt", NEW_SEED
    );
    private final FakeWorldOperations worldOperations = new FakeWorldOperations(
            calls,
            WorldInspection.loaded(originalWorld, FarmworldType.OVERWORLD, false),
            WorldInspection.loaded(regeneratedWorld, FarmworldType.OVERWORLD, false)
    );
    private final FakeLifecycleService lifecycleService = new FakeLifecycleService(
            calls,
            CompletableFuture.completedFuture(regeneratedWorld)
    );
    private final MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
    private final FarmworldResetService resetService = new FarmworldResetService(
            repository,
            clock,
            quietLogger()
    );
    private final List<String> lifecycleMessages = new ArrayList<>();
    private final List<PlayerMessage> evacuationMessages = new ArrayList<>();
    private final ResetNotificationService notificationService = new ResetNotificationService(
            resetService,
            new ResetWarningTracker(),
            lifecycleMessages::add,
            (player, message) -> {
                evacuationMessages.add(new PlayerMessage(player, message));
                return CompletableFuture.completedFuture(null);
            },
            ZoneOffset.UTC,
            quietLogger()
    );
    private final FakePostResetInitializer postResetInitializer = new FakePostResetInitializer(calls);
    private final FarmworldResetEngine engine = new FarmworldResetEngine(
            resetService,
            worldOperations,
            lifecycleService,
            postResetInitializer,
            new DirectScheduler(),
            notificationService,
            quietLogger()
    );

    @BeforeEach
    void configureReset() {
        clock.setInstant(NOW);
        resetService.reload(List.of(config()));
        postResetInitializer.result = CompletableFuture.completedFuture(PostResetResult.success());
        postResetInitializer.receivedConfig = null;
        postResetInitializer.receivedOptions = null;
        lifecycleService.receivedOptions = null;
        calls.clear();
        lifecycleMessages.clear();
        evacuationMessages.clear();
    }

    @Test
    void executesWorldsHappyPathInOrderAndPersistsSchedule() {
        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "inspect",
                "evacuate",
                "hasPlayers",
                "regenerate",
                "inspectRegenerated",
                "postReset",
                "state"
        ), calls);
        assertSame(originalWorld, lifecycleService.receivedWorld);
        FarmworldResetState state = resetService.getState("overworld").orElseThrow();
        assertEquals(NOW, state.lastReset().orElseThrow());
        assertEquals(NOW.plus(Duration.ofDays(30)), state.nextReset());
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), lifecycleMessages);
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void cleanupExceptionDoesNotChangeSuccessOrLeaveLockBehind() {
        postResetInitializer.closeFailure = new IllegalStateException("cleanup failed");

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertTrue(postResetInitializer.resetScopeClosed);
        assertFalse(engine.isResetRunning("overworld"));
        assertEquals(NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset());
    }

    @Test
    void synchronousScopeSetupExceptionReleasesLockWithoutStartingPipeline() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        IllegalStateException failure = new IllegalStateException("scope failed");
        postResetInitializer.beginFailure = failure;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.INTERNAL_ERROR, result.status());
        assertSame(failure, result.cause());
        assertEquals(List.of(), calls);
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void successfulResetUsesActualCompletionInstantForSchedule() {
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;
        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");
        Instant completedAt = NOW.plus(Duration.ofMinutes(2)).plusSeconds(30);

        clock.setInstant(completedAt);
        pendingRegeneration.complete(regeneratedWorld);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        FarmworldResetState state = resetService.getState("overworld").orElseThrow();
        assertEquals(completedAt, state.lastReset().orElseThrow());
        assertEquals(completedAt.plus(Duration.ofDays(30)), state.nextReset());
    }

    @Test
    void successfulResetLogsOldAndNewSeed() {
        List<LogRecord> logRecords = new ArrayList<>();
        FarmworldResetEngine loggingEngine = engineWith(recordingLogger(logRecords));

        ResetResult result = loggingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getMessage().equals("Alter Seed: " + OLD_SEED)
        ));
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getMessage().equals("Neuer Seed: " + NEW_SEED)
        ));
    }

    @Test
    void identicalRandomSeedWarnsButResetStillSucceeds() {
        World sameSeedWorld = world(
                "farmwelt", World.Environment.NORMAL, "same-seed-farmwelt", OLD_SEED
        );
        lifecycleService.result = CompletableFuture.completedFuture(sameSeedWorld);
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                sameSeedWorld,
                FarmworldType.OVERWORLD,
                false
        );
        List<LogRecord> logRecords = new ArrayList<>();
        FarmworldResetEngine loggingEngine = engineWith(recordingLogger(logRecords));

        ResetResult result = loggingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(1, lifecycleService.invocations);
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getLevel() == Level.WARNING
                        && record.getMessage().equals(
                                "Der zufällig erzeugte Seed entspricht dem vorherigen Seed."
                        )
        ));
    }

    @Test
    void evacuationFailureNeverInvokesWorldsAndReleasesLock() {
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.failed(List.of(), null)
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
        assertEquals(List.of(), evacuationMessages);
    }

    @Test
    void nullEvacuationFutureIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        worldOperations.evacuationResult = null;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void sendsOnePersonalMessageToOneSuccessfullyEvacuatedPlayer() {
        Player player = player("Alex");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(player))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(new PlayerMessage(
                player,
                "&eDu wurdest aus der &6Farmwelt&e teleportiert, da sie gerade zurückgesetzt wird."
        )), evacuationMessages);
    }

    @Test
    void sendsEachEvacuatedPlayerExactlyOnceAndNoMessageToOtherPlayers() {
        Player first = player("Alex");
        Player second = player("Steve");
        Player third = player("Sam");
        Player otherWorld = player("Robin");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(first, second, third, first))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(first, second, third), evacuationMessages.stream()
                .map(PlayerMessage::player)
                .toList());
        assertFalse(evacuationMessages.stream().anyMatch(message ->
                message.player() == otherWorld
        ));
    }

    @Test
    void notifiesSuccessfulPlayersEvenWhenAnotherEvacuationFails() {
        Player evacuated = player("Alex");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.failed(List.of(evacuated), null)
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(List.of(evacuated), evacuationMessages.stream()
                .map(PlayerMessage::player)
                .toList());
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void evacuationMessageRemainsCorrectWhenRegenerationLaterFails() {
        Player evacuated = player("Alex");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(evacuated))
        );
        lifecycleService.result = CompletableFuture.failedFuture(
                new IllegalStateException("regenerate failed")
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertEquals(List.of(evacuated), evacuationMessages.stream()
                .map(PlayerMessage::player)
                .toList());
    }

    @Test
    void evacuationMessageSwitchDoesNotChangeEvacuationOrReset() {
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        resetService.reload(List.of(config(new ResetNotificationConfig(
                true,
                defaults.warnings(),
                defaults.warningMessage(),
                defaults.resetStart(),
                defaults.resetSuccess(),
                defaults.resetFailure(),
                new ResetNotificationMessageConfig(false, "nicht senden")
        ))));
        Player evacuated = player("Alex");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(evacuated))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(), evacuationMessages);
        assertTrue(calls.contains("evacuate"));
    }

    @Test
    void globallyDisabledNotificationsSuppressPersonalMessageWithoutChangingReset() {
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        resetService.reload(List.of(config(new ResetNotificationConfig(
                false,
                defaults.warnings(),
                defaults.warningMessage(),
                defaults.resetStart(),
                defaults.resetSuccess(),
                defaults.resetFailure(),
                defaults.evacuation()
        ))));
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(player("Alex")))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(), evacuationMessages);
        assertTrue(calls.contains("evacuate"));
    }

    @Test
    void playerAudienceExceptionIsLoggedAndDoesNotChangeSuccessfulReset() {
        List<LogRecord> logRecords = new ArrayList<>();
        Logger logger = recordingLogger(logRecords);
        ResetNotificationService failingNotifications = new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                lifecycleMessages::add,
                (player, message) -> {
                    throw new IllegalStateException("send failed");
                },
                ZoneOffset.UTC,
                logger
        );
        FarmworldResetEngine notificationFailingEngine = new FarmworldResetEngine(
                resetService,
                worldOperations,
                lifecycleService,
                postResetInitializer,
                new DirectScheduler(),
                failingNotifications,
                logger
        );
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(player("Alex")))
        );

        ResetResult result = notificationFailingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertTrue(logRecords.stream().anyMatch(record ->
                record.getLevel() == Level.SEVERE
                        && record.getMessage().contains("Evakuierungsnachricht")
        ));
    }

    @Test
    void alreadyRunningCallDoesNotRepeatStartOrEvacuationMessage() {
        Player evacuated = player("Alex");
        worldOperations.evacuationResult = CompletableFuture.completedFuture(
                FarmworldEvacuationResult.completed(List.of(evacuated))
        );
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;

        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());

        assertEquals(1, lifecycleMessages.size());
        assertEquals(1, evacuationMessages.size());
        pendingRegeneration.complete(regeneratedWorld);
        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertEquals(1, evacuationMessages.size());
    }

    @Test
    void playersRemainingAfterEvacuationNeverInvokesWorlds() {
        worldOperations.hasPlayers = true;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.EVACUATION_FAILED, result.status());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
    }

    @Test
    void regenerationFailureDoesNotUpdateStateAndReleasesLock() {
        IllegalStateException worldsFailure = new IllegalStateException("regenerate failed");
        lifecycleService.result = CompletableFuture.failedFuture(worldsFailure);
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertSame(worldsFailure, result.cause());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void synchronousWorldsExceptionIsMappedAndReleasesLock() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        IllegalStateException worldsFailure = new IllegalStateException("synchronous failure");
        lifecycleService.synchronousFailure = worldsFailure;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertSame(worldsFailure, result.cause());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void nullWorldsFutureIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        lifecycleService.result = null;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void nullWorldsResultIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        lifecycleService.result = CompletableFuture.completedFuture(null);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void regeneratedWorldWithWrongNameIsRejected() {
        World wrongWorld = world("wrong_name", World.Environment.NORMAL, "wrong-name");
        lifecycleService.result = CompletableFuture.completedFuture(wrongWorld);
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                wrongWorld,
                FarmworldType.OVERWORLD,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void regeneratedWorldWithWrongDimensionIsRejected() {
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedWorld,
                FarmworldType.NETHER,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void regeneratedWorldMustStillBeLoadedAndUnprotected() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        worldOperations.regeneratedInspection = WorldInspection.unloaded();

        ResetResult unloadedResult = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, unloadedResult.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));

        calls.clear();
        worldOperations.inspectCount = 0;
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedWorld,
                FarmworldType.OVERWORLD,
                true
        );

        ResetResult protectedResult = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, protectedResult.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void regeneratedWorldMustBeTheInstanceReachableThroughBukkit() {
        World otherBukkitWorld = world("farmwelt", World.Environment.NORMAL, "other-farmwelt");
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                otherBukkitWorld,
                FarmworldType.OVERWORLD,
                false
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("state"));
    }

    @Test
    void staleWorldInstanceIsRejected() {
        lifecycleService.result = CompletableFuture.completedFuture(originalWorld);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.REGENERATE_FAILED, result.status());
        assertFalse(calls.contains("inspectRegenerated"));
        assertFalse(calls.contains("state"));
    }

    @Test
    void stateFailureIsNotSuccessAndDoesNotRegenerateTwice() {
        repository.failSaves = true;
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.STATE_SAVE_FAILED, result.status());
        assertEquals(1, lifecycleService.invocations);
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertEquals(
                List.of("&eDie &6Farmwelt&e wird jetzt zurückgesetzt."),
                lifecycleMessages
        );
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void nullStatePersistenceResultIsNotReportedAsSuccess() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        FarmweltScheduler nullResultScheduler = new DirectScheduler() {
            @Override
            public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
                return CompletableFuture.completedFuture(null);
            }
        };
        FarmworldResetEngine nullStateEngine = new FarmworldResetEngine(
                resetService,
                worldOperations,
                lifecycleService,
                postResetInitializer,
                nullResultScheduler,
                notificationService,
                quietLogger()
        );

        ResetResult result = nullStateEngine.reset("overworld").join();

        assertEquals(ResetStatus.STATE_SAVE_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(nullStateEngine.isResetRunning("overworld"));
    }

    @Test
    void stateSaveFailureBroadcastsEnabledFailureButNeverSuccess() {
        resetService.reload(List.of(config(lifecycleNotifications(
                true,
                true,
                true,
                true,
                "start {world}",
                "success {world}",
                "failure {world} {next-reset}"
        ))));
        lifecycleMessages.clear();
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        repository.failSaves = true;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.STATE_SAVE_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertEquals(List.of(
                "start Farmwelt",
                "failure Farmwelt 16.09.2026 07:00"
        ), lifecycleMessages);
    }

    @Test
    void successfulLifecycleMessageUsesNewlyPersistedNextReset() {
        resetService.reload(List.of(config(lifecycleNotifications(
                true,
                true,
                true,
                true,
                "start {world} {next-reset}",
                "success {world} {next-reset}",
                "failure {world}"
        ))));
        lifecycleMessages.clear();
        clock.setInstant(NOW.plus(Duration.ofMinutes(2)));

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "start Farmwelt 16.09.2026 07:00",
                "success Farmwelt 16.09.2026 07:02"
        ), lifecycleMessages);
    }

    @Test
    void audienceExceptionsDoNotChangeSuccessfulResetResult() {
        ResetNotificationService failingNotifications = new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                ignored -> {
                    throw new IllegalStateException("broadcast failed");
                },
                ResetPlayerNotificationAudience.noop(),
                ZoneOffset.UTC,
                quietLogger()
        );
        FarmworldResetEngine notificationFailingEngine = new FarmworldResetEngine(
                resetService,
                worldOperations,
                lifecycleService,
                postResetInitializer,
                new DirectScheduler(),
                failingNotifications,
                quietLogger()
        );

        ResetResult result = notificationFailingEngine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset());
    }

    @Test
    void globallyDisabledNotificationsDoNotAffectSuccessfulReset() {
        resetService.reload(List.of(config(lifecycleNotifications(
                false,
                true,
                true,
                true,
                "start {world}",
                "success {world}",
                "failure {world}"
        ))));
        lifecycleMessages.clear();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(), lifecycleMessages);
        assertEquals(NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset());
    }

    @Test
    void postResetFailureDoesNotCompleteStateAndReleasesLock() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        postResetInitializer.result = CompletableFuture.completedFuture(
                PostResetResult.failure("post reset failed", new IllegalStateException("broken"))
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.POST_RESET_FAILED, result.status());
        assertEquals(List.of(
                "inspect",
                "evacuate",
                "hasPlayers",
                "regenerate",
                "inspectRegenerated",
                "postReset"
        ), calls);
        assertFalse(calls.contains("state"));
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(engine.isResetRunning("overworld"));
        assertTrue(postResetInitializer.resetScopeClosed);
    }

    @Test
    void nullPostResetFutureIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        postResetInitializer.result = null;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.POST_RESET_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void synchronousPostResetExceptionIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        IllegalStateException failure = new IllegalStateException("post reset failed");
        postResetInitializer.applyFailure = failure;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.POST_RESET_FAILED, result.status());
        assertSame(failure, result.cause());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void nullPostResetResultIsMappedAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        postResetInitializer.result = CompletableFuture.completedFuture(null);

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.POST_RESET_FAILED, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void nonEndResetPreservesDragonFightDataRegardlessOfOverrideOption() {
        ResetOptions options = ResetOptions.allowingEnderDragon();

        ResetResult result = engine.reset("overworld", options).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertSame(options, postResetInitializer.receivedOptions);
        assertEquals(1, lifecycleService.invocations);
        assertEquals(
                FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE,
                lifecycleService.receivedOptions.endDragonFightDataMode()
        );
    }

    @Test
    void dragonDisabledEndResetRequestsFightDataResetFromLifecycle() {
        World originalEnd = world("endfarm", World.Environment.THE_END, "old-endfarm", OLD_SEED);
        World regeneratedEnd = world(
                "endfarm", World.Environment.THE_END, "new-endfarm", NEW_SEED
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "end",
                "endfarm",
                true,
                Duration.ofDays(60),
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.of(new EndPostResetConfig(false))
                )
        )));
        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalEnd,
                FarmworldType.END,
                false
        );
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedEnd,
                FarmworldType.END,
                false
        );
        lifecycleService.result = CompletableFuture.completedFuture(regeneratedEnd);

        ResetResult result = engine.reset("end").join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(
                FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED,
                lifecycleService.receivedOptions.endDragonFightDataMode()
        );
    }

    @Test
    void dragonOverrideRequestsFreshInitialFightDataForEndWorld() {
        World originalEnd = world("endfarm", World.Environment.THE_END, "old-endfarm", OLD_SEED);
        World regeneratedEnd = world(
                "endfarm", World.Environment.THE_END, "new-endfarm", NEW_SEED
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "end",
                "endfarm",
                true,
                Duration.ofDays(60),
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.of(new EndPostResetConfig(false))
                )
        )));
        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalEnd,
                FarmworldType.END,
                false
        );
        worldOperations.regeneratedInspection = WorldInspection.loaded(
                regeneratedEnd,
                FarmworldType.END,
                false
        );
        lifecycleService.result = CompletableFuture.completedFuture(regeneratedEnd);

        ResetResult result = engine.reset("end", ResetOptions.allowingEnderDragon()).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(
                FarmworldRegenerationOptions.EndDragonFightDataMode.INITIAL_FIGHT,
                lifecycleService.receivedOptions.endDragonFightDataMode()
        );
        assertEquals(List.of(
                "&eDie &6Endfarm&e wird jetzt zurückgesetzt.",
                "&aDie &6Endfarm&a wurde erfolgreich zurückgesetzt."
        ), lifecycleMessages);
    }

    @Test
    void concurrentResetLocksTeleportUntilWorldsFutureCompletes() {
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;

        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");

        assertTrue(engine.isResetRunning("overworld"));
        assertFalse(engine.isFarmworldAvailable("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());
        assertEquals(List.of("&eDie &6Farmwelt&e wird jetzt zurückgesetzt."), lifecycleMessages);

        pendingRegeneration.complete(regeneratedWorld);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), lifecycleMessages);
        assertFalse(engine.isResetRunning("overworld"));
        assertTrue(engine.isFarmworldAvailable("overworld"));
    }

    @Test
    void missingAndDisabledConfigurationsReturnExplicitStatuses() {
        assertEquals(ResetStatus.NOT_CONFIGURED, engine.reset("missing").join().status());

        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                false,
                Duration.ofDays(30)
        )));

        assertEquals(ResetStatus.DISABLED, engine.reset("overworld").join().status());
        assertEquals(List.of(), lifecycleMessages);
    }

    @Test
    void unloadedWorldIsRejectedBeforeEvacuation() {
        worldOperations.initialInspection = WorldInspection.unloaded();

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.WORLD_NOT_LOADED, result.status());
        assertEquals(List.of("inspect"), calls);
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void nullWorldInspectionIsRejectedBeforeEvacuationAndDoesNotPersistState() {
        FarmworldResetState previousState = resetService.getState("overworld").orElseThrow();
        worldOperations.initialInspection = null;

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.INVALID_CONFIGURATION, result.status());
        assertEquals(previousState, resetService.getState("overworld").orElseThrow());
        assertEquals(0, lifecycleService.invocations);
        assertFalse(calls.contains("state"));
        assertFalse(engine.isResetRunning("overworld"));
    }

    @Test
    void protectedMainWorldIsRejectedBeforeEvacuation() {
        worldOperations.initialInspection = WorldInspection.loaded(
                originalWorld,
                FarmworldType.OVERWORLD,
                true
        );

        ResetResult result = engine.reset("overworld").join();

        assertEquals(ResetStatus.PROTECTED_WORLD, result.status());
        assertEquals(List.of("inspect"), calls);
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void initialWorldNameAndDimensionMustMatchConfiguration() {
        World wrongWorld = world("wrong_name", World.Environment.NORMAL, "wrong-name");
        worldOperations.initialInspection = WorldInspection.loaded(
                wrongWorld,
                FarmworldType.OVERWORLD,
                false
        );
        assertEquals(ResetStatus.INVALID_CONFIGURATION, engine.reset("overworld").join().status());

        worldOperations.inspectCount = 0;
        worldOperations.initialInspection = WorldInspection.loaded(
                originalWorld,
                FarmworldType.NETHER,
                false
        );
        assertEquals(ResetStatus.INVALID_CONFIGURATION, engine.reset("overworld").join().status());
        assertEquals(0, lifecycleService.invocations);
    }

    @Test
    void reloadDuringWorldsResetKeepsLockAndOriginalIntervalSnapshot() {
        PostResetConfig originalPostReset = new PostResetConfig(
                Map.of("show_advancement_messages", false),
                java.util.Optional.empty(),
                java.util.Optional.empty()
        );
        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "farmwelt",
                true,
                Duration.ofDays(30),
                FarmworldType.OVERWORLD,
                originalPostReset
        )));
        CompletableFuture<World> pendingRegeneration = new CompletableFuture<>();
        lifecycleService.result = pendingRegeneration;
        CompletableFuture<ResetResult> runningReset = engine.reset("overworld");

        resetService.reload(List.of(new FarmworldResetConfig(
                "overworld",
                "replacement_farmwelt",
                true,
                Duration.ofDays(60)
        )));

        assertTrue(engine.isResetRunning("overworld"));
        assertEquals(ResetStatus.ALREADY_RUNNING, engine.reset("overworld").join().status());
        pendingRegeneration.complete(regeneratedWorld);

        assertEquals(ResetStatus.SUCCESS, runningReset.join().status());
        assertEquals("replacement_farmwelt", resetService.getConfig("overworld").orElseThrow().worldName());
        assertEquals(Duration.ofDays(60), resetService.getConfig("overworld").orElseThrow().interval());
        assertEquals("farmwelt", postResetInitializer.receivedConfig.worldName());
        assertSame(originalPostReset, postResetInitializer.receivedConfig.postReset());
        assertEquals(
                NOW.plus(Duration.ofDays(30)),
                resetService.getState("overworld").orElseThrow().nextReset()
        );
    }

    private FarmworldResetConfig config() {
        return new FarmworldResetConfig("overworld", "farmwelt", true, Duration.ofDays(30));
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

    private ResetNotificationConfig lifecycleNotifications(
            boolean enabled,
            boolean resetStart,
            boolean resetSuccess,
            boolean resetFailure,
            String startMessage,
            String successMessage,
            String failureMessage
    ) {
        return new ResetNotificationConfig(
                enabled,
                List.of(),
                "warning",
                new ResetNotificationMessageConfig(resetStart, startMessage),
                new ResetNotificationMessageConfig(resetSuccess, successMessage),
                new ResetNotificationMessageConfig(resetFailure, failureMessage),
                ResetNotificationConfig.defaults().evacuation()
        );
    }

    private FarmworldResetEngine engineWith(Logger logger) {
        return new FarmworldResetEngine(
                resetService,
                worldOperations,
                lifecycleService,
                postResetInitializer,
                new DirectScheduler(),
                notificationService,
                logger
        );
    }

    private static World world(String name, World.Environment environment, String directory) {
        return world(name, environment, directory, 0L);
    }

    private static Player player(String name) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "toString" -> "FakePlayer[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static World world(
            String name,
            World.Environment environment,
            String directory,
            long seed
    ) {
        File worldFolder = Path.of("server", "world", "dimensions", "worlds", directory)
                .toAbsolutePath()
                .toFile();
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getEnvironment" -> environment;
                    case "getWorldFolder" -> worldFolder;
                    case "getSeed" -> seed;
                    case "toString" -> "FakeWorld[" + name + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("FarmworldResetEngineTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Logger recordingLogger(List<LogRecord> logRecords) {
        Logger logger = Logger.getLogger("FarmworldResetEngineTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    private static class DirectScheduler implements FarmweltScheduler {

        @Override
        public <T> CompletableFuture<T> runGlobal(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runGlobalDelayed(
                long ticks,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runRegion(
                World world,
                int chunkX,
                int chunkZ,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runRegionDelayed(
                World world,
                int chunkX,
                int chunkZ,
                long ticks,
                CheckedSupplier<T> operation
        ) {
            return execute(operation);
        }

        @Override
        public <T> CompletableFuture<T> runAsync(CheckedSupplier<T> operation) {
            return execute(operation);
        }

        private <T> CompletableFuture<T> execute(CheckedSupplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }

    private static final class FakeWorldOperations implements FarmworldWorldOperations {

        private final List<String> calls;
        private WorldInspection initialInspection;
        private WorldInspection regeneratedInspection;
        private CompletableFuture<FarmworldEvacuationResult> evacuationResult =
                CompletableFuture.completedFuture(
                        FarmworldEvacuationResult.completed(List.of())
                );
        private boolean hasPlayers;
        private int inspectCount;

        private FakeWorldOperations(
                List<String> calls,
                WorldInspection initialInspection,
                WorldInspection regeneratedInspection
        ) {
            this.calls = calls;
            this.initialInspection = initialInspection;
            this.regeneratedInspection = regeneratedInspection;
        }

        @Override
        public WorldInspection inspect(FarmworldResetConfig resetConfig) {
            calls.add(inspectCount++ == 0 ? "inspect" : "inspectRegenerated");
            return inspectCount == 1 ? initialInspection : regeneratedInspection;
        }

        @Override
        public CompletableFuture<FarmworldEvacuationResult> evacuatePlayers(World resetWorld) {
            calls.add("evacuate");
            return evacuationResult;
        }

        @Override
        public boolean hasPlayers(World world) {
            calls.add("hasPlayers");
            return hasPlayers;
        }
    }

    private record PlayerMessage(Player player, String message) {
    }

    private static final class FakeLifecycleService implements FarmworldLifecycleService {

        private final List<String> calls;
        private CompletableFuture<World> result;
        private World receivedWorld;
        private FarmworldRegenerationOptions receivedOptions;
        private int invocations;
        private RuntimeException synchronousFailure;

        private FakeLifecycleService(List<String> calls, CompletableFuture<World> result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public CompletableFuture<World> regenerate(
                World world,
                FarmworldRegenerationOptions options
        ) {
            calls.add("regenerate");
            receivedWorld = world;
            receivedOptions = options;
            invocations++;
            if (synchronousFailure != null) {
                throw synchronousFailure;
            }
            return result;
        }
    }

    private static final class FakePostResetInitializer implements FarmworldPostResetInitializer {

        private final List<String> calls;
        private CompletableFuture<PostResetResult> result =
                CompletableFuture.completedFuture(PostResetResult.success());
        private FarmworldResetConfig receivedConfig;
        private ResetOptions receivedOptions;
        private boolean resetScopeClosed;
        private RuntimeException beginFailure;
        private RuntimeException closeFailure;
        private RuntimeException applyFailure;

        private FakePostResetInitializer(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public ResetScope beginReset(FarmworldResetConfig config, ResetOptions options) {
            if (beginFailure != null) {
                throw beginFailure;
            }
            resetScopeClosed = false;
            return () -> {
                resetScopeClosed = true;
                if (closeFailure != null) {
                    throw closeFailure;
                }
            };
        }

        @Override
        public CompletableFuture<PostResetResult> apply(
                FarmworldResetConfig config,
                World regeneratedWorld,
                ResetOptions options
        ) {
            calls.add("postReset");
            receivedConfig = config;
            receivedOptions = options;
            if (applyFailure != null) {
                throw applyFailure;
            }
            return result;
        }
    }

    private static final class TestResetStateRepository implements ResetStateRepository {

        private final List<String> calls;
        private Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        private boolean failSaves;

        private TestResetStateRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
            calls.add("state");
            if (failSaves) {
                throw new IOException("state failed");
            }
            this.states = new LinkedHashMap<>(states);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
