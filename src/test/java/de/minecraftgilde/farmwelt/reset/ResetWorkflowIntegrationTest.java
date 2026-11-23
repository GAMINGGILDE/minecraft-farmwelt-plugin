package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.thenextlvl.worlds.WorldOperationException;
import net.thenextlvl.worlds.WorldsAccess;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.event.world.WorldUnloadEvent;
import net.thenextlvl.worlds.event.WorldRegenerateEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResetWorkflowIntegrationTest {

    @TempDir
    Path dataDirectory;

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    @Test
    void concurrentCallersCanAcquireOnlyOneNetherReservation() throws Exception {
        diskWithDueStates("nether");
        var calls = new AtomicInteger();
        var harness = diskHarness(List.of(config("nether", THIRTY_DAYS)), calls);
        var pending = harness.runtime.defer("nether");
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var manual = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return harness.engine.reset("Nether");
            });
            var scheduled = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return harness.engine.resetIfDue("nether", NOW);
            });
            var a = manual.get(5, TimeUnit.SECONDS);
            var b = scheduled.get(5, TimeUnit.SECONDS);
            assertTrue(a.isDone() ^ b.isDone());
            assertEquals(ResetStatus.ALREADY_RUNNING, (a.isDone() ? a : b).join().status());
            assertEquals(1, calls.get());
            harness.runtime.complete("nether", pending);
            assertEquals(Set.of(ResetStatus.SUCCESS, ResetStatus.ALREADY_RUNNING),
                    Set.of(a.join().status(), b.join().status()));
            assertEquals(1, harness.runtime.evacuations);
            assertEquals(1, harness.runtime.closedScopes);
        }
    }

    @Test
    void ambiguousReceiptCannotBootstrapMissingRecoveryFiles() throws IOException {
        diskWithDueStates("overworld", "nether");
        Path schedule = dataDirectory.resolve("reset-state.yml");
        String original = Files.readString(schedule) + """
                  OVERWORLD:
                    next-reset: '2026-12-15T12:00:00Z'
                    attempt-id: '213172cd-e944-47a7-9d43-472748efac6b'
                """;
        Files.writeString(schedule, original);
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var restart = diskHarness(configs, calls);
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("Nether").join().status());
        assertFalse(Files.exists(dataDirectory.resolve("reset-recovery.version")));
        assertEquals(original, Files.readString(schedule));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"overworld", "OVERWORLD", "OverWorld"})
    void legacyReceiptSurvivesFailedFirstWriteAndRestart(String spelling) throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        assertEquals(ResetStatus.SUCCESS, first.engine.reset("overworld").join().status());
        var receipt = disk.load().get("overworld");
        Path schedule = dataDirectory.resolve("reset-state.yml");
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String legacySchedule = Files.readString(schedule).replace("overworld:", spelling + ":");
        String legacyJournal = Files.readString(journal).replace("key=overworld", "key=OverWorld");
        Files.writeString(schedule, legacySchedule);
        Files.writeString(journal, legacyJournal);

        var restart = diskHarness(List.of(config), calls);
        assertEquals(receipt, restart.resetService.getState("OVERWORLD").orElseThrow());
        assertTrue(restart.resetService.recoveryProblem(config).isEmpty());
        assertEquals(legacySchedule, Files.readString(schedule));
        var withNether = List.of(config, config("Nether", THIRTY_DAYS));
        Path obstruction = Files.createDirectory(dataDirectory.resolve("reset-state.yml.tmp"));
        Files.writeString(obstruction.resolve("fault"), "Schreibfehler");
        assertFalse(restart.resetService.reload(withNether));
        assertTrue(restart.resetService.getConfig("nether").isEmpty());
        assertEquals(legacySchedule, Files.readString(schedule));
        Files.delete(obstruction.resolve("fault"));
        Files.delete(obstruction);

        var migrated = diskHarness(withNether, calls);
        assertEquals(receipt, disk.load().get("overworld"));
        assertEquals(legacyJournal, Files.readString(journal));
        assertTrue(migrated.resetService.recoveryProblem(config).isEmpty());
        String saved = Files.readString(schedule);
        var again = diskHarness(withNether, calls);
        again.startupCoordinator.start();
        again.globalScheduler.runStartupTask();
        again.globalScheduler.tick();
        assertEquals(saved, Files.readString(schedule));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORLDS_PENDING", "WORLDS_RETURNED", "missing", "uuid", "completed", "next"})
    void legacyKeyRequiresEveryCompletionField(String mismatch) throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        assertEquals(ResetStatus.SUCCESS, first.engine.reset("overworld").join().status());
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        Path schedule = dataDirectory.resolve("reset-state.yml");
        String attempt = Files.readString(journal).replace("key=overworld", "key=OVERWORLD");
        String receipt = Files.readString(schedule).replace("overworld:", "Overworld:");
        switch (mismatch) {
            case "WORLDS_PENDING", "WORLDS_RETURNED" -> attempt = attempt.replace("POST_RESET_DONE", mismatch)
                    .replace("completed=" + NOW, "completed=").replace("next=" + NOW.plus(THIRTY_DAYS), "next=");
            case "missing" -> receipt = "version: 1\nworlds: {}\n";
            case "uuid" -> attempt = attempt.replaceFirst("(?m)^id=.*$", "id=213172cd-e944-47a7-9d43-472748efac6b");
            case "completed" -> attempt = attempt.replace("completed=" + NOW, "completed=" + NOW.minusSeconds(1));
            case "next" -> attempt = attempt.replace("next=" + NOW.plus(THIRTY_DAYS), "next=" + NOW.plusSeconds(1));
            default -> throw new AssertionError(mismatch);
        }
        Files.writeString(journal, attempt);
        Files.writeString(schedule, receipt);
        var restart = diskHarness(List.of(config), calls);
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("Overworld").join().status());
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        restart.globalScheduler.tick();
        assertEquals(attempt, Files.readString(journal));
        assertEquals(receipt, Files.readString(schedule));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OVERWORLD", "overworld"})
    void ambiguousScheduleSurvivesIndependentSaveAndRestart(String duplicate) throws IOException {
        var disk = diskWithDueStates("overworld", "nether");
        Path schedule = dataDirectory.resolve("reset-state.yml");
        Files.writeString(schedule, Files.readString(schedule) + "  " + duplicate + ":\n"
                + "    next-reset: '2026-12-15T12:00:00Z'\n    evidence: behalten\n");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        assertTrue(first.resetService.getState("OVERWORLD").isEmpty());
        assertTrue(first.engine.reset("Overworld").join().message().contains(duplicate));
        first.startupCoordinator.start();
        first.globalScheduler.runStartupTask();
        first.globalScheduler.tick();
        assertEquals(List.of("nether"), first.runtime.regenerationKeys);
        assertEquals(1, calls.get());
        assertTrue(Files.readString(schedule).contains("behalten"));
        assertTrue(Files.readString(schedule).contains("2026-12-15T12:00:00Z"));
        var restart = diskHarness(configs, calls);
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("OVERWORLD").join().status());
        assertTrue(restart.resetService.getState("overworld").isEmpty());
        assertEquals(NOW.plus(THIRTY_DAYS), disk.load().get("nether").nextReset());
        assertEquals(1, calls.get());
    }

    @Test
    void physicalReservationSurvivesRemovalReassignmentAndObserverCancellation() throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        var pending = first.runtime.defer("overworld");
        var observer = first.engine.reset("Overworld");
        assertTrue(observer.cancel(false));
        assertTrue(first.resetService.reload(List.of()));
        assertEquals(ResetStatus.ALREADY_RUNNING, first.engine.reset("OVERWORLD").join().status());
        var reassigned = new FarmworldResetConfig("Nether", "TEST_OVERWORLD", true, THIRTY_DAYS);
        var disabled = new FarmworldResetConfig("OVERWORLD", "replacement", false, THIRTY_DAYS);
        assertTrue(first.resetService.reload(List.of(disabled, reassigned)));
        assertFalse(first.engine.isFarmworldAvailable("nether"));
        assertEquals(ResetStatus.ALREADY_RUNNING, first.engine.reset("nether").join().status());
        assertEquals(ResetStatus.ALREADY_RUNNING, first.engine.reset("OVERWORLD").join().status());
        first.runtime.complete("overworld", pending);
        assertEquals(1, calls.get());
        assertEquals(1, first.runtime.closedScopes);
        assertEquals(NOW.plus(THIRTY_DAYS), disk.load().get("overworld").nextReset());
        assertTrue(first.engine.isFarmworldAvailable("Nether"));
        assertEquals(ResetStatus.DISABLED, first.engine.reset("Overworld").join().status());
    }

    @Test
    void legacyOpenAttemptAlsoReservesItsPhysicalNameAfterRestart() throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        first.runtime.failEveryRegeneration("overworld");
        assertEquals(ResetStatus.REGENERATE_FAILED, first.engine.reset("OVERWORLD").join().status());
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String pending = Files.readString(journal).replace("key=overworld", "key=OVERWORLD");
        Files.writeString(journal, pending);
        var reassigned = new FarmworldResetConfig("Nether", "TEST_OVERWORLD", true, THIRTY_DAYS);
        var restart = diskHarness(List.of(reassigned), calls);
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("NETHER").join().status());
        assertEquals(pending, Files.readString(journal));
        assertEquals(1, calls.get());
        assertEquals(0, restart.runtime.openedScopes);
    }

    @Test
    void schedulerAndForceShareOneReservationAcrossCaseOnlyReload() throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var harness = diskHarness(List.of(config), calls);
        var pending = harness.runtime.defer("overworld");
        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String prepared = Files.readString(journal);
        assertTrue(prepared.contains("phase=WORLDS_PENDING"));
        assertTrue(harness.resetService.reload(List.of(new FarmworldResetConfig(
                "OVERWORLD", config.worldName(), true, Duration.ofDays(60)))));

        assertEquals(ResetStatus.ALREADY_RUNNING, harness.engine.reset("OVERWORLD").join().status());
        assertFalse(harness.engine.isFarmworldAvailable("Overworld"));
        assertEquals(Set.of("overworld"), harness.engine.getRunningResets());
        assertEquals(prepared, Files.readString(journal));
        assertEquals(1, harness.runtime.evacuations);
        assertEquals(1, harness.runtime.openedScopes);
        assertEquals(0, harness.runtime.closedScopes);
        assertEquals(1, calls.get());

        harness.runtime.complete("overworld", pending);
        var persisted = disk.load().get("overworld");
        assertTrue(prepared.contains("id=" + persisted.attemptId().orElseThrow()));
        assertEquals(Optional.of(NOW), persisted.lastReset());
        assertEquals(NOW.plus(THIRTY_DAYS), persisted.nextReset());
        assertEquals(1, harness.runtime.closedScopes);
        assertTrue(harness.engine.getRunningResets().isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void dueAutomaticResetPersistsCompletionAndPreventsDuplicateTicks() {
        TestResetStateRepository repository = repositoryWithDueState("overworld");
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        Player evacuated = player("Alex");
        harness.runtime.evacuationPlayers = List.of(evacuated);
        CompletableFuture<World> pendingRegeneration = harness.runtime.defer("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();

        assertTrue(harness.engine.isResetRunning("overworld"));
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(0, repository.saveCount);
        assertEquals(
                List.of("&eDie &6Farmwelt&e wird jetzt zurückgesetzt."),
                harness.notifications
        );
        assertEquals(List.of(
                "global:&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "player:&eDu wurdest aus der &6Farmwelt&e teleportiert, da sie gerade zurückgesetzt wird."
        ), harness.notificationEvents);

        Instant completedAt = NOW.plus(Duration.ofMinutes(2));
        harness.clock.setInstant(completedAt);
        harness.runtime.complete("overworld", pendingRegeneration);

        FarmworldResetState completedState = harness.resetService.getState("overworld").orElseThrow();
        assertFalse(harness.engine.isResetRunning("overworld"));
        assertEquals(Optional.of(completedAt), completedState.lastReset());
        assertEquals(completedAt.plus(THIRTY_DAYS), completedState.nextReset());
        assertEquals(completedState, repository.states.get("overworld"));
        assertEquals(1, repository.saveCount);
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);
        assertEquals(List.of(evacuated), harness.playerNotifications.stream()
                .map(PlayerMessage::player)
                .toList());

        harness.globalScheduler.tick();

        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(1, repository.saveCount);
    }

    @Test
    void differentFarmworldsRunIndependentlyWhileDuplicateTicksStayLocked() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS)
                ),
                repository
        );
        CompletableFuture<World> overworld = harness.runtime.defer("overworld");
        CompletableFuture<World> nether = harness.runtime.defer("nether");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();

        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertTrue(harness.engine.isResetRunning("overworld"));
        assertTrue(harness.engine.isResetRunning("nether"));
        assertEquals(0, repository.saveCount);

        harness.runtime.complete("overworld", overworld);
        assertFalse(harness.engine.isResetRunning("overworld"));
        assertTrue(harness.engine.isResetRunning("nether"));

        harness.runtime.complete("nether", nether);
        assertFalse(harness.engine.isResetRunning("nether"));
        assertEquals(2, repository.saveCount);
    }

    @Test
    void automaticPathKeepsCountdownLifecycleAndPersonalEvacuationOrder() {
        Instant nextReset = NOW.plus(Duration.ofMinutes(5));
        TestResetStateRepository repository = new TestResetStateRepository(Map.of(
                "overworld",
                state("overworld", nextReset)
        ));
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        Player evacuated = player("Alex");
        harness.runtime.evacuationPlayers = List.of(evacuated);

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.clock.setInstant(nextReset);
        harness.globalScheduler.tick();

        assertEquals(List.of(
                "global:&eDie &6Farmwelt&e wird in &65 Minuten&e zurückgesetzt.",
                "global:&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "player:&eDu wurdest aus der &6Farmwelt&e teleportiert, da sie gerade zurückgesetzt wird.",
                "global:&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notificationEvents);
        assertEquals(List.of(evacuated), harness.playerNotifications.stream()
                .map(PlayerMessage::player)
                .toList());
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(Optional.of(nextReset), harness.resetService.getState("overworld")
                .orElseThrow()
                .lastReset());
    }

    @Test
    void notDueScheduleRemainsReadOnlyAcrossMultipleTicks() {
        FarmworldResetState futureState = state("overworld", NOW.plus(Duration.ofDays(10)));
        TestResetStateRepository repository = new TestResetStateRepository(Map.of(
                "overworld", futureState
        ));
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();
        harness.globalScheduler.tick();

        assertEquals(List.of(), harness.runtime.regenerationKeys);
        assertEquals(futureState, harness.resetService.getState("overworld").orElseThrow());
        assertEquals(futureState, repository.states.get("overworld"));
        assertEquals(0, repository.saveCount);
    }

    @Test
    void manualForcePathBroadcastsLifecycleWithoutCountdown() {
        FarmworldResetState futureState = state("overworld", NOW.plus(Duration.ofDays(10)));
        TestResetStateRepository repository = new TestResetStateRepository(Map.of(
                "overworld", futureState
        ));
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        Player evacuated = player("Alex");
        harness.runtime.evacuationPlayers = List.of(evacuated);

        ResetResult result = harness.engine.reset("overworld", ResetOptions.defaults()).join();

        assertEquals(ResetStatus.SUCCESS, result.status());
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);
        assertEquals(List.of(
                "global:&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "player:&eDu wurdest aus der &6Farmwelt&e teleportiert, da sie gerade zurückgesetzt wird.",
                "global:&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt."
        ), harness.notificationEvents);
        assertEquals(List.of(evacuated), harness.playerNotifications.stream()
                .map(PlayerMessage::player)
                .toList());
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(1, repository.saveCount);
    }

    @Test
    void regularSchedulerKeepsAmbiguousWorldBlockedAndResetsHealthyWorld() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS)
                ),
                repository
        );
        harness.runtime.failEveryRegeneration("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();

        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(1, repository.saveCount);
        assertEquals(ScheduledTask.ExecutionState.IDLE, harness.globalScheduler.periodicTask.getExecutionState());

        harness.globalScheduler.tick();

        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(1, repository.saveCount);
    }

    @Test
    void startupCatchUpRunsRealPipelinesSequentiallyBeforePeriodicScheduler() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether", "end"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS),
                        endConfig(Duration.ofDays(60))
                ),
                repository
        );
        CompletableFuture<World> overworld = harness.runtime.defer("overworld");
        CompletableFuture<World> nether = harness.runtime.defer("nether");
        CompletableFuture<World> end = harness.runtime.defer("end");

        harness.startupCoordinator.start();

        assertEquals(StartupResetCoordinator.STARTUP_DELAY_TICKS, harness.globalScheduler.startupDelay);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.globalScheduler.runStartupTask();
        assertEquals(List.of("overworld"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("overworld", overworld);
        assertEquals(List.of("overworld", "nether"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("nether", nether);
        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
        assertEquals(0, harness.globalScheduler.periodicTasks);

        harness.runtime.complete("end", end);

        assertEquals(1, harness.globalScheduler.periodicTasks);
        assertEquals(3, repository.saveCount);
        assertEquals(List.of(
                "&eDie &6Farmwelt&e wird jetzt zurückgesetzt.",
                "&aDie &6Farmwelt&a wurde erfolgreich zurückgesetzt.",
                "&eDie &6Netherfarm&e wird jetzt zurückgesetzt.",
                "&aDie &6Netherfarm&a wurde erfolgreich zurückgesetzt.",
                "&eDie &6Endfarm&e wird jetzt zurückgesetzt.",
                "&aDie &6Endfarm&a wurde erfolgreich zurückgesetzt."
        ), harness.notifications);
        assertEquals(Optional.of(NOW), harness.resetService.getState("overworld").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());

        harness.globalScheduler.tick();
        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openWorldsAttemptSurvivesStartupObservationAndLateCompletion(boolean shutdown) throws IOException {
        diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var pending = h.runtime.defer("overworld");
        h.startupCoordinator.start();
        h.globalScheduler.runStartupTask();
        var before = h.resetService.getState("overworld");
        var journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String evidence = Files.readString(journal);
        assertTrue(evidence.contains("WORLDS_PENDING"));
        // Im Test wird der nächste verzögerte Callback ohne echte Wartezeit ausgelöst.
        h.clock.setInstant(NOW.plus(Duration.ofMinutes(10)));
        h.globalScheduler.observation.run();
        assertEquals(1, h.globalScheduler.periodicTasks);
        assertEquals(ResetStatus.ALREADY_RUNNING, h.engine.reset("OVERWORLD").join().status());
        assertTrue(h.resetService.reload(configs));
        h.globalScheduler.tick();
        h.globalScheduler.tick();
        assertEquals(before, h.resetService.getState("overworld"));
        assertEquals(evidence, Files.readString(journal));
        assertFalse(pending.isDone());
        assertEquals(List.of("overworld"), h.runtime.regenerationKeys);
        assertEquals(ResetStatus.WORLDS_HANDOFF_DEFERRED, h.engine.reset("nether").join().status());
        assertEquals(1, h.runtime.evacuations);
        assertEquals(1, h.runtime.openedScopes);
        assertEquals(0, h.runtime.closedScopes);
        assertEquals(1, java.util.Collections.frequency(h.runtime.regenerationKeys, "overworld"));
        if (shutdown) {
            h.engine.stop();
            h.startupCoordinator.stop();
        }
        h.runtime.complete("overworld", pending);
        assertEquals(1, h.globalScheduler.periodicTasks);
        if (shutdown) {
            assertEquals(evidence, Files.readString(journal));
            assertTrue(h.runtime.postResetOptions.isEmpty());
            assertEquals(1, h.runtime.closedScopes);
            assertEquals(1, calls.get());
            var restarted = diskHarness(configs, calls);
            assertEquals(ResetStatus.RECOVERY_BLOCKED, restarted.engine.reset("overworld").join().status());
        } else {
            var receipt = h.resetService.getState("overworld").orElseThrow().attemptId().orElseThrow();
            assertTrue(evidence.contains(receipt.toString()));
            assertTrue(Files.readString(journal).contains("POST_RESET_DONE"));
            h.globalScheduler.tick();
            assertEquals(2, calls.get());
            assertEquals(2, h.runtime.openedScopes);
            assertEquals(2, h.runtime.closedScopes);
            assertEquals(2, h.runtime.evacuations);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledEvacuationAllowsIndependentCatchUp(boolean shutdown) throws IOException {
        diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var teleport = new CompletableFuture<FarmworldEvacuationResult>();
        h.runtime.nextEvacuation = teleport;
        h.startupCoordinator.start();
        h.globalScheduler.runStartupTask();
        h.clock.setInstant(NOW.plus(Duration.ofMinutes(10)));
        h.globalScheduler.observation.run();
        h.globalScheduler.tick();
        assertEquals(List.of("nether"), h.runtime.regenerationKeys);
        assertEquals(1, h.globalScheduler.periodicTasks);
        assertEquals(ResetStatus.ALREADY_RUNNING, h.engine.reset("OVERWORLD").join().status());
        if (shutdown) {
            h.engine.stop();
            h.startupCoordinator.stop();
        }
        teleport.complete(FarmworldEvacuationResult.completed(List.of()));
        assertEquals(shutdown ? 1 : 2, calls.get());
        assertEquals(2, h.runtime.closedScopes);
        assertEquals(1, h.globalScheduler.periodicTasks);
    }

    @Test
    void unobservableTeleportKeepsBothReservations() throws IOException {
        var configs = List.of(config("overworld", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        h.runtime.nextEvacuation = CompletableFuture.completedFuture(FarmworldEvacuationResult.failed(List.of(),
                new FarmworldEvacuationResult.UnconfirmedTeleportException("Teleportende unbekannt", null)));
        assertEquals(ResetStatus.EVACUATION_FAILED, h.engine.reset("overworld").join().status());
        assertTrue(h.engine.isResetRunning("OVERWORLD"));
        assertTrue(h.resetService.reload(List.of(new FarmworldResetConfig("nether", configs.getFirst().worldName(),
                true, THIRTY_DAYS))));
        assertEquals(ResetStatus.ALREADY_RUNNING, h.engine.reset("nether").join().status());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shutdownImmediatelyBeforeOrAfterBindingWorldsHandoff(boolean accepted) throws Exception {
        var disk = diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var boundary = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        Runnable pause = () -> {
            boundary.countDown();
            try {
                assertTrue(resume.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        };
        if (accepted) {
            // Bereits im Lifecycle-Adapter, unmittelbar vor dem Aufruf des echten API-Einstiegspunkts.
            h.logger.setLevel(Level.INFO);
            h.logger.setFilter(record -> {
                if (record.getMessage().startsWith("Regeneration von '")) pause.run();
                return false;
            });
        } else {
            var inspections = new AtomicInteger();
            h.runtime.beforeInspection = () -> {
                if (inspections.incrementAndGet() == 2) pause.run();
            };
        }
        var external = h.runtime.defer("overworld");
        var journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var reset = workers.submit(() -> h.engine.reset("overworld"));
            String evidence;
            try {
                assertTrue(boundary.await(5, TimeUnit.SECONDS));
                evidence = Files.readString(journal);
                assertTrue(evidence.contains("WORLDS_PENDING"));
                workers.submit(h.engine::stop).get(5, TimeUnit.SECONDS);
                assertEquals(0, calls.get());
                assertEquals(ResetStatus.DISABLED, h.engine.reset("nether").join().status());
            } finally {
                resume.countDown();
            }
            var result = reset.get(5, TimeUnit.SECONDS);
            if (accepted) {
                assertFalse(result.isDone());
                assertFalse(external.isDone());
                h.runtime.complete("overworld", external);
            }
            assertEquals(ResetStatus.DISABLED, result.get(5, TimeUnit.SECONDS).status());
            assertEquals(accepted ? 1 : 0, calls.get());
            assertEquals(evidence, Files.readString(journal));
            assertEquals(dueStates("overworld", "nether"), disk.load());
            assertTrue(h.runtime.postResetOptions.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "wrappedCancel", "throw", "nullFuture", "observerFailure", "timeout"})
    void lostWorldsCompletionDefersNewAndPreparedHandoffs(String fault) throws IOException {
        var disk = diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var evacuation = new CompletableFuture<FarmworldEvacuationResult>();
        h.runtime.nextEvacuation = evacuation;
        var nether = h.engine.reset("nether");
        var external = new CompletableFuture<World>();
        h.runtime.nextWorldsCall = () -> switch (fault) {
            case "throw" -> throw new IllegalStateException("Aufruf ohne Abschlussbeleg");
            case "nullFuture" -> null;
            case "observerFailure" -> new CompletableFuture<World>() {
                @Override
                public <U> CompletableFuture<U> handle(BiFunction<? super World, Throwable, ? extends U> action) {
                    throw new IllegalStateException("Beobachter abgelehnt");
                }
            };
            default -> external;
        };
        var reset = h.engine.reset("overworld");
        switch (fault) {
            case "cancel" -> assertTrue(external.cancel(false));
            case "wrappedCancel" -> external.completeExceptionally(new CompletionException(new CancellationException()));
            case "timeout" -> external.completeExceptionally(new TimeoutException());
        }
        assertEquals(ResetStatus.REGENERATE_FAILED, reset.join().status());
        assertTrue(h.engine.runningDiagnosis("overworld").orElseThrow().contains("Worlds-Abschluss ungeklärt"));
        var journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String evidence = Files.readString(journal);
        assertTrue(evidence.contains("WORLDS_PENDING"));
        evacuation.complete(FarmworldEvacuationResult.completed(List.of()));
        assertEquals(ResetStatus.WORLDS_HANDOFF_DEFERRED, nether.join().status());
        assertTrue(h.resetService.recoveryProblem(configs.get(1)).isEmpty());
        assertEquals(ResetStatus.WORLDS_HANDOFF_DEFERRED, h.engine.reset("nether").join().status());
        h.clock.setInstant(NOW.plus(Duration.ofDays(1)));
        assertTrue(h.resetService.reload(configs));
        h.startupCoordinator.start();
        h.globalScheduler.runStartupTask();
        h.globalScheduler.tick();
        assertEquals(ResetStatus.ALREADY_RUNNING, h.engine.reset("OVERWORLD").join().status());
        assertEquals(evidence, Files.readString(journal));
        assertEquals(dueStates("overworld", "nether"), disk.load());
        assertEquals(1, calls.get());
    }

    @Test
    void worldsEventVetoIsTerminalAndAllowsIndependentHandoff() throws IOException {
        diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var external = new CompletableFuture<World>();
        h.runtime.nextWorldsCall = () -> external;
        var reset = h.engine.catchUpIfDue("overworld", NOW);
        assertEquals(ResetStatus.WORLDS_HANDOFF_DEFERRED, h.engine.reset("nether").join().status());
        var failure = new WorldOperationException(WorldOperationException.Reason.EVENT_CANCELLED);
        external.completeExceptionally(failure);
        assertEquals(ResetStatus.REGENERATE_FAILED, reset.join().status());
        assertEquals(failure, reset.join().cause());
        assertEquals(ResetStatus.RECOVERY_BLOCKED, h.engine.reset("overworld").join().status());
        assertEquals(1, calls.get());
        assertEquals(ResetStatus.SUCCESS, h.engine.reset("nether").join().status());
        assertEquals(List.of("overworld", "nether"), h.runtime.regenerationKeys);
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "lastCheck", "afterWorlds", "afterPost", "state"})
    void shutdownAtCheckpointPreservesExactlyTheDurableEvidence(String point) throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var writes = new AtomicInteger();
        var inspections = new AtomicInteger();
        int stopAfterWrite = switch (point) {
            case "journal" -> 1;
            case "afterWorlds" -> 2;
            case "afterPost" -> 3;
            case "state" -> 4;
            default -> -1;
        };
        h.scheduler.afterAsyncWork = () -> {
            if (writes.incrementAndGet() == stopAfterWrite) h.engine.stop();
        };
        h.runtime.beforeInspection = () -> {
            if (inspections.incrementAndGet() == 2 && point.equals("lastCheck")) h.engine.stop();
        };
        var result = h.engine.reset("overworld");
        assertTrue(result.isDone());
        assertEquals(ResetStatus.DISABLED, h.engine.reset("overworld").join().status());
        int expectedCalls = point.equals("journal") || point.equals("lastCheck") ? 0 : 1;
        assertEquals(expectedCalls, calls.get());
        assertEquals(1, h.runtime.closedScopes);
        var restart = diskHarness(configs, calls);
        if (point.equals("state")) {
            assertEquals(ResetStatus.SUCCESS, result.join().status());
            assertTrue(disk.load().get("overworld").attemptId().isPresent());
            assertTrue(restart.resetService.recoveryProblem(config).isEmpty());
        } else {
            String phase = switch (point) {
                case "afterPost" -> "POST_RESET_DONE";
                case "afterWorlds" -> "WORLDS_RETURNED";
                default -> "WORLDS_PENDING";
            };
            assertTrue(restart.engine.reset("overworld").join().message().contains(phase));
            assertTrue(disk.load().get("overworld").attemptId().isEmpty());
        }
        assertEquals(expectedCalls, calls.get());
    }

    @Test
    void startupFailureBlocksOnlyAmbiguousWorldAcrossPeriodicTicks() {
        TestResetStateRepository repository = new TestResetStateRepository(dueStates(
                "overworld", "nether", "end"
        ));
        WorkflowHarness harness = harness(
                List.of(
                        config("overworld", THIRTY_DAYS),
                        config("nether", THIRTY_DAYS),
                        endConfig(Duration.ofDays(60))
                ),
                repository
        );
        harness.runtime.failEveryRegeneration("overworld");

        harness.startupCoordinator.start();
        harness.globalScheduler.runStartupTask();

        assertEquals(List.of("overworld", "nether", "end"), harness.runtime.regenerationKeys);
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(Optional.of(NOW), harness.resetService.getState("nether").orElseThrow().lastReset());
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());
        assertEquals(2, repository.saveCount);
        assertEquals(1, harness.globalScheduler.periodicTasks);

        harness.globalScheduler.tick();

        assertEquals(
                List.of("overworld", "nether", "end"),
                harness.runtime.regenerationKeys
        );
        assertEquals(state("overworld", NOW), harness.resetService.getState("overworld").orElseThrow());
        assertEquals(2, repository.saveCount);
    }

    @Test
    void automaticEndResetUsesDefaultOptionsAndConfiguredDragonlessPolicy() {
        TestResetStateRepository repository = repositoryWithDueState("end");
        WorkflowHarness harness = harness(
                List.of(endConfig(Duration.ofDays(60))),
                repository
        );

        var pending = harness.runtime.defer("end");
        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        assertEquals(ResetStatus.ALREADY_RUNNING,
                harness.engine.reset("END", ResetOptions.allowingEnderDragon()).join().status());
        assertEquals(1, harness.runtime.openedScopes);
        assertEquals(1, harness.runtime.regenerationCalls.size());
        harness.runtime.complete("end", pending);

        RegenerationCall call = harness.runtime.regenerationCalls.getFirst();
        assertEquals("end", call.farmworldKey());
        assertEquals(
                FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED,
                call.options().endDragonFightDataMode()
        );
        assertEquals(List.of(ResetOptions.defaults()), harness.runtime.postResetOptions);
        assertEquals(Optional.of(NOW), harness.resetService.getState("end").orElseThrow().lastReset());
    }

    @Test
    void automaticResetKeepsSnapshotAcrossReloadAndNextPipelineUsesNewInterval() {
        TestResetStateRepository repository = repositoryWithDueState("overworld");
        WorkflowHarness harness = harness(
                List.of(config("overworld", THIRTY_DAYS)),
                repository
        );
        CompletableFuture<World> pendingRegeneration = harness.runtime.defer("overworld");

        harness.automaticScheduler.start();
        harness.globalScheduler.tick();
        assertTrue(harness.resetService.reload(List.of(
                config("overworld", Duration.ofDays(60))
        )));

        Instant firstCompletion = NOW.plus(Duration.ofMinutes(2));
        harness.clock.setInstant(firstCompletion);
        harness.runtime.complete("overworld", pendingRegeneration);

        FarmworldResetState firstState = harness.resetService.getState("overworld").orElseThrow();
        assertEquals(firstCompletion.plus(THIRTY_DAYS), firstState.nextReset());

        harness.clock.setInstant(firstState.nextReset());
        harness.globalScheduler.tick();

        FarmworldResetState secondState = harness.resetService.getState("overworld").orElseThrow();
        assertEquals(Optional.of(firstState.nextReset()), secondState.lastReset());
        assertEquals(firstState.nextReset().plus(Duration.ofDays(60)), secondState.nextReset());
        assertEquals(List.of("overworld", "overworld"), harness.runtime.regenerationKeys);
        assertEquals(2, repository.saveCount);
    }

    @Test
    void restartWithYamlRepositoryPreservesFutureScheduleAndSkipsCatchUp() throws IOException {
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        Path stateFile = dataDirectory.resolve("reset-state.yml");
        YamlResetStateRepository repository = new YamlResetStateRepository(stateFile, quietLogger());
        FarmworldResetConfig config = config("overworld", THIRTY_DAYS);
        FarmworldResetService firstInstance = new FarmworldResetService(
                repository,
                dataDirectory,
                clock,
                quietLogger()
        );
        assertTrue(firstInstance.reload(List.of(config)));
        FarmworldResetState persistedState = firstInstance.getState("overworld").orElseThrow();

        WorkflowHarness restarted = new WorkflowHarness(List.of(config), repository, clock);
        restarted.startupCoordinator.start();
        restarted.globalScheduler.runStartupTask();

        assertEquals(NOW.plus(THIRTY_DAYS), persistedState.nextReset());
        assertEquals(persistedState, restarted.resetService.getState("overworld").orElseThrow());
        assertEquals(persistedState, repository.load().get("overworld"));
        assertEquals(List.of(), restarted.runtime.regenerationKeys);
        assertEquals(1, restarted.globalScheduler.periodicTasks);
    }

    @Test
    void failedStateSaveNeverRepeatsWorldsCallAcrossTicksForceReloadAndRestart() throws IOException {
        Path stateFile = dataDirectory.resolve("reset-state.yml");
        YamlResetStateRepository disk = new YamlResetStateRepository(stateFile, quietLogger());
        disk.save(dueStates("overworld"));
        List<FarmworldResetConfig> configs = List.of(config("overworld", THIRTY_DAYS));
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        AtomicInteger worldsCalls = new AtomicInteger();
        WorkflowHarness first = new WorkflowHarness(configs, disk, clock, worldsCalls);
        Path obstruction = Files.createDirectory(dataDirectory.resolve("reset-state.yml.tmp"));
        Files.writeString(obstruction.resolve("fault"), "simulierter Schreibfehler");

        first.automaticScheduler.start();
        first.globalScheduler.tick();
        assertEquals(1, worldsCalls.get());
        assertEquals(dueStates("overworld"), disk.load());
        assertFalse(first.engine.isResetRunning("overworld"));
        Files.delete(obstruction.resolve("fault"));
        Files.delete(obstruction);
        assertTrue(first.resetService.reload(configs));
        first.globalScheduler.tick();
        first.globalScheduler.tick();
        ResetResult blocked = first.engine.reset("OVERWORLD").join();
        assertEquals(ResetStatus.RECOVERY_BLOCKED, blocked.status());
        assertTrue(blocked.message().contains("POST_RESET_DONE"));
        assertEquals(1, worldsCalls.get());
        first.automaticScheduler.stop();

        WorkflowHarness restarted = new WorkflowHarness(configs,
                new YamlResetStateRepository(stateFile, quietLogger()), clock, worldsCalls);
        restarted.startupCoordinator.start();
        restarted.globalScheduler.runStartupTask();
        restarted.globalScheduler.tick();
        restarted.globalScheduler.tick();
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restarted.engine.reset("OverWorld").join().status());
        assertEquals(1, worldsCalls.get());
        assertEquals(dueStates("overworld"), disk.load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspection", "evacuation", "playersRemain"})
    void failureBeforeJournalAllowsSafeRetryAfterRestart(String failure) throws IOException {
        var disk = diskWithDueStates("overworld");
        var configs = List.of(config("overworld", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        first.runtime.preflightFailure = failure;
        ResetStatus expected = failure.equals("inspection") ? ResetStatus.WORLD_NOT_LOADED : ResetStatus.EVACUATION_FAILED;
        assertEquals(expected, first.engine.reset("overworld").join().status());
        assertFalse(first.engine.isResetRunning("overworld"));
        assertTrue(first.resetService.recoveryProblem(configs.getFirst()).isEmpty());
        assertEquals(0, calls.get());
        assertEquals(dueStates("overworld"), disk.load());

        var restart = diskHarness(configs, calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        assertEquals(1, calls.get());
        assertTrue(restart.resetService.recoveryProblem(configs.getFirst()).isEmpty());
    }

    @Test
    void journalWriteFailurePreventsHandoffToWorlds() throws IOException {
        diskWithDueStates("overworld");
        var configs = List.of(config("overworld", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        Files.createDirectory(dataDirectory.resolve("reset-recovery-overworld.journal.tmp"));
        assertEquals(ResetStatus.RECOVERY_BLOCKED, first.engine.reset("overworld").join().status());
        first.automaticScheduler.start();
        first.globalScheduler.tick();
        assertEquals(0, calls.get());
        assertFalse(first.engine.isResetRunning("overworld"));
    }

    @Test
    void crashImmediatelyBeforeWorldsBlocksEvenWithNoHandoff() throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        first.resetService.prepare(config, first.resetService.newAttempt(config));

        var restart = diskHarness(configs, calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        restart.globalScheduler.tick();
        assertTrue(restart.engine.reset("overworld").join().message().contains("WORLDS_PENDING"));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unload", "delete", "recreated"})
    void worldsFailureAtAnyInternalStageKeepsAttemptBlocked(String stage) throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        CompletableFuture<World> pending = first.runtime.defer("overworld");
        CompletableFuture<ResetResult> reset = first.engine.resetIfDue("overworld", NOW);
        assertEquals(ResetStatus.ALREADY_RUNNING, first.engine.reset("overworld").join().status());
        // Verschiedene mögliche Weltzustände hinter derselben fehlgeschlagenen Worlds-Future.
        if (!stage.equals("unload")) {
            first.runtime.activeWorlds.remove("overworld");
        }
        if (stage.equals("recreated")) {
            first.runtime.activeWorlds.put("overworld", first.runtime.createRegeneratedWorld("overworld"));
        }
        pending.completeExceptionally(new IOException(stage));
        assertEquals(ResetStatus.REGENERATE_FAILED, reset.join().status());
        assertFalse(first.engine.isResetRunning("overworld"));
        var restart = diskHarness(configs, calls);
        assertTrue(restart.engine.reset("overworld").join().message().contains("WORLDS_PENDING"));
        restart.automaticScheduler.start();
        restart.globalScheduler.tick();
        assertEquals(1, calls.get());
    }

    @Test
    void pendingPostResetBlocksRestartUntilLateCompletionAndMatchingReceipt() throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        first.runtime.postResetResult = new CompletableFuture<>();
        var observer = first.engine.reset("overworld");
        assertTrue(observer.cancel(false));
        assertTrue(first.engine.isResetRunning("overworld"));
        assertTrue(first.resetService.reload(configs));

        var restart = diskHarness(configs, calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        assertTrue(restart.engine.reset("overworld").join().message().contains("WORLDS_RETURNED"));
        first.runtime.postResetResult.complete(PostResetResult.success());
        assertFalse(first.engine.isResetRunning("overworld"));
        var completed = diskHarness(configs, calls);
        assertTrue(completed.resetService.recoveryProblem(config).isEmpty());
        assertEquals(ResetStatus.NOT_DUE, completed.engine.resetIfDue("overworld", NOW).join().status());
        assertEquals(1, calls.get());
    }

    @Test
    void shutdownDuringWorldsKeepsOpenAttemptAndLateFutureCannotRestartScheduler() throws IOException {
        diskWithDueStates("overworld", "nether");
        var configs = List.of(config("overworld", THIRTY_DAYS), config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        var pending = first.runtime.defer("overworld");
        first.startupCoordinator.start();
        first.globalScheduler.runStartupTask();
        first.startupCoordinator.stop();
        assertFalse(pending.isCancelled());
        assertEquals(1, calls.get());
        var restart = diskHarness(configs, calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        assertEquals(List.of("nether"), restart.runtime.regenerationKeys);
        assertTrue(restart.engine.reset("overworld").join().message().contains("WORLDS_PENDING"));
        // Der alte Prozess endet hier mit einer späten Fehlermeldung von Worlds.
        pending.completeExceptionally(new IOException("verspäteter Worlds-Abschluss"));
        assertFalse(first.engine.isResetRunning("overworld"));
        assertEquals(0, first.globalScheduler.periodicTasks);
        restart.globalScheduler.tick();
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "directory", "version", "truncated", "wrongKey", "duplicateField",
            "readyWithReceipt", "wrongReceipt", "wrongSchedule", "missingSchedule", "malformedSchedule"})
    void damagedRecoveryBlocksOnlyAffectedWorldAfterRestart(String damage) throws IOException {
        var disk = diskWithDueStates("overworld", "nether");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config, config("nether", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        assertEquals(ResetStatus.SUCCESS, first.engine.reset("overworld").join().status());
        Path file = dataDirectory.resolve("reset-recovery-overworld.journal");
        String valid = Files.readString(file);
        switch (damage) {
            case "missing" -> Files.delete(file);
            case "directory" -> {
                Files.delete(file);
                Files.createDirectory(file);
            }
            case "version" -> Files.writeString(file, valid.replace("version=1", "version=99"));
            case "truncated" -> Files.writeString(file, valid.substring(0, valid.indexOf("world=")));
            case "wrongKey" -> Files.writeString(file, valid.replace("key=overworld", "key=nether"));
            case "duplicateField" -> Files.writeString(file, valid + "phase=READY\n");
            case "readyWithReceipt" -> Files.writeString(file, "version=1\nphase=READY\n");
            case "wrongReceipt" -> Files.writeString(file, valid.replaceFirst("id=[^\n]+",
                    "id=213172cd-e944-47a7-9d43-472748efac6b"));
            case "wrongSchedule" -> Files.writeString(file, valid.replace("next=" + NOW.plus(THIRTY_DAYS),
                    "next=" + NOW.plus(Duration.ofDays(1))));
            case "missingSchedule" -> Files.delete(dataDirectory.resolve("reset-state.yml"));
            case "malformedSchedule" -> Files.writeString(dataDirectory.resolve("reset-state.yml"), "worlds: [");
            default -> throw new AssertionError(damage);
        }

        var restart = diskHarness(configs, calls);
        assertTrue(restart.resetService.recoveryProblem(config).isPresent());
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("overworld").join().status());
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        restart.globalScheduler.tick();
        if (damage.endsWith("Schedule") && !damage.equals("wrongSchedule")) {
            assertTrue(restart.resetService.getState("overworld").isEmpty());
            assertEquals(ResetStatus.SUCCESS, restart.engine.reset("nether").join().status());
        }
        assertEquals(List.of("nether"), restart.runtime.regenerationKeys);
        assertEquals(2, calls.get());
        assertTrue(disk.load().get("nether").attemptId().isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORLDS_PENDING", "WORLDS_RETURNED"})
    void failedPhaseWriteKeepsLastDurableEvidence(String phase) throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        var pending = first.runtime.defer("overworld");
        var post = new CompletableFuture<PostResetResult>();
        first.runtime.postResetResult = post;
        var reset = first.engine.reset("overworld");
        if (phase.equals("WORLDS_RETURNED")) {
            first.runtime.complete("overworld", pending);
        }
        Files.createDirectory(dataDirectory.resolve("reset-recovery-overworld.journal.tmp"));
        if (phase.equals("WORLDS_PENDING")) {
            first.runtime.complete("overworld", pending);
        } else {
            post.complete(PostResetResult.success());
        }
        assertEquals(ResetStatus.RECOVERY_BLOCKED, reset.join().status());
        assertFalse(first.engine.isResetRunning("overworld"));
        var restart = diskHarness(configs, calls);
        assertTrue(restart.engine.reset("overworld").join().message().contains(phase));
        assertEquals(1, calls.get());
    }

    @Test
    void diskCommitAlreadyProvesCompletionBeforeOriginalPipelineReturns() throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var configs = List.of(config);
        var calls = new AtomicInteger();
        var checked = new java.util.concurrent.atomic.AtomicBoolean();
        ResetStateRepository interruptAfterSave = new ResetStateRepository() {
            public Map<String, FarmworldResetState> load() throws IOException {
                return disk.load();
            }

            public void save(Map<String, FarmworldResetState> states) throws IOException {
                disk.save(states);
                var restart = diskHarness(configs, calls);
                assertTrue(restart.resetService.recoveryProblem(config).isEmpty());
                restart.startupCoordinator.start();
                restart.globalScheduler.runStartupTask();
                restart.globalScheduler.tick();
                assertEquals(1, calls.get());
                checked.set(true);
            }
        };
        var first = new WorkflowHarness(configs, interruptAfterSave, new MutableClock(NOW, ZoneOffset.UTC), calls);
        assertEquals(ResetStatus.SUCCESS, first.engine.reset("overworld").join().status());
        assertTrue(checked.get());
    }

    @Test
    void newAttemptCannotBeReleasedByPreviousReceiptOrDifferentCase() throws IOException {
        diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        assertEquals(ResetStatus.SUCCESS, first.engine.reset("overworld").join().status());
        var pending = first.runtime.defer("overworld");
        first.engine.reset("overworld");
        assertFalse(pending.isDone());
        var alias = new FarmworldResetConfig("OVERWORLD", config.worldName(), true, THIRTY_DAYS);
        var restart = diskHarness(List.of(alias), calls);
        assertTrue(restart.engine.reset("OVERWORLD").join().message().contains("WORLDS_PENDING"));
        assertEquals(2, calls.get());
    }

    @Test
    void verifiedPostResetReceiptCanRepairScheduleWithoutWorlds() throws IOException {
        var disk = diskWithDueStates("overworld");
        var config = config("overworld", THIRTY_DAYS);
        var calls = new AtomicInteger();
        var first = diskHarness(List.of(config), calls);
        var fault = Files.createDirectory(dataDirectory.resolve("reset-state.yml.tmp"));
        Files.writeString(fault.resolve("fault"), "Schreibfehler");
        assertEquals(ResetStatus.STATE_SAVE_FAILED, first.engine.reset("overworld").join().status());
        Files.delete(fault.resolve("fault"));
        Files.delete(fault);
        var fields = Files.readAllLines(dataDirectory.resolve("reset-recovery-overworld.journal")).stream()
                .map(line -> line.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair -> pair[1]));
        assertEquals("POST_RESET_DONE", fields.get("phase"));
        disk.save(Map.of("overworld", new FarmworldResetState("overworld",
                Optional.of(Instant.parse(fields.get("completed"))), Instant.parse(fields.get("next")),
                Optional.of(java.util.UUID.fromString(fields.get("id"))))));
        var restart = diskHarness(List.of(config), calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        restart.globalScheduler.tick();
        assertTrue(restart.resetService.recoveryProblem(config).isEmpty());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedWorldBeforeHandoffRestoresExactJournalAcrossRestart(boolean priorSuccess) throws IOException {
        var disk = diskWithDueStates("overworld");
        var configs = List.of(config("overworld", THIRTY_DAYS));
        var calls = new AtomicInteger();
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        if (priorSuccess) {
            var previousRun = diskHarness(configs, calls);
            assertEquals(ResetStatus.SUCCESS, previousRun.engine.reset("overworld").join().status());
            Files.writeString(journal, Files.readString(journal).replace("key=overworld", "key=Overworld"));
        }
        var first = diskHarness(configs, calls);
        int before = calls.get();
        String previous = Files.readString(journal);
        var schedule = disk.load();
        AtomicInteger inspections = new AtomicInteger();
        first.runtime.beforeInspection = () -> {
            if (inspections.incrementAndGet() == 2) {
                assertEquals(ResetStatus.ALREADY_RUNNING, first.engine.reset("overworld").join().status());
                // Eine zweite Service-Instanz kennt nur den dauerhaften Stand vor der Rücknahme.
                var observer = diskHarness(configs, calls);
                assertEquals(ResetStatus.RECOVERY_BLOCKED, observer.engine.reset("overworld").join().status());
                first.runtime.activeWorlds.put("overworld", first.runtime.createRegeneratedWorld("overworld"));
            }
        };
        assertEquals(ResetStatus.INVALID_CONFIGURATION, first.engine.reset("overworld").join().status());
        assertEquals(before, calls.get());
        assertEquals(schedule, disk.load());
        assertEquals(previous, Files.readString(journal));

        var restart = diskHarness(configs, calls);
        assertEquals(ResetStatus.SUCCESS, restart.engine.reset("overworld").join().status());
        assertEquals(before + 1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"writeFailure", "differentAttempt"})
    void failedRollbackKeepsPendingAcrossTicksForceAndRestart(String fault) throws IOException {
        var disk = diskWithDueStates("overworld");
        var configs = List.of(config("overworld", THIRTY_DAYS));
        var calls = new AtomicInteger();
        var first = diskHarness(configs, calls);
        Path obstruction = dataDirectory.resolve("reset-recovery-overworld.journal.tmp");
        Path journal = dataDirectory.resolve("reset-recovery-overworld.journal");
        String otherId = "bcfd10e3-40a0-45ac-9c0c-a56ba3371c28";
        AtomicInteger inspections = new AtomicInteger();
        first.runtime.beforeInspection = () -> {
            if (inspections.incrementAndGet() == 2) {
                try {
                    if (fault.equals("writeFailure")) {
                        Files.createDirectory(obstruction);
                    } else {
                        Files.writeString(journal, Files.readString(journal).replaceFirst("(?m)^id=.*$", "id=" + otherId));
                    }
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                first.runtime.preflightFailure = "playersRemain";
            }
        };
        assertEquals(ResetStatus.RECOVERY_BLOCKED, first.engine.reset("overworld").join().status());
        assertFalse(first.engine.isResetRunning("overworld"));
        if (fault.equals("writeFailure")) {
            Files.delete(obstruction);
        } else {
            assertTrue(Files.readString(journal).contains("id=" + otherId));
        }
        assertEquals(ResetStatus.RECOVERY_BLOCKED, first.engine.reset("overworld").join().status());
        var restart = diskHarness(configs, calls);
        restart.startupCoordinator.start();
        restart.globalScheduler.runStartupTask();
        restart.globalScheduler.tick();
        assertTrue(restart.engine.reset("overworld").join().message().contains("WORLDS_PENDING"));
        assertEquals(dueStates("overworld"), disk.load());
        assertEquals(0, calls.get());
    }

    private YamlResetStateRepository diskWithDueStates(String... keys) throws IOException {
        var disk = new YamlResetStateRepository(dataDirectory.resolve("reset-state.yml"), quietLogger());
        disk.save(dueStates(keys));
        return disk;
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingBackup", "validVeto", "timeout", "success", "stateSaveFailure", "postResetFailure"})
    void dragonFilesFollowTheJournalAttemptThroughCompletion(String scenario) throws Exception {
        var configs = List.of(endConfig(THIRTY_DAYS));
        diskWithDueStates("end");
        var calls = new AtomicInteger();
        var h = diskHarness(configs, calls);
        var folder = Files.createDirectory(dataDirectory.resolve("farm"));
        Path active = folder.resolve("data/minecraft/ender_dragon_fight.dat");
        Files.createDirectories(active.getParent());
        Files.writeString(active, "Original");
        World old = world("test_end", FarmworldType.END, folder.toString(), 1L);
        h.runtime.activeWorlds.put("end", old);
        h.runtime.farmworldKeys.put(old, "end");
        var returned = new CompletableFuture<World>();
        h.runtime.nextWorldsCall = () -> returned;
        var operation = h.engine.reset("END");
        h.adapter.resetEndDragonFightData(new WorldRegenerateEvent(old));
        var unload = new WorldUnloadEvent(old);
        h.adapter.prepareEndDragonFightData(unload);
        assertFalse(unload.isCancelled());
        byte[] prepared = Files.readAllBytes(active);
        Path journal = dataDirectory.resolve("reset-recovery-end.journal");
        String id = Files.readAllLines(journal).stream().filter(line -> line.startsWith("id="))
                .findFirst().orElseThrow().substring(3);
        Path backup = active.resolveSibling(active.getFileName() + ".farmwelt-stage").resolve(id + ".bak");
        assertEquals("Original", Files.readString(backup));
        var veto = new WorldOperationException(WorldOperationException.Reason.UNLOAD_FAILED).key(old.getKey());
        switch (scenario) {
            case "missingBackup", "validVeto" -> {
                if (scenario.equals("missingBackup")) Files.delete(backup);
                unload.setCancelled(true);
                returned.completeExceptionally(veto);
            }
            case "timeout" -> returned.completeExceptionally(new TimeoutException());
            default -> {
                if (scenario.equals("stateSaveFailure")) Files.createDirectory(dataDirectory.resolve("reset-state.yml.tmp"));
                if (scenario.equals("postResetFailure")) {
                    h.runtime.postResetResult = CompletableFuture.completedFuture(PostResetResult.failure("Fehler", null));
                }
                World next = world("test_end", FarmworldType.END, folder.toString(), 2L);
                h.runtime.activeWorlds.put("end", next);
                returned.complete(next);
            }
        }
        if (scenario.equals("success")) {
            assertEquals(ResetStatus.SUCCESS, operation.join().status());
            assertEquals(id, h.resetService.getState("end").orElseThrow().attemptId().orElseThrow().toString());
            assertFalse(Files.exists(backup.getParent()));
            return;
        }
        assertFalse(operation.join().successful());
        if (scenario.equals("validVeto")) {
            assertEquals("Original", Files.readString(active));
            assertFalse(Files.exists(backup.getParent()));
        } else {
            org.junit.jupiter.api.Assertions.assertArrayEquals(prepared, Files.readAllBytes(active));
            if (scenario.equals("missingBackup")) {
                assertTrue(veto.getSuppressed()[0].getMessage().contains("fehlt"));
                assertFalse(Files.exists(backup));
            } else {
                assertEquals("Original", Files.readString(backup));
            }
        }
        assertTrue(Files.readString(journal).contains(switch (scenario) {
            case "stateSaveFailure" -> "POST_RESET_DONE";
            case "postResetFailure" -> "WORLDS_RETURNED";
            default -> "WORLDS_PENDING";
        }));
        var restart = diskHarness(configs, calls);
        assertEquals(ResetStatus.RECOVERY_BLOCKED, restart.engine.reset("end").join().status());
        restart.automaticScheduler.start();
        restart.globalScheduler.tick();
        assertEquals(1, calls.get());
    }

    private WorkflowHarness diskHarness(List<FarmworldResetConfig> configs, AtomicInteger calls) {
        return new WorkflowHarness(configs, new YamlResetStateRepository(dataDirectory.resolve("reset-state.yml"),
                quietLogger()), new MutableClock(NOW, ZoneOffset.UTC), calls);
    }

    private WorkflowHarness harness(
            List<FarmworldResetConfig> configurations,
            TestResetStateRepository repository
    ) {
        return new WorkflowHarness(
                configurations,
                repository,
                new MutableClock(NOW, ZoneOffset.UTC)
        );
    }

    private static FarmworldResetConfig config(String farmworldKey, Duration interval) {
        return new FarmworldResetConfig(
                farmworldKey,
                "test_" + farmworldKey,
                true,
                interval
        );
    }

    private static FarmworldResetConfig endConfig(Duration interval) {
        return new FarmworldResetConfig(
                "end",
                "test_end",
                true,
                interval,
                FarmworldType.END,
                new PostResetConfig(
                        Map.of(),
                        Optional.empty(),
                        Optional.of(new EndPostResetConfig(false))
                )
        );
    }

    private static TestResetStateRepository repositoryWithDueState(String farmworldKey) {
        return new TestResetStateRepository(Map.of(
                farmworldKey,
                state(farmworldKey, NOW)
        ));
    }

    private static Map<String, FarmworldResetState> dueStates(String... farmworldKeys) {
        Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        for (String farmworldKey : farmworldKeys) {
            states.put(farmworldKey, state(farmworldKey, NOW));
        }
        return states;
    }

    private static FarmworldResetState state(String farmworldKey, Instant nextReset) {
        return new FarmworldResetState(farmworldKey, Optional.empty(), nextReset);
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("ResetWorkflowIntegrationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static Plugin plugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "FarmweltTest";
                    case "getLogger" -> logger;
                    case "toString" -> "FarmweltTestPlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
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
            FarmworldType type,
            String directory,
            long seed
    ) {
        World.Environment environment = switch (type) {
            case OVERWORLD -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        };
        File worldFolder = Path.of("server", "world", "workflow-tests").resolve(directory)
                .toAbsolutePath()
                .toFile();
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getEnvironment" -> environment;
                    case "getKey" -> new NamespacedKey("worlds", name);
                    case "getWorldFolder" -> worldFolder;
                    case "getSeed" -> seed;
                    case "toString" -> "FakeWorld[" + name + ", " + directory + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
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

    private final class WorkflowHarness {

        private final Logger logger = quietLogger();
        private final MutableClock clock;
        private final FarmworldResetService resetService;
        private final DirectScheduler scheduler = new DirectScheduler();
        private final FakeMinecraftRuntime runtime;
        private final FarmworldResetEngine engine;
        private final WorldsFarmworldLifecycleService adapter;
        private final RecordingGlobalRegionScheduler globalScheduler;
        private final AutomaticResetScheduler automaticScheduler;
        private final StartupResetCoordinator startupCoordinator;
        private final List<String> notifications = new ArrayList<>();
        private final List<PlayerMessage> playerNotifications = new ArrayList<>();
        private final List<String> notificationEvents = new ArrayList<>();

        private WorkflowHarness(
                List<FarmworldResetConfig> configurations,
                ResetStateRepository repository,
                MutableClock clock
        ) {
            this(configurations, repository, clock, null);
        }

        private WorkflowHarness(
                List<FarmworldResetConfig> configurations, ResetStateRepository repository,
                MutableClock clock, AtomicInteger worldsCalls
        ) {
            this.clock = clock;
            this.resetService = new FarmworldResetService(repository, dataDirectory, clock, logger);
            assertTrue(resetService.reload(configurations));
            this.runtime = new FakeMinecraftRuntime(configurations);
            ResetNotificationService notificationService = new ResetNotificationService(
                    resetService,
                    new ResetWarningTracker(),
                    message -> {
                        notifications.add(message);
                        notificationEvents.add("global:" + message);
                    },
                    (player, message) -> {
                        playerNotifications.add(new PlayerMessage(player, message));
                        notificationEvents.add("player:" + message);
                        return CompletableFuture.completedFuture(null);
                    },
                    ZoneOffset.UTC,
                    logger
            );
            this.adapter = worldsCalls == null ? null : countingWorldsAdapter(runtime, worldsCalls, scheduler, logger);
            this.engine = new FarmworldResetEngine(
                    resetService,
                    runtime,
                    adapter == null ? runtime : adapter,
                    runtime,
                    scheduler,
                    notificationService,
                    logger,
                    new EndDragonFightCompatibility(() -> "26.1.2"),
                    () -> Duration.between(NOW, clock.instant()).toNanos()
            );
            this.globalScheduler = new RecordingGlobalRegionScheduler();
            Plugin plugin = plugin(logger);
            this.automaticScheduler = new AutomaticResetScheduler(
                    plugin,
                    globalScheduler,
                    resetService,
                    engine,
                    notificationService,
                    clock
            );
            this.startupCoordinator = new StartupResetCoordinator(
                    plugin,
                    globalScheduler,
                    automaticScheduler,
                    clock
            );
        }
    }

    private static WorldsFarmworldLifecycleService countingWorldsAdapter(FakeMinecraftRuntime runtime, AtomicInteger calls,
                                                                        FarmweltScheduler scheduler, Logger logger) {
        WorldsAccess access = (WorldsAccess) Proxy.newProxyInstance(WorldsAccess.class.getClassLoader(),
                new Class<?>[]{WorldsAccess.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("regenerate")) {
                        throw new AssertionError(method.getName());
                    }
                    calls.incrementAndGet();
                    return runtime.regenerate((World) args[0], FarmworldRegenerationOptions.defaults());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorlds" -> List.of(world("world", FarmworldType.OVERWORLD, "main", 0L), runtime.activeWorlds.get("end"));
                    case "getWorld" -> runtime.activeWorlds.values().stream()
                            .filter(world -> world.getName().equals(args[0])).findFirst().orElse(null);
                    default -> throw new AssertionError(method.getName());
                });
        return new WorldsFarmworldLifecycleService(server, access, new EndDragonFightDataStore(4790),
                new EndDragonFightCompatibility(() -> "26.1.2"), scheduler, logger);
    }

    private record RegenerationCall(
            String farmworldKey,
            FarmworldRegenerationOptions options
    ) {
    }

    private static final class FakeMinecraftRuntime implements
            FarmworldWorldOperations,
            FarmworldLifecycleService,
            FarmworldPostResetInitializer {

        private final Map<String, FarmworldResetConfig> configurations = new LinkedHashMap<>();
        private final Map<String, World> activeWorlds = new LinkedHashMap<>();
        private final Map<World, String> farmworldKeys = new IdentityHashMap<>();
        private final Map<String, Deque<CompletableFuture<World>>> plannedRegenerations =
                new LinkedHashMap<>();
        private final Map<String, Integer> generations = new LinkedHashMap<>();
        private final Set<String> alwaysFailing = new java.util.HashSet<>();
        private final List<String> regenerationKeys = new ArrayList<>();
        private final List<RegenerationCall> regenerationCalls = new ArrayList<>();
        private final List<ResetOptions> postResetOptions = new ArrayList<>();
        private Runnable beforeInspection = () -> {};
        private Supplier<CompletableFuture<World>> nextWorldsCall;
        private String preflightFailure = "";
        private CompletableFuture<FarmworldEvacuationResult> nextEvacuation;
        private int evacuations;
        private int openedScopes;
        private int closedScopes;
        private CompletableFuture<PostResetResult> postResetResult = CompletableFuture.completedFuture(PostResetResult.success());
        private List<Player> evacuationPlayers = List.of();

        private FakeMinecraftRuntime(List<FarmworldResetConfig> configurations) {
            for (FarmworldResetConfig configuration : configurations) {
                this.configurations.put(configuration.farmworldKey(), configuration);
                World initialWorld = world(
                        configuration.worldName(),
                        configuration.farmworldType(),
                        configuration.farmworldKey() + "-initial",
                        1L
                );
                activeWorlds.put(configuration.farmworldKey(), initialWorld);
                farmworldKeys.put(initialWorld, configuration.farmworldKey());
                generations.put(configuration.farmworldKey(), 0);
            }
        }

        private CompletableFuture<World> defer(String farmworldKey) {
            CompletableFuture<World> future = new CompletableFuture<>();
            plannedRegenerations.computeIfAbsent(
                    farmworldKey,
                    ignored -> new ArrayDeque<>()
            ).addLast(future);
            return future;
        }

        private void complete(String farmworldKey, CompletableFuture<World> future) {
            future.complete(createRegeneratedWorld(farmworldKey));
        }

        private void failEveryRegeneration(String farmworldKey) {
            alwaysFailing.add(farmworldKey);
        }

        @Override
        public WorldInspection inspect(FarmworldResetConfig resetConfig) {
            beforeInspection.run();
            if (preflightFailure.equals("inspection")) {
                return WorldInspection.unloaded();
            }
            return WorldInspection.loaded(
                    activeWorlds.get(resetConfig.farmworldKey()),
                    resetConfig.farmworldType(),
                    false
            );
        }

        @Override
        public CompletableFuture<FarmworldEvacuationResult> evacuatePlayers(World resetWorld) {
            evacuations++;
            if (nextEvacuation != null) {
                var result = nextEvacuation;
                nextEvacuation = null;
                return result;
            }
            return CompletableFuture.completedFuture(
                    preflightFailure.equals("evacuation") ? FarmworldEvacuationResult.failed(List.of(), null)
                            : FarmworldEvacuationResult.completed(evacuationPlayers)
            );
        }

        @Override
        public ResetScope beginReset(FarmworldResetConfig config, ResetOptions options) {
            openedScopes++;
            return () -> closedScopes++;
        }

        @Override
        public boolean hasPlayers(World world) {
            return preflightFailure.equals("playersRemain");
        }

        @Override
        public CompletableFuture<World> regenerate(
                World world,
                FarmworldRegenerationOptions options
        ) {
            String farmworldKey = farmworldKeys.get(world);
            regenerationKeys.add(farmworldKey);
            regenerationCalls.add(new RegenerationCall(farmworldKey, options));
            if (nextWorldsCall != null) {
                var call = nextWorldsCall;
                nextWorldsCall = null;
                return call.get();
            }
            if (alwaysFailing.contains(farmworldKey)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulierter Worlds-Fehler")
                );
            }

            Deque<CompletableFuture<World>> planned = plannedRegenerations.get(farmworldKey);
            CompletableFuture<World> result = planned == null || planned.isEmpty()
                    ? CompletableFuture.completedFuture(createRegeneratedWorld(farmworldKey))
                    : planned.removeFirst();
            return result.thenApply(regeneratedWorld -> {
                activeWorlds.put(farmworldKey, regeneratedWorld);
                farmworldKeys.put(regeneratedWorld, farmworldKey);
                return regeneratedWorld;
            });
        }

        @Override
        public CompletableFuture<PostResetResult> apply(
                FarmworldResetConfig config,
                World regeneratedWorld,
                ResetOptions options
        ) {
            postResetOptions.add(options);
            return postResetResult;
        }

        private World createRegeneratedWorld(String farmworldKey) {
            FarmworldResetConfig configuration = configurations.get(farmworldKey);
            int generation = generations.compute(farmworldKey, (ignored, current) -> current + 1);
            return world(
                    configuration.worldName(),
                    configuration.farmworldType(),
                    farmworldKey + "-generation-" + generation,
                    generation + 1L
            );
        }
    }

    private record PlayerMessage(Player player, String message) {
    }

    static final class DirectScheduler implements FarmweltScheduler {
        private Runnable afterAsyncWork = () -> {};

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
            var result = execute(operation);
            afterAsyncWork.run();
            return result;
        }

        private <T> CompletableFuture<T> execute(CheckedSupplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (Exception exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }

    private static final class TestResetStateRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states;
        private int saveCount;

        private TestResetStateRepository(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
        }

        @Override
        public Map<String, FarmworldResetState> load() {
            return new LinkedHashMap<>(states);
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) {
            this.states = new LinkedHashMap<>(states);
            saveCount++;
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

    private static final class RecordingGlobalRegionScheduler implements GlobalRegionScheduler {

        private Runnable observation = () -> {};
        private long startupDelay;
        private int periodicTasks;
        private Consumer<ScheduledTask> startupAction;
        private Consumer<ScheduledTask> periodicAction;
        private RecordingScheduledTask startupTask;
        private RecordingScheduledTask periodicTask;

        @Override
        public void execute(Plugin plugin, Runnable run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long delayTicks
        ) {
            if (delayTicks != StartupResetCoordinator.STARTUP_DELAY_TICKS) {
                var timer = new RecordingScheduledTask(plugin, false);
                observation = () -> task.accept(timer);
                return timer;
            }
            startupDelay = delayTicks;
            startupAction = task;
            startupTask = new RecordingScheduledTask(plugin, false);
            return startupTask;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelayTicks,
                long periodTicks
        ) {
            periodicTasks++;
            periodicAction = task;
            periodicTask = new RecordingScheduledTask(plugin, true);
            return periodicTask;
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        private void runStartupTask() {
            startupAction.accept(startupTask);
        }

        private void tick() {
            periodicAction.accept(periodicTask);
        }
    }

    private static final class RecordingScheduledTask implements ScheduledTask {

        private final Plugin plugin;
        private final boolean repeating;
        private boolean cancelled;

        private RecordingScheduledTask(Plugin plugin, boolean repeating) {
            this.plugin = plugin;
            this.repeating = repeating;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
        }

        @Override
        public CancelledState cancel() {
            if (cancelled) {
                return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
            }
            cancelled = true;
            return CancelledState.NEXT_RUNS_CANCELLED;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }
}
