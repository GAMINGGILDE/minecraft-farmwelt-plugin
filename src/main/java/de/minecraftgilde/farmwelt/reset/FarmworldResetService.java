package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verwaltet Reset-Konfigurationen und deren persistenten Zeitplan.
 *
 * <p>Ein Reload berechnet ein vorhandenes {@code nextReset} nie neu. Ein geändertes Intervall
 * wird daher erst nach dem nächsten erfolgreichen Reset für den Folgetermin verwendet.</p>
 */
public final class FarmworldResetService {

    private final ResetStateRepository stateRepository;
    private final ResetRecoveryJournal journal;
    private final Object scheduleWriter = new Object();
    private boolean journalLoaded;
    private final Clock clock;
    private final Logger logger;

    private Map<String, FarmworldResetConfig> configurations = Map.of();
    private volatile Map<String, FarmworldResetState> states = Map.of();

    public FarmworldResetService(ResetStateRepository stateRepository, Path dataDirectory, Clock clock, Logger logger) {
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.journal = new ResetRecoveryJournal(dataDirectory);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean reload(Collection<FarmworldResetConfig> resetConfigurations) {
        synchronized (scheduleWriter) {
            return loadSchedules(resetConfigurations);
        }
    }

    private boolean loadSchedules(Collection<FarmworldResetConfig> resetConfigurations) {
        Objects.requireNonNull(resetConfigurations, "resetConfigurations");

        Map<String, FarmworldResetConfig> loadedConfigurations = indexConfigurations(resetConfigurations);
        Map<String, FarmworldResetState> loadedStates;
        try {
            loadedStates = new LinkedHashMap<>(stateRepository.load());
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Reset-States konnten nicht geladen werden. Bestehende Reset-Konfiguration und In-Memory-States bleiben erhalten.", exception);
            return false;
        }

        boolean firstLoad = !journalLoaded;
        if (firstLoad) {
            journal.load(loadedStates);
            journalLoaded = true;
        }
        for (FarmworldResetConfig config : loadedConfigurations.values()) {
            if (firstLoad || recoveryProblem(config).isEmpty()) {
                journal.problem(config, loadedStates).ifPresent(reason ->
                        logger.severe("Reset-Sperre für '" + config.farmworldKey() + "'. " + reason));
            }
        }
        boolean stateChanged = initializeMissingStates(loadedConfigurations, loadedStates);
        if (stateChanged) {
            try {
                stateRepository.save(Collections.unmodifiableMap(new LinkedHashMap<>(loadedStates)));
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Neu initialisierte Reset-States konnten nicht gespeichert werden.", exception);
                return false;
            }
        }

        synchronized (this) {
            configurations = Collections.unmodifiableMap(loadedConfigurations);
            states = Collections.unmodifiableMap(new LinkedHashMap<>(loadedStates));
        }
        return true;
    }

    public synchronized Optional<FarmworldResetConfig> getConfig(String farmworldKey) {
        return Optional.ofNullable(configurations.get(farmworldKey));
    }

    public synchronized Optional<FarmworldResetState> getState(String farmworldKey) {
        if (!configurations.containsKey(farmworldKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(farmworldKey));
    }

    public synchronized Collection<FarmworldResetConfig> getConfiguredWorlds() {
        return List.copyOf(configurations.values());
    }

    /**
     * Persists a successful reset using the currently loaded configuration and the injected clock.
     * The public in-memory state is replaced only after the repository save completed successfully.
     */
    public FarmworldResetState completeReset(String farmworldKey) throws IOException {
        synchronized (scheduleWriter) {
            FarmworldResetConfig configuration = configurations.get(farmworldKey);
            if (configuration == null) {
                throw new IllegalArgumentException("Farmwelt '" + farmworldKey + "' ist nicht konfiguriert.");
            }
            return completeReset(configuration);
        }
    }

    public FarmworldResetState completeReset(
            FarmworldResetConfig configurationSnapshot
    ) throws IOException {
        return completeReset(configurationSnapshot, clock.instant());
    }

    /**
     * Completes a reset with the immutable configuration snapshot captured by the reset engine.
     * This makes a concurrent reload unable to change the interval of an already running reset.
     */
    public FarmworldResetState completeReset(
            FarmworldResetConfig configurationSnapshot,
            Instant completedAt
    ) throws IOException {
        synchronized (scheduleWriter) {
            if (journal.current(configurationSnapshot.farmworldType()) != null
                    || recoveryProblem(configurationSnapshot).isPresent()) {
                throw new IOException("Zum Abschluss dieser Farmwelt ist der Beleg des laufenden Versuchs erforderlich.");
            }
            return saveCompletion(configurationSnapshot, completedAt, Optional.empty());
        }
    }

    private FarmworldResetState saveCompletion(FarmworldResetConfig configurationSnapshot, Instant completedAt,
                                               Optional<UUID> attemptId) throws IOException {
        Objects.requireNonNull(configurationSnapshot, "configurationSnapshot");
        Objects.requireNonNull(completedAt, "completedAt");
        if (!configurationSnapshot.enabled()) {
            throw new IllegalStateException("Farmwelt '" + configurationSnapshot.farmworldKey()
                    + "' ist für Resets deaktiviert.");
        }

        final Instant nextReset;
        try {
            nextReset = completedAt.plus(configurationSnapshot.interval());
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IOException("Der nächste Reset-Zeitpunkt liegt außerhalb des unterstützten Bereichs.", exception);
        }

        FarmworldResetState completedState = new FarmworldResetState(
                configurationSnapshot.farmworldKey(),
                Optional.of(completedAt),
                nextReset, attemptId
        );
        Map<String, FarmworldResetState> candidateStates = new LinkedHashMap<>(states);
        candidateStates.put(configurationSnapshot.farmworldKey(), completedState);
        Map<String, FarmworldResetState> immutableCandidate = Collections.unmodifiableMap(candidateStates);

        // Save first, publish second. A failed save therefore leaves the observable schedule unchanged.
        stateRepository.save(immutableCandidate);
        states = immutableCandidate;
        return completedState;
    }

    public Optional<String> recoveryProblem(FarmworldResetConfig config) {
        return journal.problem(config, states);
    }

    ResetAttempt newAttempt(FarmworldResetConfig config) {
        return new ResetAttempt(UUID.randomUUID(), config.farmworldKey(), config.worldName(),
                clock.instant(), ResetAttempt.Phase.WORLDS_PENDING, null, null);
    }

    @FunctionalInterface
    interface HandoffRollback {
        void restorePrevious() throws IOException;
    }

    HandoffRollback prepare(FarmworldResetConfig config, ResetAttempt attempt) throws IOException {
        synchronized (journal) {
            Optional<String> problem = recoveryProblem(config);
            if (problem.isPresent()) {
                throw new IOException(problem.orElseThrow());
            }
            FarmworldType type = config.farmworldType();
            ResetAttempt previous = journal.current(type);
            journal.replace(type, previous, attempt);
            return () -> {
                synchronized (journal) {
                    journal.replace(type, attempt, previous);
                }
            };
        }
    }

    ResetAttempt worldsReturned(FarmworldResetConfig config, ResetAttempt attempt) throws IOException {
        ResetAttempt update = attempt.worldsReturned();
        synchronized (journal) {
            journal.replace(config.farmworldType(), attempt, update);
        }
        return update;
    }

    ResetAttempt postResetDone(FarmworldResetConfig config, ResetAttempt attempt) throws IOException {
        Instant completed = clock.instant();
        ResetAttempt update = attempt.postResetDone(completed, completed.plus(config.interval()));
        synchronized (journal) {
            journal.replace(config.farmworldType(), attempt, update);
        }
        return update;
    }

    FarmworldResetState completeReset(FarmworldResetConfig config, ResetAttempt attempt) throws IOException {
        synchronized (scheduleWriter) {
            if (attempt.phase() != ResetAttempt.Phase.POST_RESET_DONE
                    || !attempt.equals(journal.current(config.farmworldType()))) {
                throw new IOException("Abschlussbeleg passt nicht zum offenen Versuch.");
            }
            return saveCompletion(config, attempt.completed(), Optional.of(attempt.id()));
        }
    }

    private Map<String, FarmworldResetConfig> indexConfigurations(
            Collection<FarmworldResetConfig> resetConfigurations
    ) {
        Map<String, FarmworldResetConfig> indexedConfigurations = new LinkedHashMap<>();
        for (FarmworldResetConfig configuration : resetConfigurations) {
            FarmworldResetConfig existing = indexedConfigurations.put(configuration.farmworldKey(), configuration);
            if (existing != null) {
                logger.warning("Doppelte Reset-Konfiguration für Farmwelt '"
                        + configuration.farmworldKey() + "' wurde überschrieben.");
            }
        }
        return indexedConfigurations;
    }

    private boolean initializeMissingStates(
            Map<String, FarmworldResetConfig> resetConfigurations,
            Map<String, FarmworldResetState> loadedStates
    ) {
        boolean stateChanged = false;
        Instant now = clock.instant();

        for (FarmworldResetConfig configuration : resetConfigurations.values()) {
            if (!configuration.enabled() || loadedStates.containsKey(configuration.farmworldKey())
                    || journal.problem(configuration, loadedStates).isPresent()) {
                continue;
            }

            try {
                Instant nextReset = now.plus(configuration.interval());
                loadedStates.put(
                        configuration.farmworldKey(),
                        new FarmworldResetState(configuration.farmworldKey(), Optional.empty(), nextReset)
                );
                stateChanged = true;
            } catch (DateTimeException | ArithmeticException exception) {
                logger.warning("Reset-Intervall für Farmwelt '" + configuration.farmworldKey()
                        + "' liegt außerhalb des unterstützten Zeitbereichs. Es wurde kein State erzeugt.");
            }
        }

        return stateChanged;
    }
}
