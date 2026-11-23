package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Executes one complete, asynchronous reset pipeline for a configured farmworld. */
public final class FarmworldResetEngine implements FarmworldResetExecutor {

    static final Duration OBSERVATION_INTERVAL = Duration.ofMinutes(10);
    private final LongSupplier nanoTime;
    private volatile boolean stopped;
    private final FarmworldResetService resetService;
    private final FarmworldWorldOperations worldOperations;
    private final FarmworldLifecycleService lifecycleService;
    private final FarmworldPostResetInitializer postResetInitializer;
    private final FarmweltScheduler scheduler;
    private final ResetNotificationService notificationService;
    private final Logger logger;
    private final EndDragonFightCompatibility dragonCompatibility;
    private final Map<FarmworldType, Reservation> runningResets = new EnumMap<>(FarmworldType.class);

    public FarmworldResetEngine(
            FarmworldResetService resetService,
            FarmworldWorldOperations worldOperations,
            FarmworldLifecycleService lifecycleService,
            FarmworldPostResetInitializer postResetInitializer,
            FarmweltScheduler scheduler,
            ResetNotificationService notificationService,
            Logger logger
    ) {
        this(resetService, worldOperations, lifecycleService, postResetInitializer, scheduler,
                notificationService, logger, EndDragonFightCompatibility.runningServer());
    }

    FarmworldResetEngine(
            FarmworldResetService resetService,
            FarmworldWorldOperations worldOperations,
            FarmworldLifecycleService lifecycleService,
            FarmworldPostResetInitializer postResetInitializer,
            FarmweltScheduler scheduler,
            ResetNotificationService notificationService,
            Logger logger,
            EndDragonFightCompatibility dragonCompatibility
    ) {
        this(resetService, worldOperations, lifecycleService, postResetInitializer, scheduler, notificationService,
                logger, dragonCompatibility, System::nanoTime);
    }

    FarmworldResetEngine(
            FarmworldResetService resetService,
            FarmworldWorldOperations worldOperations,
            FarmworldLifecycleService lifecycleService,
            FarmworldPostResetInitializer postResetInitializer,
            FarmweltScheduler scheduler,
            ResetNotificationService notificationService,
            Logger logger,
            EndDragonFightCompatibility dragonCompatibility, LongSupplier nanoTime
    ) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.dragonCompatibility = Objects.requireNonNull(dragonCompatibility, "dragonCompatibility");
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.worldOperations = Objects.requireNonNull(worldOperations, "worldOperations");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.postResetInitializer = Objects.requireNonNull(postResetInitializer, "postResetInitializer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.notificationService = Objects.requireNonNull(
                notificationService,
                "notificationService"
        );
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<ResetResult> reset(String farmworldKey) {
        return reset(farmworldKey, ResetOptions.defaults());
    }

    @Override
    public CompletableFuture<ResetResult> reset(String farmworldKey, ResetOptions options) {
        return startReset(farmworldKey, options, null, false);
    }

    @Override
    public CompletableFuture<ResetResult> resetIfDue(String farmworldKey, Instant evaluatedAt) {
        return startReset(farmworldKey, ResetOptions.defaults(), Objects.requireNonNull(evaluatedAt), false);
    }

    @Override
    public CompletableFuture<ResetResult> catchUpIfDue(String key, Instant evaluatedAt) {
        return startReset(key, ResetOptions.defaults(), Objects.requireNonNull(evaluatedAt), true);
    }

