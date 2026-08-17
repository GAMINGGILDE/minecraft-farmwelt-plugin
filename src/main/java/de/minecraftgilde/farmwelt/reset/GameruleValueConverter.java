package de.minecraftgilde.farmwelt.reset;

import java.util.Locale;
import java.util.Objects;

/** Converts YAML scalar values to the type declared by the Bukkit gamerule registry. */
public final class GameruleValueConverter {

    public Object convert(Object configuredValue, Class<?> targetType) {
        Objects.requireNonNull(configuredValue, "configuredValue");
        Objects.requireNonNull(targetType, "targetType");

        if (targetType == Boolean.class) {
            return toBoolean(configuredValue);
        }
        if (targetType == Integer.class) {
            return toInteger(configuredValue);
        }
        if (targetType.isInstance(configuredValue)) {
            return configuredValue;
        }
        throw new IllegalArgumentException(
                "Nicht unterst\u00fctzter Gamerule-Typ: " + targetType.getName()
        );
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw invalidValue(value, "boolean");
            };
        }
        throw invalidValue(value, "boolean");
    }

    private Integer toInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long longValue
                && longValue >= Integer.MIN_VALUE
                && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException exception) {
                throw invalidValue(value, "integer");
            }
        }
        throw invalidValue(value, "integer");
    }

    private IllegalArgumentException invalidValue(Object value, String expectedType) {
        return new IllegalArgumentException(
                "Gamerule-Wert '" + value + "' ist kein g\u00fcltiger " + expectedType + "-Wert."
        );
    }
}
