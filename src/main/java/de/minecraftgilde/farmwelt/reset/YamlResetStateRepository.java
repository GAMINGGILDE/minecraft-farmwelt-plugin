package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlResetStateRepository implements ResetStateRepository {

    private static final int STATE_VERSION = 1;

    private final Path stateFile;
    private final Logger logger;

    public YamlResetStateRepository(Path stateFile, Logger logger) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Map<String, FarmworldResetState> load() throws IOException {
        if (!Files.exists(stateFile)) {
            return Map.of();
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(stateFile.toFile());
        } catch (InvalidConfigurationException exception) {
            logger.warning("Reset-State-Datei '" + stateFile.getFileName()
                    + "' ist kein gültiges YAML. Die betroffenen States werden neu initialisiert: "
                    + exception.getMessage());
            return Map.of();
        }

        validateVersion(yaml);

        ConfigurationSection worldsSection = yaml.getConfigurationSection("worlds");
        if (worldsSection == null) {
            if (yaml.contains("worlds")) {
                logger.warning("Reset-State-Datei enthält einen ungültigen Bereich 'worlds'.");
            }
            return Map.of();
        }

        Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        for (String farmworldKey : worldsSection.getKeys(false)) {
            ConfigurationSection stateSection = worldsSection.getConfigurationSection(farmworldKey);
            if (stateSection == null) {
                warnInvalidState(farmworldKey, "der Eintrag ist kein YAML-Bereich");
                continue;
            }

            Optional<FarmworldResetState> state = loadState(farmworldKey, stateSection);
            state.ifPresent(value -> states.put(farmworldKey, value));
        }

        return Collections.unmodifiableMap(states);
    }

    @Override
    public void save(Map<String, FarmworldResetState> states) throws IOException {
        Objects.requireNonNull(states, "states");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", STATE_VERSION);
        ConfigurationSection worldsSection = yaml.createSection("worlds");

        for (Map.Entry<String, FarmworldResetState> entry : new TreeMap<>(states).entrySet()) {
            FarmworldResetState state = entry.getValue();
            ConfigurationSection stateSection = worldsSection.createSection(entry.getKey());
            state.lastReset().ifPresent(lastReset -> stateSection.set("last-reset", lastReset.toString()));
            stateSection.set("next-reset", state.nextReset().toString());
        }

        Path parent = stateFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            yaml.save(temporaryFile.toFile());
            replaceStateFile(temporaryFile);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                logger.warning("Temporäre Reset-State-Datei konnte nicht entfernt werden: "
                        + exception.getMessage());
            }
        }
    }

    private void validateVersion(YamlConfiguration yaml) throws IOException {
        Object versionValue = yaml.get("version");
        if (versionValue == null) {
            logger.warning("Reset-State-Datei enthält keine Versionsangabe. Version 1 wird angenommen.");
            return;
        }
        if (!(versionValue instanceof Number version)) {
            logger.warning("Reset-State-Datei enthält eine ungültige Versionsangabe. Version 1 wird angenommen.");
            return;
        }
        if (version.intValue() > STATE_VERSION) {
            throw new IOException("Reset-State-Version " + version + " wird nicht unterstützt.");
        }
    }

    private Optional<FarmworldResetState> loadState(
            String farmworldKey,
            ConfigurationSection stateSection
    ) {
        Object nextResetValue = stateSection.get("next-reset");
        Optional<Instant> nextReset = parseInstant(nextResetValue);
        if (nextReset.isEmpty()) {
            warnInvalidState(farmworldKey, "'next-reset' ist kein gültiger ISO-8601-Zeitstempel");
            return Optional.empty();
        }

        Optional<Instant> lastReset = Optional.empty();
        Object lastResetValue = stateSection.get("last-reset");
        if (lastResetValue != null) {
            lastReset = parseInstant(lastResetValue);
            if (lastReset.isEmpty()) {
                warnInvalidState(farmworldKey, "'last-reset' ist kein gültiger ISO-8601-Zeitstempel");
                return Optional.empty();
            }
        }

        return Optional.of(new FarmworldResetState(farmworldKey, lastReset, nextReset.orElseThrow()));
    }

    private Optional<Instant> parseInstant(Object value) {
        if (!(value instanceof String timestamp) || timestamp.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Instant.parse(timestamp));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private void warnInvalidState(String farmworldKey, String reason) {
        logger.warning("Ungültiger Reset-State für Farmwelt '" + farmworldKey + "': " + reason + ".");
    }

    private void replaceStateFile(Path temporaryFile) throws IOException {
        try {
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