    private CompletableFuture<ResetResult> startReset(String farmworldKey, ResetOptions options, Instant dueAt,
                                                       boolean startup) {
        Objects.requireNonNull(options, "options");
        FarmworldType type = FarmworldType.fromFarmworldKey(farmworldKey).orElse(null);
        final Reservation reservation;
        final FarmworldResetConfig resetConfig;
        // Derselbe kurze Monitor schützt die Config-Veröffentlichung und die Reservierung beider Identitäten.
        synchronized (resetService) {
            resetConfig = resetService.getConfig(farmworldKey).orElse(null);
            String world = resetConfig == null ? "" : resetConfig.worldName();
            if (stopped) return rejected(farmworldKey, world, ResetStatus.DISABLED, "Farmwelt wird deaktiviert.");
            if (type == null) {
                return rejected(Objects.toString(farmworldKey, ""), world, ResetStatus.NOT_CONFIGURED,
                        "Die Farmwelt ist nicht konfiguriert.");
            }
            if (runningResets.containsKey(type) || targetReserved(world)) {
                return rejected(type.key(), world, ResetStatus.ALREADY_RUNNING, "Für diese Farmwelt läuft bereits ein Reset.");
            }
            if (resetConfig == null) {
                return rejected(type.key(), world, ResetStatus.NOT_CONFIGURED, "Die Farmwelt ist nicht konfiguriert.");
            }
            if (!resetConfig.enabled()) {
                return rejected(type.key(), world, ResetStatus.DISABLED, "Der Reset ist für diese Farmwelt deaktiviert.");
            }
            var blocker = conflictingHandoff(startup);
            if (blocker.isPresent()) return rejected(type.key(), world, ResetStatus.WORLDS_HANDOFF_DEFERRED, blocker.get());
            reservation = new Reservation(world, nanoTime.getAsLong(), startup);
            runningResets.put(type, reservation);
        }

        try {
            // Nur die interne Pipeline gibt ihre Reservierung frei, auch wenn ein Beobachter abbricht.
            return runReserved(resetConfig, options, dueAt, reservation)
                    .whenComplete((result, failure) -> release(type, reservation)).copy();
        } catch (RuntimeException | Error failure) {
            release(type, reservation);
            throw failure;
        }
    }

    private CompletableFuture<ResetResult> runReserved(FarmworldResetConfig resetConfig, ResetOptions options,
                                                        Instant dueAt, Reservation reservation) {
        String key = resetConfig.farmworldKey();
        Optional<String> recovery = resetService.recoveryProblem(resetConfig);
        if (recovery.isPresent()) {
            return rejected(key, resetConfig.worldName(), ResetStatus.RECOVERY_BLOCKED,
                    "Recovery-Sperre. " + recovery.orElseThrow());
        }
        // Zwischen Tick-Auswahl und Lock-Erwerb kann ein manueller Reset den Plan erfüllt haben.
        if (dueAt != null && resetService.getState(key).filter(state -> !dueAt.isBefore(state.nextReset())).isEmpty()) {
            return rejected(key, resetConfig.worldName(), ResetStatus.NOT_DUE, "Es liegt kein fälliger Reset-Termin vor.");
        }

        final FarmworldPostResetInitializer.ResetScope resetScope;
        try {
            requireActive();
            logger.info("Reset für Farmwelt '" + key + "' (" + resetConfig.worldName() + ") gestartet.");
            notificationService.broadcastResetStart(resetConfig);
            if (FarmworldRegenerationOptions.effectiveDragonPolicy(resetConfig, options)
                    != FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE) {
                try {
                    dragonCompatibility.requireSupported();
                } catch (RuntimeException | LinkageError exception) {
                    throw new ResetPipelineException(ResetStatus.UNSUPPORTED_DRAGON_VERSION,
                            exception.getMessage(), exception);
                }
            }
            requireActive();
            resetScope = Objects.requireNonNull(
                    postResetInitializer.beginReset(resetConfig, options),
                    "postResetInitializer.beginReset(...)"
            );
        } catch (RuntimeException | LinkageError exception) {
            return CompletableFuture.completedFuture(finishAndNotify(resetConfig, null, exception));
        }

        CompletableFuture<ResetResult> resetFuture;
        try {
            resetFuture = execute(resetConfig, options, reservation);
        } catch (RuntimeException | LinkageError exception) {
            resetFuture = CompletableFuture.failedFuture(exception);
        }

        return resetFuture.handle((result, failure) -> finishAndNotify(resetConfig, result, failure))
                .whenComplete((result, failure) -> closeScope(resetConfig, resetScope));
    }

    private void closeScope(FarmworldResetConfig resetConfig, FarmworldPostResetInitializer.ResetScope resetScope) {
        try {
            resetScope.close();
        } catch (RuntimeException | LinkageError exception) {
            logger.log(Level.SEVERE, "Temporäre Dragon-Policy für '" + resetConfig.worldName()
                    + "' konnte nicht bereinigt werden.", exception);
        }
    }

