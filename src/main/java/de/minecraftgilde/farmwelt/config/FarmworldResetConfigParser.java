package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.PostResetConfig;
import de.minecraftgilde.farmwelt.reset.ResetIntervalParser;
import de.minecraftgilde.farmwelt.reset.ResetNotificationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** Übersetzt den Reset-Bereich der YAML-Konfiguration in validierte Laufzeitwerte. */
public final class FarmworldResetConfigParser {

    private final Logger logger;
    private final ResetIntervalParser intervalParser = new ResetIntervalParser();
    private final FarmworldPostResetConfigParser postResetConfigParser;
    private final FarmworldResetNotificationConfigParser notificationConfigParser;

    public FarmworldResetConfigParser(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.postResetConfigParser = new FarmworldPostResetConfigParser(logger);
        this.notificationConfigParser = new FarmworldResetNotificationConfigParser(logger);
    }

    public List<FarmworldResetConfig> parse(ConfigurationSection farmworldsSection) {
        if (farmworldsSection == null) {
            return List.of();
        }

        List<FarmworldResetConfig> loadedConfigs = new ArrayList<>();
        for (String farmworldKey : farmworldsSection.getKeys(false)) {
            ConfigurationSection farmworldSection = farmworldsSection.getConfigurationSection(farmworldKey);
            if (farmworldSection == null) {
                continue;
            }

            ConfigurationSection resetSection = farmworldSection.getConfigurationSection("reset");
            if (resetSection == null) {
                continue;
            }

            boolean enabled = farmworldSection.getBoolean("enabled", true)
                    && resetSection.getBoolean("enabled", false);
            parseEntry(
                    farmworldKey,
                    farmworldSection.getString("display-name"),
                    resetSection,
                    enabled
            ).ifPresent(loadedConfigs::add);
        }

        return List.copyOf(loadedConfigs);
    }

    private Optional<FarmworldResetConfig> parseEntry(
            String farmworldKey,
            String configuredDisplayName,
            ConfigurationSection resetSection,
            boolean enabled
    ) {
        String worldName = resetSection.getString("world");
        if (worldName == null || worldName.isBlank()) {
            logger.warning("Reset-Konfiguration für Farmwelt '" + farmworldKey
                    + "' enthält keinen Bukkit-Weltnamen. Reset für diese Farmwelt wurde deaktiviert.");
            return Optional.empty();
        }

        Object intervalValue = resetSection.get("interval");
        String intervalText = intervalValue instanceof String value ? value : null;
        Optional<Duration> interval = intervalParser.parse(intervalText);
        if (interval.isEmpty()) {
            logger.warning("Ungültiges Reset-Intervall '" + String.valueOf(intervalValue)
                    + "' für Farmwelt '" + farmworldKey
                    + "'. Reset für diese Farmwelt wurde deaktiviert.");
            return Optional.empty();
        }

        Optional<FarmworldType> farmworldType = FarmworldType.fromFarmworldKey(farmworldKey);
        if (farmworldType.isEmpty()) {
            logger.warning("Reset-Konfiguration für unbekannte Farmwelt-ID '"
                    + farmworldKey + "' wurde deaktiviert.");
            return Optional.empty();
        }

        final PostResetConfig postReset;
        try {
            postReset = postResetConfigParser.parse(
                    resetSection.getConfigurationSection("post-reset"),
                    farmworldKey,
                    farmworldType.orElseThrow()
            );
        } catch (IllegalArgumentException exception) {
            logger.warning("Ungültige Post-Reset-Konfiguration für Farmwelt '"
                    + farmworldKey + "': " + exception.getMessage()
                    + " Reset für diese Farmwelt wurde deaktiviert.");
            return Optional.empty();
        }

        ConfigurationSection notificationsSection = resetSection.getConfigurationSection("notifications");
        if (notificationsSection == null && resetSection.contains("notifications")) {
            logger.warning("Notification-Konfiguration für Farmwelt '" + farmworldKey
                    + "' muss ein YAML-Bereich sein. Standardwerte werden verwendet.");
        }
        ResetNotificationConfig notifications = notificationConfigParser.parse(
                notificationsSection,
                farmworldKey
        );

        return Optional.of(new FarmworldResetConfig(
                farmworldKey,
                worldName.trim(),
                displayName(configuredDisplayName, farmworldType.orElseThrow()),
                enabled,
                interval.orElseThrow(),
                farmworldType.orElseThrow(),
                postReset,
                notifications
        ));
    }

    private String displayName(String configuredDisplayName, FarmworldType farmworldType) {
        if (configuredDisplayName != null && !configuredDisplayName.isBlank()) {
            return configuredDisplayName.trim();
        }
        return switch (farmworldType) {
            case OVERWORLD -> "Farmwelt";
            case NETHER -> "Netherfarm";
            case END -> "Endfarm";
        };
    }
}
