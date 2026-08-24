package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.reset.EndPostResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.PostResetConfig;
import de.minecraftgilde.farmwelt.reset.WorldBorderConfig;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** Parses the deliberately small, typed post-reset configuration surface. */
public final class FarmworldPostResetConfigParser {

    private final Logger logger;

    public FarmworldPostResetConfigParser(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public PostResetConfig parse(
            ConfigurationSection postResetSection,
            String farmworldKey,
            FarmworldType farmworldType
    ) {
        Objects.requireNonNull(farmworldKey, "farmworldKey");
        Objects.requireNonNull(farmworldType, "farmworldType");
        if (postResetSection == null) {
            return PostResetConfig.none();
        }

        ConfigurationSection gamerulesSection = optionalSection(postResetSection, "gamerules");
        ConfigurationSection worldBorderSection = optionalSection(postResetSection, "world-border");
        ConfigurationSection endSection = optionalSection(postResetSection, "end");
        Map<String, Object> gamerules = parseGamerules(gamerulesSection);
        Optional<WorldBorderConfig> worldBorder = parseWorldBorder(worldBorderSection);
        Optional<EndPostResetConfig> end = parseEnd(
                endSection,
                farmworldKey,
                farmworldType
        );
        return new PostResetConfig(gamerules, worldBorder, end);
    }

    private Map<String, Object> parseGamerules(ConfigurationSection gamerulesSection) {
        if (gamerulesSection == null) {
            return Map.of();
        }

        Map<String, Object> gamerules = new LinkedHashMap<>();
        for (String configuredName : gamerulesSection.getKeys(false)) {
            String normalizedName = configuredName.trim().toLowerCase(Locale.ROOT);
            if (normalizedName.isEmpty()) {
                throw new IllegalArgumentException("Ein Gamerule-Name darf nicht leer sein.");
            }
            Object configuredValue = gamerulesSection.get(configuredName);
            if (configuredValue == null
                    || configuredValue instanceof ConfigurationSection
                    || configuredValue instanceof Map<?, ?>
                    || configuredValue instanceof Iterable<?>) {
                throw new IllegalArgumentException(
                        "Gamerule '" + configuredName + "' ben\u00f6tigt einen skalaren Wert."
                );
            }
            gamerules.put(normalizedName, configuredValue);
        }
        return gamerules;
    }

    private ConfigurationSection optionalSection(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null && parent.contains(path)) {
            throw new IllegalArgumentException(
                    "Post-Reset-Eintrag '" + path + "' muss ein YAML-Bereich sein."
            );
        }
        return section;
    }

    private Optional<WorldBorderConfig> parseWorldBorder(ConfigurationSection worldBorderSection) {
        if (worldBorderSection == null || !worldBorderSection.contains("size")) {
            return Optional.empty();
        }

        Object configuredSize = worldBorderSection.get("size");
        final double size;
        if (configuredSize instanceof Number number) {
            size = number.doubleValue();
        } else if (configuredSize instanceof String text) {
            try {
                size = Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                throw invalidBorderSize(configuredSize, exception);
            }
        } else {
            throw invalidBorderSize(configuredSize, null);
        }

        try {
            return Optional.of(new WorldBorderConfig(size));
        } catch (IllegalArgumentException exception) {
            throw invalidBorderSize(configuredSize, exception);
        }
    }

    private Optional<EndPostResetConfig> parseEnd(
            ConfigurationSection endSection,
            String farmworldKey,
            FarmworldType farmworldType
    ) {
        if (endSection == null || !endSection.contains("dragon")) {
            return Optional.empty();
        }
        if (farmworldType != FarmworldType.END) {
            logger.warning("Post-Reset-Enderdragon-Policy f\u00fcr Farmwelt '" + farmworldKey
                    + "' wird ignoriert, da sie keine End-Farmwelt ist.");
            return Optional.empty();
        }

        Object configuredDragon = endSection.get("dragon");
        if (configuredDragon instanceof Boolean booleanValue) {
            return Optional.of(new EndPostResetConfig(booleanValue));
        }
        if (configuredDragon instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> Optional.of(new EndPostResetConfig(true));
                case "false" -> Optional.of(new EndPostResetConfig(false));
                default -> throw invalidDragonPolicy(configuredDragon);
            };
        }
        throw invalidDragonPolicy(configuredDragon);
    }

    private IllegalArgumentException invalidBorderSize(Object configuredSize, Throwable cause) {
        return new IllegalArgumentException(
                "Ung\u00fcltige WorldBorder-Gr\u00f6\u00dfe '" + configuredSize + "'.",
                cause
        );
    }

    private IllegalArgumentException invalidDragonPolicy(Object configuredDragon) {
        return new IllegalArgumentException(
                "Ung\u00fcltige Enderdragon-Policy '" + configuredDragon + "'."
        );
    }
}