    private static CompletableFuture<ResetResult> rejected(String key, String world, ResetStatus status, String message) {
        return CompletableFuture.completedFuture(new ResetResult(key, world, status, message, null));
    }

    private static final class Reservation {
        final String worldName;
        final long started;
        final boolean startup;
        volatile String phase = "Weltprüfung (Global)";
        volatile ResetAttempt attempt;
        volatile FarmworldLifecycleService.BackupCleanup backupCleanup;
        volatile java.nio.file.Path validatedFolder;
        volatile WorldsHandoff worlds = WorldsHandoff.NOT_STARTED;
        volatile boolean teleportUnconfirmed;
        boolean warned;

        Reservation(String worldName, long started, boolean startup) {
            this.startup = startup;
            this.worldName = worldName;
            this.started = started;
        }
    }

    private enum WorldsHandoff {
        NOT_STARTED, ACCEPTED, RETURNED, UNCONFIRMED;

        boolean lacksCompletion() {
            return this == ACCEPTED || this == UNCONFIRMED;
        }
    }

    public void stop() {
        synchronized (resetService) {
            stopped = true;
        }
    }

    private Optional<String> conflictingHandoff(boolean startup) {
        long now = nanoTime.getAsLong();
        return runningResets.values().stream()
                .filter(r -> r.worlds == WorldsHandoff.UNCONFIRMED || (r.worlds == WorldsHandoff.ACCEPTED
                        && (startup || r.startup || now - r.started >= OBSERVATION_INTERVAL.toNanos())))
                .map(r -> "Worlds-Ergebnis für " + r.worldName + " offen. Weitere Übergaben zurückgestellt.")
                .findFirst();
    }

    @Override
    public Optional<String> runningDiagnosis(String key) {
        synchronized (resetService) {
            return FarmworldType.fromFarmworldKey(key).map(runningResets::get).map(this::diagnosis)
                    .or(() -> conflictingHandoff(false));
        }
    }

    private String diagnosis(Reservation r) {
        long seconds = Duration.ofNanos(nanoTime.getAsLong() - r.started).toSeconds();
        return r.worldName + ", Versuch " + (r.attempt == null ? "in Vorbereitung" : r.attempt.id())
                + ", seit " + seconds + " s. Phase: " + r.phase + ". Reservierung bleibt bestehen.";
    }

    @Override
    public void diagnoseRunningResets() {
        synchronized (resetService) {
            long now = nanoTime.getAsLong();
            for (var entry : runningResets.entrySet()) {
                var r = entry.getValue();
                long elapsed = now - r.started;
                if (elapsed < OBSERVATION_INTERVAL.toNanos() || r.warned) continue;
                r.warned = true;
                logger.warning("Reset " + entry.getKey().key() + ": " + diagnosis(r) + (r.worlds.lacksCompletion()
                        ? " Weitere Worlds-Übergaben warten auf das Ergebnis." : ""));
            }
        }
    }

    private void requireActive() {
        if (stopped) throw new ResetPipelineException(ResetStatus.DISABLED, "Farmwelt wird deaktiviert.", null);
    }

    private void release(FarmworldType type, Reservation owner) {
        synchronized (resetService) {
            if (runningResets.get(type) == owner && !owner.teleportUnconfirmed
                    && !owner.worlds.lacksCompletion()) {
                runningResets.remove(type);
            }
        }
    }

    private boolean targetReserved(String worldName) {
        return runningResets.values().stream().anyMatch(r -> r.worldName.equalsIgnoreCase(worldName));
    }

    public boolean isResetRunning(String farmworldKey) {
        synchronized (resetService) {
            return runningResets.containsKey(FarmworldType.fromFarmworldKey(farmworldKey).orElse(null))
                    || resetService.getConfig(farmworldKey).map(config -> targetReserved(config.worldName())).orElse(false);
        }
    }

    public Set<String> getRunningResets() {
        synchronized (resetService) {
            Set<String> keys = new HashSet<>();
            runningResets.keySet().forEach(type -> keys.add(type.key()));
            return Set.copyOf(keys);
        }
    }

