package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;

/** Executes one complete, asynchronous reset pipeline for a configured farmworld. */
public final class FarmworldResetEngine implements FarmworldResetExecutor {

    private final FarmworldResetService resetService;
    private final FarmworldWorldOperations worldOperations;
    private final FarmworldLifecycleService lifecycleService;
    private final FarmweltScheduler scheduler;
    private final Logger logger;
    private final Set<String> runningResets = ConcurrentHashMap.newKeySet();

    public FarmworldResetEngine(
            FarmworldResetService resetService,
            FarmworldWorldOperations worldOperations,
            FarmworldLifecycleService lifecycleService,
            FarmweltScheduler scheduler,
            Logger logger
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.worldOperations = Objects.requireNonNull(worldOperations, "worldOperations");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<ResetResult> reset(String farmworldKey) {
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

        logger.info("Reset für Farmwelt '" + farmworldKey + "' ("
                + resetConfig.worldName() + ") gestartet.");

        CompletableFuture<ResetResult> resetFuture;
        try {
            resetFuture = execute(resetConfig)
                    .handle((result, failure) -> finish(resetConfig, result, failure));
        } catch (RuntimeException exception) {
            resetFuture = CompletableFuture.completedFuture(finish(resetConfig, null, exception));
        }

        return resetFuture.whenComplete((result, failure) -> runningResets.remove(farmworldKey));
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

    private CompletableFuture<ResetResult> execute(FarmworldResetConfig resetConfig) {
        return validateWorld(resetConfig)
                .thenCompose(this::evacuatePlayers)
                .thenCompose(this::confirmWorldIsEmpty)
                .thenCompose(this::regenerateWorld)
                .thenCompose(this::validateRegeneratedWorld)
                .thenCompose(this::persistState)
                .thenApply(state -> new ResetResult(
                        resetConfig.farmworldKey(),
                        resetConfig.worldName(),
                        ResetStatus.SUCCESS,
                        "Die Farmwelt wurde erfolgreich zurückgesetzt.",
                        null
                ));
    }

    private CompletableFuture<PipelineContext> validateWorld(FarmworldResetConfig resetConfig) {
        return mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.inspect(resetConfig)),
                ResetStatus.INVALID_CONFIGURATION,
                "Die konfigurierte Welt konnte nicht geprüft werden."
        ).thenCompose(inspection -> {
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
            return CompletableFuture.completedFuture(new PipelineContext(resetConfig, world));
        });
    }

    private CompletableFuture<PipelineContext> evacuatePlayers(PipelineContext context) {
        CompletableFuture<Boolean> evacuation = mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.evacuatePlayers(context.originalWorld()))
                        .thenCompose(Function.identity()),
                ResetStatus.EVACUATION_FAILED,
                "Mindestens ein Spieler konnte nicht sicher evakuiert werden."
        );

        return evacuation.thenCompose(success -> {
            if (!success) {
                return failed(
                        ResetStatus.EVACUATION_FAILED,
                        "Mindestens ein Spieler konnte nicht sicher evakuiert werden.",
                        null
                );
            }
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<PipelineContext> confirmWorldIsEmpty(PipelineContext context) {
        return mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.hasPlayers(context.originalWorld())),
                ResetStatus.EVACUATION_FAILED,
                "Die Spielerfreiheit der Farmwelt konnte nicht bestätigt werden."
        ).thenCompose(hasPlayers -> {
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

    private CompletableFuture<RegeneratedContext> regenerateWorld(PipelineContext context) {
        logger.info("Regeneration von '" + context.resetConfig().worldName()
                + "' über Worlds gestartet.");

        final CompletableFuture<World> regeneration;
        try {
            // Worlds owns Folia/global scheduling for its complete lifecycle operation.
            regeneration = lifecycleService.regenerate(context.originalWorld());
        } catch (RuntimeException exception) {
            return failed(
                    ResetStatus.REGENERATE_FAILED,
                    "Worlds konnte die Farmwelt nicht regenerieren.",
                    exception
            );
        }

        return mapOperationFailure(
                regeneration,
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
                    context.originalWorld(),
                    regeneratedWorld
            ));
        });
    }

    private CompletableFuture<RegeneratedContext> validateRegeneratedWorld(
            RegeneratedContext context
    ) {
        return mapOperationFailure(
                scheduler.runGlobal(() -> new RegeneratedInspection(
                        worldOperations.inspect(context.resetConfig()),
                        context.regeneratedWorld().getWorldFolder().getAbsolutePath()
                )),
                ResetStatus.REGENERATE_FAILED,
                "Die von Worlds regenerierte Bukkit-Welt konnte nicht geprüft werden."
        ).thenCompose(regeneratedInspection -> {
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
            logger.info("Neuer Weltordner: " + regeneratedInspection.worldFolder());
            return CompletableFuture.completedFuture(context);
        });
    }

    private CompletableFuture<FarmworldResetState> persistState(RegeneratedContext context) {
        return mapOperationFailure(
                scheduler.runAsync(() -> resetService.completeReset(context.resetConfig())),
                ResetStatus.STATE_SAVE_FAILED,
                "Der Weltreset war erfolgreich, aber der Reset-State konnte nicht gespeichert werden."
        );
    }

    private <T> CompletableFuture<T> mapOperationFailure(
            CompletableFuture<T> operation,
            ResetStatus status,
            String message
    ) {
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
                + "' abgebrochen: " + message;
        if (status == ResetStatus.REGENERATE_FAILED || status == ResetStatus.STATE_SAVE_FAILED) {
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
            World originalWorld
    ) {
    }

    private record RegeneratedContext(
            FarmworldResetConfig resetConfig,
            World originalWorld,
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
