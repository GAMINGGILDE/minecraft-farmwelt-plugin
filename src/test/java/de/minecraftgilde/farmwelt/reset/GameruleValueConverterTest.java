package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GameruleValueConverterTest {

    private final GameruleValueConverter converter = new GameruleValueConverter();

    @Test
    void convertsBooleanAndIntegerGamerulesToTheirApiTypes() {
        assertEquals(Boolean.FALSE, converter.convert(false, Boolean.class));
        assertEquals(Boolean.TRUE, converter.convert("true", Boolean.class));
        assertEquals(Integer.valueOf(50), converter.convert(50, Integer.class));
        assertEquals(Integer.valueOf(50), converter.convert("50", Integer.class));
    }

    @Test
    void rejectsValuesThatDoNotMatchTheGameruleType() {
        assertThrows(IllegalArgumentException.class, () -> converter.convert("yes", Boolean.class));
        assertThrows(IllegalArgumentException.class, () -> converter.convert("many", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> converter.convert(1.5D, Integer.class));
    }
}