    public Set<String> getResetWorldNames() {
        synchronized (resetService) {
            Set<String> names = new HashSet<>();
            resetService.getConfiguredWorlds().forEach(config -> names.add(config.worldName()));
            runningResets.values().forEach(reservation -> names.add(reservation.worldName));
            return Set.copyOf(names);
        }
    }

    @Override
    public boolean isFarmworldAvailable(String farmworldKey) {
        return !isResetRunning(farmworldKey);
    }

    private CompletableFuture<ResetResult> execute(
            FarmworldResetConfig resetConfig,
            ResetOptions options, Reservation reservation
    ) {
        ResetAttempt attempt = resetService.newAttempt(resetConfig);
        reservation.attempt = attempt;
        return validateWorld(resetConfig, options)
                .thenCompose(context -> {
                    reservation.phase = "Evakuierung (Entity/teleportAsync)";
                    return evacuatePlayers(context, reservation);
                })
                .thenCompose(this::confirmWorldIsEmpty)
                .thenCompose(context -> {
                    reservation.phase = "Journal und letzte Leerprüfung (Async/Global)";
                    return prepareHandoff(context, attempt, reservation);
                })
                .thenCompose(context -> checkpoint(() -> resetService.worldsReturned(resetConfig, attempt))
                        .thenCompose(confirmed -> initializeAndRecord(context, confirmed, reservation)))
                .thenCompose(completed -> {
                    reservation.phase = "Zeitplan speichern (Async)";
                    return persistState(resetConfig, completed, reservation);
                })
                .thenApply(state -> new ResetResult(
                        resetConfig.farmworldKey(),
                        resetConfig.worldName(),
                        ResetStatus.SUCCESS,
                        "Die Farmwelt wurde erfolgreich zurückgesetzt.",
                        null
                ));
    }

    private CompletableFuture<PipelineContext> validateWorld(
            FarmworldResetConfig resetConfig,
            ResetOptions options
    ) {
        return mapOperationFailure(() -> scheduler.runGlobal(() -> inspectOriginal(resetConfig, options)),
                ResetStatus.INVALID_CONFIGURATION, "Die konfigurierte Welt konnte nicht geprüft werden.");
    }

    private PipelineContext inspectOriginal(FarmworldResetConfig resetConfig, ResetOptions options) {
        WorldInspection inspection = worldOperations.inspect(resetConfig);
        if (inspection == null) {
            throw new ResetPipelineException(
                    ResetStatus.INVALID_CONFIGURATION,
                    "Die Prüfung der konfigurierten Welt hat kein Ergebnis geliefert.",
                    null
            );
        }
        if (!inspection.loaded()) {
            throw new ResetPipelineException(
                    ResetStatus.WORLD_NOT_LOADED,
                    "Die Farmwelt ist derzeit nicht geladen und kann nicht sicher zurückgesetzt werden.",
                    null
            );
        }

        World world = inspection.loadedWorld().orElseThrow();
        if (!world.getName().equals(resetConfig.worldName())) {
            throw new ResetPipelineException(
                    ResetStatus.INVALID_CONFIGURATION,
                    "Der tatsächliche Bukkit-Weltname stimmt nicht mit der Reset-Konfiguration überein.",
                    null
            );
        }
        if (inspection.protectedMainWorld()) {
            throw new ResetPipelineException(
                    ResetStatus.PROTECTED_WORLD,
                    "Die konfigurierte Welt ist als Hauptwelt geschützt und darf nicht zurückgesetzt werden.",
                    null
            );
        }
        if (inspection.loadedWorldType().orElseThrow() != resetConfig.farmworldType()) {
            throw new ResetPipelineException(
                    ResetStatus.INVALID_CONFIGURATION,
                    "Die Dimension der geladenen Welt passt nicht zur logischen Farmwelt-ID.",
                    null
            );
        }

        logger.info("Geladene Bukkit-Welt '" + world.getName() + "' gefunden.");
        long oldSeed = world.getSeed();
        logger.info("Alter Seed: " + oldSeed);
        return new PipelineContext(
                resetConfig,
                options,
                world,
                oldSeed
        );
    }

