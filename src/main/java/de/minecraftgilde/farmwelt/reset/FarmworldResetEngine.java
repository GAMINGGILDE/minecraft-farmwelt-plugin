package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Executes one complete, asynchronous reset pipeline for a configured farmworld. */
public final class FarmworldResetEngine implements FarmworldResetExecutor {

    private final FarmworldResetService resetService;
    private final FarmworldWorldOperations worldOperations;
    private final FarmworldLifecycleService lifecycleService;
    private final FarmworldPostResetInitializer postResetInitializer;
    private final FarmweltScheduler scheduler;
    private final ResetNotificationService notificationService;
    private final Logger logger;
    private final EndDragonFightCompatibility dragonCompatibility;
    private final Set<String> runningResets = ConcurrentHashMap.newKeySet();

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
        return startReset(farmworldKey, options, null);
    }

    @Override
    public CompletableFuture<ResetResult> resetIfDue(String farmworldKey, Instant evaluatedAt) {
        return startReset(farmworldKey, ResetOptions.defaults(), Objects.requireNonNull(evaluatedAt));
    }

    private CompletableFuture<ResetResult> startReset(String farmworldKey, ResetOptions options, Instant dueAt) {
        Objects.requireNonNull(options, "options");
        if (farmworldKey == null || farmworldKey.isBlank()) {
            return CompletableFuture.completedFuture(new ResetResult(
                    farmworldKey == null ? "" : farmworldKey,
                    "",
                    ResetStatus.NOT_CONFIGURED,
                    "Die Farmwelt ist nicht konfiguriert.",
                    null
            ));
        }

        if (!runningResets.add(farmworldKey)) {
            String worldName = resetService.getConfig(farmworldKey)
                    .map(FarmworldResetConfig::worldName)
                    .orElse("");
            return CompletableFuture.completedFuture(new ResetResult(
                    farmworldKey,
                    worldName,
                    ResetStatus.ALREADY_RUNNING,
                    "Für diese Farmwelt läuft bereits ein Reset.",
                    null
            ));
        }

        Optional<FarmworldResetConfig> configuredReset = resetService.getConfig(farmworldKey);
        if (configuredReset.isEmpty()) {
            runningResets.remove(farmworldKey);
            return CompletableFuture.completedFuture(new ResetResult(
                    farmworldKey,
                    "",
                    ResetStatus.NOT_CONFIGURED,
                    "Die Farmwelt ist nicht konfiguriert.",
                    null
            ));
        }

        FarmworldResetConfig resetConfig = configuredReset.orElseThrow();
        if (!resetConfig.enabled()) {
            runningResets.remove(farmworldKey);
            return CompletableFuture.completedFuture(new ResetResult(
                    farmworldKey,
                    resetConfig.worldName(),
                    ResetStatus.DISABLED,
                    "Der Reset ist für diese Farmwelt deaktiviert.",
                    null
            ));
        }

        Optional<String> recovery = resetService.recoveryProblem(resetConfig);
        if (recovery.isPresent()) {
            runningResets.remove(farmworldKey);
            return CompletableFuture.completedFuture(new ResetResult(farmworldKey, resetConfig.worldName(),
                    ResetStatus.RECOVERY_BLOCKED, "Recovery-Sperre. " + recovery.orElseThrow(), null));
        }

        // Zwischen Tick-Auswahl und Lock-Erwerb kann ein manueller Reset den Plan erfüllt haben.
        if (dueAt != null && resetService.getState(farmworldKey)
                .filter(state -> !dueAt.isBefore(state.nextReset())).isEmpty()) {
            runningResets.remove(farmworldKey);
            return CompletableFuture.completedFuture(new ResetResult(farmworldKey, resetConfig.worldName(),
                    ResetStatus.NOT_DUE, "Es liegt kein fälliger Reset-Termin vor.", null));
        }

        logger.info("Reset für Farmwelt '" + farmworldKey + "' ("
                + resetConfig.worldName() + ") gestartet.");
        notificationService.broadcastResetStart(resetConfig);

        final FarmworldPostResetInitializer.ResetScope resetScope;
        try {
            if (FarmworldRegenerationOptions.effectiveDragonPolicy(resetConfig, options)
                    != FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE) {
                try {
                    dragonCompatibility.requireSupported();
                } catch (RuntimeException exception) {
                    throw new ResetPipelineException(ResetStatus.UNSUPPORTED_DRAGON_VERSION,
                            exception.getMessage(), exception);
                }
            }
            resetScope = Objects.requireNonNull(
                    postResetInitializer.beginReset(resetConfig, options),
                    "postResetInitializer.beginReset(...)"
            );
        } catch (RuntimeException exception) {
            runningResets.remove(farmworldKey);
            return CompletableFuture.completedFuture(finishAndNotify(
                    resetConfig,
                    null,
                    exception
            ));
        }

        CompletableFuture<ResetResult> resetFuture;
        try {
            resetFuture = execute(resetConfig, options)
                    .handle((result, failure) -> finishAndNotify(
                            resetConfig,
                            result,
                            failure
                    ));
        } catch (RuntimeException exception) {
            resetFuture = CompletableFuture.completedFuture(finishAndNotify(
                    resetConfig,
                    null,
                    exception
            ));
        }

        // Ein abgebrochener Beobachter darf die Freigabe nach dem tatsächlichen Abschluss nicht verhindern.
        return resetFuture.whenComplete((result, failure) -> {
            try {
                resetScope.close();
            } catch (RuntimeException exception) {
                logger.log(
                        Level.SEVERE,
                        "Tempor\u00e4re Dragon-Policy f\u00fcr '" + resetConfig.worldName()
                                + "' konnte nicht bereinigt werden.",
                        exception
                );
            } finally {
                runningResets.remove(farmworldKey);
            }
        }).copy();
    }

    public boolean isResetRunning(String farmworldKey) {
        return farmworldKey != null && runningResets.contains(farmworldKey);
    }

    public Set<String> getRunningResets() {
        return Set.copyOf(runningResets);
    }

    @Override
    public boolean isFarmworldAvailable(String farmworldKey) {
        return !isResetRunning(farmworldKey);
    }

    private CompletableFuture<ResetResult> execute(
            FarmworldResetConfig resetConfig,
            ResetOptions options
    ) {
        ResetAttempt attempt = resetService.newAttempt(resetConfig);
        return validateWorld(resetConfig, options)
                .thenCompose(this::evacuatePlayers)
                .thenCompose(this::confirmWorldIsEmpty)
                .thenCompose(context -> prepareHandoff(context, attempt))
                .thenCompose(this::regenerateWorld)
                .thenCompose(context -> checkpoint(() -> resetService.worldsReturned(resetConfig, attempt))
                        .thenCompose(confirmed -> initializeAndRecord(context, confirmed)))
                .thenCompose(completed -> persistState(resetConfig, completed))
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
        return mapOperationFailure(
                () -> scheduler.runGlobal(() -> worldOperations.inspect(resetConfig)),
                ResetStatus.INVALID_CONFIGURATION,
                "Die konfigurierte Welt konnte nicht geprüft werden."
        ).thenCompose(inspection -> {
            if (inspection == null) {
                return failed(
                        ResetStatus.INVALID_CONFIGURATION,
                        "Die Prüfung der konfigurierten Welt hat kein Ergebnis geliefert.",
                        null
                );
            }
            if (!inspection.loaded()) {
                return failed(
                        ResetStatus.WORLD_NOT_LOADED,
                        "Die Farmwelt ist derzeit nicht geladen und kann nicht sicher zurückgesetzt werden.",
                        null
                );
            }

            World world = inspection.loadedWorld().orElseThrow();
            if (!world.getName().equals(resetConfig.worldName())) {
                return failed(
                        ResetStatus.INVALID_CONFIGURATION,
                        "Der tatsächliche Bukkit-Weltname stimmt nicht mit der Reset-Konfiguration überein.",
                        null
                );
            }
            if (inspection.protectedMainWorld()) {
                return failed(
                        ResetStatus.PROTECTED_WORLD,
                        "Die konfigurierte Welt ist als Hauptwelt geschützt und darf nicht zurückgesetzt werden.",
                        null
                );
            }
            if (inspection.loadedWorldType().orElseThrow() != resetConfig.farmworldType()) {
                return failed(
                        ResetStatus.INVALID_CONFIGURATION,
                        "Die Dimension der geladenen Welt passt nicht zur logischen Farmwelt-ID.",
                        null
                );
            }

            logger.info("Geladene Bukkit-Welt '" + world.getName() + "' gefunden.");
            long oldSeed = world.getSeed();
            logger.info("Alter Seed: " + oldSeed);
            return CompletableFuture.completedFuture(new PipelineContext(
                    resetConfig,
                    options,
                    world,
                    oldSeed
            ));
        });
    }

    private CompletableFuture<PipelineContext> evacuatePlayers(PipelineContext context) {
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

    private CompletableFuture<PipelineContext> prepareHandoff(PipelineContext context, ResetAttempt attempt) {
        FarmworldResetConfig config = context.resetConfig();
        return checkpoint(() -> resetService.prepare(config, attempt)).thenCompose(rollback ->
                // Nur dieser Fehlerzweig liegt beweisbar vor dem Aufruf von regenerateWorld.
                recheckHandoff(context).exceptionallyCompose(failure ->
                        checkpoint(() -> {
                            rollback.restorePrevious();
                            return context;
                        }).thenCompose(ignored -> CompletableFuture.failedFuture(failure))));
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

    private CompletableFuture<RegeneratedContext> regenerateWorld(PipelineContext context) {
        logger.info("Regeneration von '" + context.resetConfig().worldName()
                + "' über Worlds mit zufälligem Seed gestartet.");

        return mapOperationFailure(
                // Worlds owns Folia/global scheduling for its complete lifecycle operation.
                () -> lifecycleService.regenerate(
                        context.originalWorld(),
                        FarmworldRegenerationOptions.forReset(
                                context.resetConfig(),
                                context.options()
                        )
                ),
                ResetStatus.REGENERATE_FAILED,
                "Worlds konnte die Farmwelt nicht regenerieren."
        ).thenCompose(regeneratedWorld -> {
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
            RegeneratedContext context
    ) {
        return mapOperationFailure(
                () -> scheduler.runGlobal(() -> new RegeneratedInspection(
                        worldOperations.inspect(context.resetConfig()),
                        context.regeneratedWorld().getWorldFolder().getAbsolutePath()
                )),
                ResetStatus.REGENERATE_FAILED,
                "Die von Worlds regenerierte Bukkit-Welt konnte nicht geprüft werden."
        ).thenCompose(regeneratedInspection -> {
            if (regeneratedInspection == null || regeneratedInspection.inspection() == null) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Die Prüfung der regenerierten Bukkit-Welt hat kein Ergebnis geliefert.",
                        null
                );
            }
            WorldInspection inspection = regeneratedInspection.inspection();
            if (!inspection.loaded()) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Die von Worlds regenerierte Welt ist über Bukkit nicht geladen.",
                        null
                );
            }

            World bukkitWorld = inspection.loadedWorld().orElseThrow();
            if (bukkitWorld != context.regeneratedWorld()) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Die von Worlds gelieferte Welt ist nicht die über Bukkit erreichbare Weltinstanz.",
                        null
                );
            }
            if (!bukkitWorld.getName().equals(context.resetConfig().worldName())) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Die regenerierte Bukkit-Welt hat einen unerwarteten Namen.",
                        null
                );
            }
            if (inspection.protectedMainWorld()) {
                return failed(
                        ResetStatus.REGENERATE_FAILED,
                        "Die regenerierte Welt wurde unerwartet als geschützte Hauptwelt erkannt.",
                        null
                );
            }
            if (inspection.loadedWorldType().orElseThrow()
                    != context.resetConfig().farmworldType()) {
                return failed(
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
            logger.info("Neuer Weltordner: " + regeneratedInspection.worldFolder());
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<ResetAttempt> initializeAndRecord(RegeneratedContext context, ResetAttempt confirmed) {
        return validateRegeneratedWorld(context)
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

    private CompletableFuture<FarmworldResetState> persistState(FarmworldResetConfig config, ResetAttempt attempt) {
        return mapOperationFailure(
                () -> scheduler.runAsync(
                        () -> resetService.completeReset(config, attempt)
                ),
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
            operation = Objects.requireNonNull(
                    operationSupplier.get(),
                    "Externe Operation hat kein Future geliefert."
            );
        } catch (RuntimeException exception) {
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
        notificationService.broadcastResetResult(resetConfig, completedResult);
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

    private record RegeneratedInspection(
            WorldInspection inspection,
            String worldFolder
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
