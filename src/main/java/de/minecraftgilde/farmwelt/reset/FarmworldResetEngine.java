package de.minecraftgilde.farmwelt.reset;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Executes one complete, asynchronous reset pipeline for a configured farmworld. */
public final class FarmworldResetEngine implements FarmworldResetExecutor {

    private final FarmworldResetService resetService;
    private final FarmworldWorldOperations worldOperations;
    private final WorldDirectoryOperations directoryOperations;
    private final FarmweltScheduler scheduler;
    private final Logger logger;
    private final Set<String> runningResets = ConcurrentHashMap.newKeySet();

    public FarmworldResetEngine(
            FarmworldResetService resetService,
            FarmworldWorldOperations worldOperations,
            WorldDirectoryOperations directoryOperations,
            FarmweltScheduler scheduler,
            Logger logger
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.worldOperations = Objects.requireNonNull(worldOperations, "worldOperations");
        this.directoryOperations = Objects.requireNonNull(directoryOperations, "directoryOperations");
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
                .thenCompose(this::unloadWorld)
                .thenCompose(this::deleteWorldDirectory)
                .thenCompose(this::createWorld)
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
                ResetStatus.WORLD_NOT_FOUND,
                "Die konfigurierte Welt konnte nicht geprüft werden."
        ).thenCompose(inspection -> {
            if (!inspection.loaded()) {
                return failed(
                        ResetStatus.WORLD_NOT_LOADED,
                        "Die Farmwelt ist derzeit nicht geladen und kann nicht sicher zurückgesetzt werden.",
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

            Path actualWorldDirectory = inspection.loadedWorldDirectory().orElseThrow()
                    .toAbsolutePath()
                    .normalize();
            logger.info("Geladene Bukkit-Welt '" + resetConfig.worldName() + "' gefunden.");
            logger.info("Tatsächlicher Weltordner: " + actualWorldDirectory);

            return validateActualWorldDirectory(resetConfig, actualWorldDirectory)
                    .thenApply(validatedWorldDirectory -> {
                        logger.info("Weltordner sicher validiert.");
                        return new PipelineContext(resetConfig, validatedWorldDirectory);
                    });
        });
    }

    private CompletableFuture<Path> validateActualWorldDirectory(
            FarmworldResetConfig resetConfig,
            Path actualWorldDirectory
    ) {
        return scheduler.runAsync(() -> directoryOperations.validateWorldDirectory(
                resetConfig.worldName(),
                actualWorldDirectory
        )).handle((validatedWorldDirectory, failure) -> {
            if (failure == null) {
                return validatedWorldDirectory;
            }

            Throwable cause = unwrap(failure);
            if (cause instanceof WorldDirectoryValidationException validationFailure) {
                throw new CompletionException(toResetFailure(validationFailure));
            }
            throw new CompletionException(new ResetPipelineException(
                    ResetStatus.UNSAFE_WORLD_DIRECTORY,
                    "Der von Bukkit gemeldete Weltordner konnte nicht sicher validiert werden.",
                    cause
            ));
        });
    }

    private ResetPipelineException toResetFailure(
            WorldDirectoryValidationException validationFailure
    ) {
        return switch (validationFailure.reason()) {
            case INVALID_WORLD_NAME -> new ResetPipelineException(
                    ResetStatus.INVALID_CONFIGURATION,
                    "Der konfigurierte Bukkit-Weltname ist ungültig.",
                    validationFailure
            );
            case WORLD_NOT_FOUND -> new ResetPipelineException(
                    ResetStatus.WORLD_NOT_FOUND,
                    "Der von Bukkit gemeldete Weltordner wurde nicht gefunden.",
                    validationFailure
            );
            case PROTECTED_WORLD -> new ResetPipelineException(
                    ResetStatus.PROTECTED_WORLD,
                    "Die konfigurierte Welt ist als Hauptwelt geschützt und darf nicht zurückgesetzt werden.",
                    validationFailure
            );
            case UNSAFE_PATH -> new ResetPipelineException(
                    ResetStatus.UNSAFE_WORLD_DIRECTORY,
                    "Der von Bukkit gemeldete Weltordner liegt außerhalb des erlaubten Bereichs.",
                    validationFailure
            );
        };
    }

    private CompletableFuture<PipelineContext> evacuatePlayers(PipelineContext context) {
        CompletableFuture<Boolean> evacuation = mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.evacuatePlayers(context.resetConfig()))
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
            return mapOperationFailure(
                    scheduler.runGlobal(() -> worldOperations.hasPlayers(context.resetConfig())),
                    ResetStatus.EVACUATION_FAILED,
                    "Die Spielerfreiheit der Welt konnte nicht bestätigt werden."
            ).thenCompose(hasPlayers -> {
                if (hasPlayers) {
                    return failed(
                            ResetStatus.EVACUATION_FAILED,
                            "Nach der Evakuierung befinden sich weiterhin Spieler in der Welt.",
                            null
                    );
                }
                logger.info("Spieler aus '" + context.resetConfig().worldName() + "' evakuiert.");
                return CompletableFuture.completedFuture(context);
            });
        });
    }

    private CompletableFuture<PipelineContext> unloadWorld(PipelineContext context) {
        CompletableFuture<Boolean> unload = mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.unload(context.resetConfig())),
                ResetStatus.UNLOAD_FAILED,
                "Die Welt konnte nicht entladen werden."
        );

        return unload.thenCompose(success -> {
            if (!success) {
                return failed(ResetStatus.UNLOAD_FAILED, "Die Welt konnte nicht entladen werden.", null);
            }
            return mapOperationFailure(
                    scheduler.runGlobal(() -> worldOperations.isLoaded(context.resetConfig())),
                    ResetStatus.UNLOAD_FAILED,
                    "Der Entladezustand der Welt konnte nicht geprüft werden."
            ).thenCompose(stillLoaded -> {
                if (stillLoaded) {
                    return failed(
                            ResetStatus.UNLOAD_FAILED,
                            "Die Welt ist nach dem Entladeversuch weiterhin geladen.",
                            null
                    );
                }
                logger.info("Welt '" + context.resetConfig().worldName() + "' entladen.");
                return CompletableFuture.completedFuture(context);
            });
        });
    }

    private CompletableFuture<PipelineContext> deleteWorldDirectory(PipelineContext context) {
        return mapOperationFailure(
                scheduler.runAsync(() -> {
                    directoryOperations.deleteRecursively(context.worldDirectory());
                    return context;
                }),
                ResetStatus.DELETE_FAILED,
                "Der Weltordner konnte nicht vollständig gelöscht werden."
        ).thenApply(result -> {
            logger.info("Weltordner '" + context.resetConfig().worldName() + "' gelöscht.");
            return result;
        });
    }

    private CompletableFuture<PipelineContext> createWorld(PipelineContext context) {
        return mapOperationFailure(
                scheduler.runGlobal(() -> worldOperations.createAndValidate(context.resetConfig())),
                ResetStatus.CREATE_FAILED,
                "Die gelöschte Welt konnte nicht neu erstellt werden."
        ).thenCompose(createdWorldDirectory -> {
            if (createdWorldDirectory.isEmpty()) {
                return failed(
                        ResetStatus.CREATE_FAILED,
                        "Die gelöschte Welt konnte nicht neu erstellt und validiert werden.",
                        null
                );
            }

            Path actualCreatedWorldDirectory = createdWorldDirectory.orElseThrow()
                    .toAbsolutePath()
                    .normalize();
            return mapOperationFailure(
                    scheduler.runAsync(() -> directoryOperations.validateWorldDirectory(
                            context.resetConfig().worldName(),
                            actualCreatedWorldDirectory
                    )),
                    ResetStatus.CREATE_FAILED,
                    "Der neu erstellte Weltordner konnte nicht sicher validiert werden."
            ).thenApply(validatedCreatedWorldDirectory -> {
                logger.info("Farmwelt '" + context.resetConfig().worldName() + "' neu erstellt.");
                logger.info("Neuer Weltordner: " + validatedCreatedWorldDirectory);
                return context;
            });
        });
    }

    private CompletableFuture<FarmworldResetState> persistState(PipelineContext context) {
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
        if (status == ResetStatus.CREATE_FAILED || status == ResetStatus.STATE_SAVE_FAILED) {
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
            Path worldDirectory
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