    private CompletableFuture<PipelineContext> evacuatePlayers(PipelineContext context, Reservation reservation) {
        CompletableFuture<FarmworldEvacuationResult> evacuation = mapOperationFailure(
                () -> scheduler.runGlobal(
                                () -> worldOperations.evacuatePlayers(context.originalWorld())
                        )
                        .thenCompose(Function.identity()),
                ResetStatus.EVACUATION_FAILED,
                "Mindestens ein Spieler konnte nicht sicher evakuiert werden."
        );

        return evacuation.thenCompose(result -> {
            if (result == null) {
                return failed(
                        ResetStatus.EVACUATION_FAILED,
                        "Die Spieler-Evakuierung hat kein Ergebnis geliefert.",
                        null
                );
            }
            if (result.failure().orElse(null) instanceof FarmworldEvacuationResult.UnconfirmedTeleportException) {
                reservation.teleportUnconfirmed = true;
                reservation.phase = "Teleportende ungeklärt. Ziel bleibt bis zum Serverneustart reserviert";
            }
            requireActive();
            for (Player player : result.evacuatedPlayers()) {
                notificationService.sendEvacuationMessage(context.resetConfig(), player);
            }
            if (!result.successful()) {
                return failed(
                        ResetStatus.EVACUATION_FAILED,
                        "Mindestens ein Spieler konnte nicht sicher evakuiert werden.",
                        result.failure().orElse(null)
                );
            }
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<PipelineContext> confirmWorldIsEmpty(PipelineContext context) {
        return mapOperationFailure(
                () -> scheduler.runGlobal(
                        () -> worldOperations.hasPlayers(context.originalWorld())
                ),
                ResetStatus.EVACUATION_FAILED,
                "Die Spielerfreiheit der Farmwelt konnte nicht bestätigt werden."
        ).thenCompose(hasPlayers -> {
            if (hasPlayers == null) {
                return failed(
                        ResetStatus.EVACUATION_FAILED,
                        "Die Spielerfreiheit der Farmwelt konnte nicht bestätigt werden.",
                        null
                );
            }
            if (hasPlayers) {
                return failed(
                        ResetStatus.EVACUATION_FAILED,
                        "Nach der Evakuierung befinden sich weiterhin Spieler in der Farmwelt.",
                        null
                );
            }
            logger.info("Spieler aus '" + context.resetConfig().worldName() + "' evakuiert.");
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<RegeneratedContext> prepareHandoff(PipelineContext context, ResetAttempt attempt, Reservation reservation) {
        FarmworldResetConfig config = context.resetConfig();
        return checkpoint(() -> resetService.prepare(config, attempt)).thenCompose(rollback ->
                recheckHandoff(context).thenCompose(checked -> regenerateWorld(checked, reservation))
                        .exceptionallyCompose(failure -> {
                            if (reservation.worlds != WorldsHandoff.NOT_STARTED) {
                                return CompletableFuture.failedFuture(failure);
                            }
                            return checkpoint(() -> {
                                rollback.restorePrevious();
                                return context;
                            }).thenCompose(ignored -> CompletableFuture.failedFuture(failure));
                        }));
    }

    private CompletableFuture<PipelineContext> recheckHandoff(PipelineContext context) {
        // Der Journal-Flush kann dauern. Währenddessen können sich Weltzuordnung und Belegung ändern.
        return mapOperationFailure(() -> scheduler.runGlobal(() -> {
            WorldInspection current = worldOperations.inspect(context.resetConfig());
            if (current == null || current.loadedWorld().orElse(null) != context.originalWorld()
                    || current.protectedMainWorld()
                    || current.loadedWorldType().orElse(null) != context.resetConfig().farmworldType()) {
                throw new ResetPipelineException(ResetStatus.INVALID_CONFIGURATION,
                        "Die Farmwelt ist nach der Journal-Sicherung kein sicheres Reset-Ziel mehr.", null);
            }
            if (worldOperations.hasPlayers(context.originalWorld())) {
                throw new ResetPipelineException(ResetStatus.EVACUATION_FAILED,
                        "Die Farmwelt ist nach der Journal-Sicherung wieder belegt.", null);
            }
            return context;
        }), ResetStatus.INVALID_CONFIGURATION, "Die letzte Prüfung vor der Worlds-Übergabe ist fehlgeschlagen.");
    }

    private CompletableFuture<RegeneratedContext> regenerateWorld(PipelineContext context, Reservation reservation) {
        var options = FarmworldRegenerationOptions.forReset(context.resetConfig(), context.options());
        synchronized (resetService) {
            requireActive();
            conflictingHandoff(reservation.startup).ifPresent(reason -> {
                throw new ResetPipelineException(ResetStatus.WORLDS_HANDOFF_DEFERRED, reason, null);
            });
            // Wer vor stop() gewinnt, besitzt genau diesen Aufruf unwiderruflich.
            reservation.phase = "Worlds.regenerate";
            reservation.worlds = WorldsHandoff.ACCEPTED;
        }
        final CompletableFuture<World> observed;
        try {
            var worlds = Objects.requireNonNull(lifecycleService.regenerate(context.originalWorld(), options,
                            reservation.attempt.id(), cleanup -> reservation.backupCleanup = cleanup),
                    "Worlds hat kein Future geliefert.");
            observed = Objects.requireNonNull(worlds.handle((world, failure) ->
                    observeWorldsResult(reservation, world, failure)), "Worlds-Beobachter fehlt.");
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.failedFuture(unconfirmedWorlds(reservation, failure));
        }
        return observed.thenCompose(regeneratedWorld -> {
            requireActive();
            if (regeneratedWorld == null) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Worlds hat keine regenerierte Bukkit-Welt geliefert.",
                        null
                );
            }
            if (regeneratedWorld == context.originalWorld()) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Worlds hat weiterhin die veraltete Bukkit-Weltinstanz geliefert.",
                        null
                );
            }

            logger.info("Worlds hat Farmwelt '" + context.resetConfig().worldName()
                    + "' erfolgreich regeneriert.");
            return CompletableFuture.completedFuture(new RegeneratedContext(
                    context.resetConfig(),
                    context.options(),
                    context.oldSeed(),
                    regeneratedWorld
            ));
        });
    }

    private World observeWorldsResult(Reservation reservation, World world, Throwable failure) {
        if (FarmworldLifecycleService.observationLost(failure)) {
            throw unconfirmedWorlds(reservation, unwrap(failure));
        }
        synchronized (resetService) {
            if (reservation.worlds == WorldsHandoff.ACCEPTED) {
                reservation.phase = "Worlds-Rückkehr, Validierung und Post-Reset";
                reservation.worlds = WorldsHandoff.RETURNED;
            }
        }
        if (failure != null) {
            throw new ResetPipelineException(ResetStatus.REGENERATE_FAILED,
                    "Worlds konnte die Farmwelt nicht regenerieren.", unwrap(failure));
        }
        return world;
    }

    private ResetPipelineException unconfirmedWorlds(Reservation reservation, Throwable cause) {
        String message = "Worlds-Abschluss ungeklärt. Weitere Übergaben bis zum Serverneustart gesperrt.";
        synchronized (resetService) {
            reservation.phase = message;
            reservation.worlds = WorldsHandoff.UNCONFIRMED;
        }
        return new ResetPipelineException(ResetStatus.REGENERATE_FAILED, message, cause);
    }

    private CompletableFuture<RegeneratedContext> applyPostReset(RegeneratedContext context) {
        return mapOperationFailure(
                () -> postResetInitializer.apply(
                        context.resetConfig(),
                        context.regeneratedWorld(),
                        context.options()
                ),
                ResetStatus.POST_RESET_FAILED,
                "Die Farmwelt wurde neu erstellt, konnte aber nicht vollst\u00e4ndig initialisiert werden."
        ).thenCompose(result -> {
            if (result == null) {
                return failed(
                        ResetStatus.POST_RESET_FAILED,
                        "Die Post-Reset-Initialisierung hat kein Ergebnis geliefert.",
                        null
                );
            }
            if (!result.successful()) {
                return failed(
                        ResetStatus.POST_RESET_FAILED,
                        result.message(),
                        result.cause().orElse(null)
                );
            }
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<RegeneratedContext> validateRegeneratedWorld(
            RegeneratedContext context, Reservation reservation
    ) {
        return mapOperationFailure(() -> scheduler.runGlobal(() -> inspectRegenerated(context, reservation)),
                ResetStatus.REGENERATE_FAILED, "Die von Worlds regenerierte Bukkit-Welt konnte nicht geprüft werden.");
    }

    private RegeneratedContext inspectRegenerated(RegeneratedContext context, Reservation reservation) {
        WorldInspection inspection = worldOperations.inspect(context.resetConfig());
        if (inspection == null) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die Prüfung der regenerierten Bukkit-Welt hat kein Ergebnis geliefert.",
                    null
            );
        }
        if (!inspection.loaded()) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die von Worlds regenerierte Welt ist über Bukkit nicht geladen.",
                    null
            );
        }

        World bukkitWorld = inspection.loadedWorld().orElseThrow();
        if (bukkitWorld != context.regeneratedWorld()) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die von Worlds gelieferte Welt ist nicht die über Bukkit erreichbare Weltinstanz.",
                    null
            );
        }
        if (!bukkitWorld.getName().equals(context.resetConfig().worldName())) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die regenerierte Bukkit-Welt hat einen unerwarteten Namen.",
                    null
            );
        }
        if (inspection.protectedMainWorld()) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die regenerierte Welt wurde unerwartet als geschützte Hauptwelt erkannt.",
                    null
            );
        }
        if (inspection.loadedWorldType().orElseThrow()
                != context.resetConfig().farmworldType()) {
            throw new ResetPipelineException(
                    ResetStatus.REGENERATE_FAILED,
                    "Die regenerierte Bukkit-Welt hat eine unerwartete Dimension.",
                    null
            );
        }

        logger.info("Neue Bukkit-Welt '" + bukkitWorld.getName() + "' validiert.");
        long newSeed = context.regeneratedWorld().getSeed();
        logger.info("Neuer Seed: " + newSeed);
        if (context.oldSeed() == newSeed) {
            logger.warning("Der zufällig erzeugte Seed entspricht dem vorherigen Seed.");
        }
        reservation.validatedFolder = bukkitWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
        logger.info("Neuer Weltordner: " + reservation.validatedFolder);
        return context;
    }

    private CompletableFuture<ResetAttempt> initializeAndRecord(RegeneratedContext context, ResetAttempt confirmed,
                                                                Reservation reservation) {
        return validateRegeneratedWorld(context, reservation)
                .thenCompose(this::applyPostReset)
                .thenCompose(initialized -> checkpoint(() -> resetService.postResetDone(initialized.resetConfig(), confirmed)));
    }

    private <T> CompletableFuture<T> checkpoint(FarmweltScheduler.CheckedSupplier<T> operation) {
        return mapOperationFailure(() -> scheduler.runAsync(operation), ResetStatus.RECOVERY_BLOCKED,
                "Recovery-Phase konnte nicht sicher gespeichert werden. Journal und Serverlog prüfen.")
                .thenCompose(value -> value == null
                        ? failed(ResetStatus.RECOVERY_BLOCKED, "Die Recovery-Bestätigung fehlt.", null)
                        : CompletableFuture.completedFuture(value));
    }

    private CompletableFuture<FarmworldResetState> persistState(FarmworldResetConfig config, ResetAttempt attempt,
                                                                Reservation reservation) {
        return mapOperationFailure(
                () -> scheduler.runAsync(() -> {
                    var state = resetService.completeReset(config, attempt);
                    if (state != null && attempt.confirmedBy(state) && reservation.backupCleanup != null) {
                        try {
                            reservation.backupCleanup.run(reservation.validatedFolder);
                        } catch (java.io.IOException | RuntimeException failure) {
                            logger.log(Level.WARNING, "Reset bestätigt, DragonBattle-Cleanup offen. "
                                    + attempt.diagnosis(), failure);
                        }
                    }
                    return state;
                }),
                ResetStatus.STATE_SAVE_FAILED,
                "Der Weltreset war erfolgreich, aber der Reset-State konnte nicht gespeichert werden."
        ).thenCompose(state -> {
            if (state == null) {
                return failed(
                        ResetStatus.STATE_SAVE_FAILED,
                        "Der Weltreset war erfolgreich, aber der Reset-State lieferte kein Ergebnis.",
                        null
                );
            }
            return CompletableFuture.completedFuture(state);
        });
    }

    private <T> CompletableFuture<T> mapOperationFailure(
            Supplier<CompletableFuture<T>> operationSupplier,
            ResetStatus status,
            String message
    ) {
        final CompletableFuture<T> operation;
        try {
            requireActive();
            operation = Objects.requireNonNull(
                    operationSupplier.get(),
                    "Externe Operation hat kein Future geliefert."
            );
        } catch (RuntimeException | LinkageError exception) {
            if (exception instanceof ResetPipelineException pipeline) return CompletableFuture.failedFuture(pipeline);
            return failed(status, message, exception);
        }

        return operation.handle((value, failure) -> {
            if (failure == null) {
                return value;
            }

            Throwable cause = unwrap(failure);
            if (cause instanceof ResetPipelineException resetFailure) {
                throw new CompletionException(resetFailure);
            }
            throw new CompletionException(new ResetPipelineException(status, message, cause));
        });
    }

    private <T> CompletableFuture<T> failed(ResetStatus status, String message, Throwable cause) {
        return CompletableFuture.failedFuture(new ResetPipelineException(status, message, cause));
    }

    private ResetResult finish(
            FarmworldResetConfig resetConfig,
            ResetResult result,
            Throwable failure
    ) {
        if (failure == null) {
            FarmworldResetState state = resetService.getState(resetConfig.farmworldKey()).orElse(null);
            logger.info("Reset für Farmwelt '" + resetConfig.farmworldKey()
                    + "' erfolgreich abgeschlossen. Nächster Reset: "
                    + (state == null ? "unbekannt" : state.nextReset()));
            return result;
        }

        Throwable cause = unwrap(failure);
        ResetStatus status = ResetStatus.INTERNAL_ERROR;
        String message = "Der Reset ist durch einen internen Fehler fehlgeschlagen.";
        Throwable reportedCause = cause;
        if (cause instanceof ResetPipelineException resetFailure) {
            status = resetFailure.status;
            message = resetFailure.getMessage();
            reportedCause = resetFailure.getCause();
        }

        String logMessage = "Reset für Farmwelt '" + resetConfig.farmworldKey()
                + "' abgebrochen: " + message
                + resetService.recoveryProblem(resetConfig).map(reason -> " Recovery-Sperre. " + reason).orElse("");
        if (status == ResetStatus.REGENERATE_FAILED
                || status == ResetStatus.POST_RESET_FAILED
                || status == ResetStatus.STATE_SAVE_FAILED) {
            if (reportedCause == null) {
                logger.severe(logMessage);
            } else {
                logger.log(Level.SEVERE, logMessage, reportedCause);
            }
        } else if (reportedCause == null) {
            logger.warning(logMessage);
        } else {
            logger.log(Level.WARNING, logMessage, reportedCause);
        }

        return new ResetResult(
                resetConfig.farmworldKey(),
                resetConfig.worldName(),
                status,
                message,
                reportedCause
        );
    }

    private ResetResult finishAndNotify(
            FarmworldResetConfig resetConfig,
            ResetResult result,
            Throwable failure
    ) {
        ResetResult completedResult = finish(resetConfig, result, failure);
        if (!stopped) notificationService.broadcastResetResult(resetConfig, completedResult);
        return completedResult;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PipelineContext(
            FarmworldResetConfig resetConfig,
            ResetOptions options,
            World originalWorld,
            long oldSeed
    ) {
    }

    private record RegeneratedContext(
            FarmworldResetConfig resetConfig,
            ResetOptions options,
            long oldSeed,
            World regeneratedWorld
    ) {
    }

    private static final class ResetPipelineException extends RuntimeException {

        private final ResetStatus status;

        private ResetPipelineException(ResetStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
    }
}
