package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns reset configurations and their persistent schedule state.
 *
 * <p>Reloading never recalculates an existing {@code nextReset}. A changed interval is therefore
 * only available for calculating a later schedule after a successful reset in a future phase.</p>
 */
public final class FarmworldResetService {

    private final ResetStateRepository stateRepository;
    private final Clock clock;
    private final Logger logger;

    private Map<String, FarmworldResetConfig> configurations = Map.of();
    private Map<String, FarmworldResetState> states = Map.of();

    public FarmworldResetService(ResetStateRepository stateRepository, Clock clock, Logger logger) {
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized void reload(Collection<FarmworldResetConfig> resetConfigurations) {
        Objects.requireNonNull(resetConfigurations, "resetConfigurations");

        Map<String, FarmworldResetConfig> loadedConfigurations = indexConfigurations(resetConfigurations);
        configurations = Collections.unmodifiableMap(loadedConfigurations);

        Map<String, FarmworldResetState> loadedStates;
        try {
            loadedStates = new LinkedHashMap<>(stateRepository.load());
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Reset-States konnten nicht geladen werden. Bestehende In-Memory-States bleiben erhalten.", exception);
            return;
        }

        boolean stateChanged = initializeMissingStates(loadedConfigurations, loadedStates);
        states = Collections.unmodifiableMap(new LinkedHashMap<>(loadedStates));

        if (stateChanged) {
            try {
                stateRepository.save(states);
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Neu initialisierte Reset-States konnten nicht gespeichert werden.", exception);
            }
        }
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
            if (!configuration.enabled() || loadedStates.containsKey(configuration.farmworldKey())) {
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
