package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.reset.ResetIntervalParser;
import de.minecraftgilde.farmwelt.reset.ResetNotificationConfig;
import de.minecraftgilde.farmwelt.reset.ResetNotificationMessageConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** Lädt die tolerante, rein vorbereitende Notification-Konfiguration eines Resets. */
public final class FarmworldResetNotificationConfigParser {

    private final Logger logger;
    private final ResetIntervalParser intervalParser = new ResetIntervalParser();

    public FarmworldResetNotificationConfigParser(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ResetNotificationConfig parse(
            ConfigurationSection notificationsSection,
            String farmworldKey
    ) {
        Objects.requireNonNull(farmworldKey, "farmworldKey");
        ResetNotificationConfig defaults = ResetNotificationConfig.defaults();
        if (notificationsSection == null) {
            return defaults;
        }

        return new ResetNotificationConfig(
                parseBoolean(notificationsSection, "enabled", defaults.enabled(), farmworldKey),
                parseWarnings(notificationsSection, defaults.warnings(), farmworldKey),
                parseMessage(notificationsSection, "warning-message", defaults.warningMessage(), farmworldKey),
                parseMessageConfig(notificationsSection, "reset-start", defaults.resetStart(), farmworldKey),
                parseMessageConfig(notificationsSection, "reset-success", defaults.resetSuccess(), farmworldKey),
                parseMessageConfig(notificationsSection, "reset-failure", defaults.resetFailure(), farmworldKey),
                parseMessageConfig(notificationsSection, "evacuation", defaults.evacuation(), farmworldKey)
        );
    }

    private List<Duration> parseWarnings(
            ConfigurationSection section,
            List<Duration> defaults,
            String farmworldKey
    ) {
        if (!section.contains("warnings")) {
            return defaults;
        }

        Object configuredWarnings = section.get("warnings");
        if (!(configuredWarnings instanceof List<?> warningValues)) {
            logger.warning("Notification-Warnungen für Farmwelt '" + farmworldKey
                    + "' müssen eine YAML-Liste sein. Es werden keine Warning-Zeitpunkte verwendet.");
            return List.of();
        }

        List<Duration> warnings = new ArrayList<>();
        for (Object warningValue : warningValues) {
            String warningText = warningValue instanceof String text ? text : null;
            Optional<Duration> warning = intervalParser.parse(warningText);
            if (warning.isPresent()) {
                warnings.add(warning.orElseThrow());
                continue;
            }

            logger.warning("Ungültiger Notification-Warning-Zeitpunkt '" + String.valueOf(warningValue)
                    + "' für Farmwelt '" + farmworldKey
                    + "' wird ignoriert. Erwartet wird eine positive Ganzzahl mit m, h oder d.");
        }
        return warnings;
    }

    private ResetNotificationMessageConfig parseMessageConfig(
            ConfigurationSection parent,
            String path,
            ResetNotificationMessageConfig defaults,
            String farmworldKey
    ) {
        if (!parent.contains(path)) {
            return defaults;
        }

        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            logger.warning("Notification-Eintrag '" + path + "' für Farmwelt '" + farmworldKey
                    + "' muss ein YAML-Bereich sein. Standardwerte werden verwendet.");
            return defaults;
        }

        return new ResetNotificationMessageConfig(
                parseBoolean(section, "enabled", defaults.enabled(), farmworldKey + "." + path),
                parseMessage(section, "message", defaults.message(), farmworldKey + "." + path)
        );
    }

    private boolean parseBoolean(
            ConfigurationSection section,
            String path,
            boolean defaultValue,
            String context
    ) {
        if (!section.contains(path)) {
            return defaultValue;
        }

        Object configuredValue = section.get(path);
        if (configuredValue instanceof Boolean booleanValue) {
            return booleanValue;
        }

        logger.warning("Ungültiger Boolean-Wert für Notification-Eintrag '" + context + "." + path
                + "': " + String.valueOf(configuredValue) + ". Standardwert wird verwendet.");
        return defaultValue;
    }

    private String parseMessage(
            ConfigurationSection section,
            String path,
            String defaultValue,
            String context
    ) {
        if (!section.contains(path)) {
            return defaultValue;
        }

        Object configuredValue = section.get(path);
        if (configuredValue instanceof String message && !message.isBlank()) {
            return message;
        }

        logger.warning("Ungültige oder leere Notification-Nachricht für Eintrag '"
                + context + "." + path + "'. Standardtext wird verwendet.");
        return defaultValue;
    }
}
