package de.minecraftgilde.farmwelt.reset;

import de.minecraftgilde.farmwelt.config.FarmworldYamlKeys;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

public final class YamlResetStateRepository implements ResetStateRepository {

    private static final int STATE_VERSION = 1;

    private final Path stateFile;
    private final Logger logger;
    private Map<FarmworldType, List<NodeTuple>> preserved = Map.of();

    public YamlResetStateRepository(Path stateFile, Logger logger) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Map<String, FarmworldResetState> load() throws IOException {
        if (Files.notExists(stateFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            preserved = Map.of();
            return Map.of();
        }

        YamlConfiguration yaml = new YamlConfiguration();
        String contents = Files.readString(stateFile);
        try {
            yaml.loadFromString(contents);
        } catch (InvalidConfigurationException exception) {
            logger.warning("Reset-State-Datei '" + stateFile.getFileName()
                    + "' ist kein gültiges YAML. Die betroffenen States werden neu initialisiert: "
                    + exception.getMessage());
            preserved = Map.of();
            return Map.of();
        }

        validateVersion(yaml);
        try {
            preserved = FarmworldYamlKeys.duplicates(contents, "worlds");
        } catch (YAMLException exception) {
            throw new IllegalArgumentException("YAML-Identitäten im Zeitplan konnten nicht geprüft werden.", exception);
        }

        ConfigurationSection worldsSection = yaml.getConfigurationSection("worlds");
        if (worldsSection == null) {
            if (yaml.contains("worlds")) {
                logger.warning("Reset-State-Datei enthält einen ungültigen Bereich 'worlds'.");
            }
            return Map.of();
        }

        Map<String, FarmworldResetState> states = new LinkedHashMap<>();
        for (String farmworldKey : worldsSection.getKeys(false)) {
            if (FarmworldType.fromFarmworldKey(farmworldKey).filter(preserved::containsKey).isPresent()) {
                continue;
            }
            ConfigurationSection stateSection = worldsSection.getConfigurationSection(farmworldKey);
            if (stateSection == null) {
                warnInvalidState(farmworldKey, "der Eintrag ist kein YAML-Bereich");
                continue;
            }

            Optional<FarmworldResetState> state = loadState(farmworldKey, stateSection);
            state.ifPresent(value -> states.put(value.farmworldKey(), value));
        }

        return Collections.unmodifiableMap(states);
    }

    @Override
    public Map<FarmworldType, String> identityConflicts() {
        var problems = new EnumMap<FarmworldType, String>(FarmworldType.class);
        preserved.forEach((type, entries) -> problems.put(type,
                "reset-state.yml: " + FarmworldYamlKeys.diagnosis(entries)));
        return Map.copyOf(problems);
    }

    @Override
    public boolean hasConflictingReceipt() {
        return preserved.values().stream().flatMap(List::stream)
                .map(NodeTuple::getValueNode).filter(MappingNode.class::isInstance).map(MappingNode.class::cast)
                .anyMatch(node -> node.isMerged() || node.getValue().stream().anyMatch(entry ->
                        entry.getKeyNode() instanceof ScalarNode key && key.getValue().equals("attempt-id")));
    }

    @Override
    public void save(Map<String, FarmworldResetState> states) throws IOException {
        Objects.requireNonNull(states, "states");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", STATE_VERSION);
        ConfigurationSection worldsSection = yaml.createSection("worlds");

        for (Map.Entry<String, FarmworldResetState> entry : new TreeMap<>(states).entrySet()) {
            FarmworldResetState state = entry.getValue();
            if (!entry.getKey().equals(state.farmworldKey())
                    || FarmworldType.fromFarmworldKey(entry.getKey()).filter(preserved::containsKey).isPresent()) {
                throw new IOException("Nicht eindeutiger Zeitplan für '" + entry.getKey() + "'.");
            }
            ConfigurationSection stateSection = worldsSection.createSection(state.farmworldKey());
            state.attemptId().ifPresent(id -> stateSection.set("attempt-id", id.toString()));
            state.lastReset().ifPresent(lastReset -> stateSection.set("last-reset", lastReset.toString()));
            stateSection.set("next-reset", state.nextReset().toString());
        }

        Path parent = stateFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryFile, serialize(yaml), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            replaceStateFile(temporaryFile);
            DurableResetFiles.syncDirectory(parent);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                logger.warning("Temporäre Reset-State-Datei konnte nicht entfernt werden: "
                        + exception.getMessage());
            }
        }
    }

    private String serialize(YamlConfiguration schedules) {
        String contents = schedules.saveToString();
        if (preserved.isEmpty()) {
            return contents;
        }
        // Auch gleich geschriebene oder beschädigte Teilbäume beim Speichern gesunder Welten erhalten.
        var yaml = new Yaml();
        var root = yaml.compose(new StringReader(contents));
        var worlds = FarmworldYamlKeys.section(root, "worlds");
        preserved.values().forEach(worlds.getValue()::addAll);
        var output = new StringWriter();
        yaml.serialize(root, output);
        return output.toString();
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

        Optional<UUID> attemptId = Optional.empty();
        if (stateSection.contains("attempt-id")) {
            try {
                attemptId = Optional.of(ResetAttempt.parseId(stateSection.getString("attempt-id", "")));
            } catch (IllegalArgumentException e) {
                warnInvalidState(farmworldKey, "ungültige Versuchs-ID");
                return Optional.empty();
            }
        }
        return Optional.of(new FarmworldResetState(farmworldKey, lastReset, nextReset.orElseThrow(), attemptId));
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
