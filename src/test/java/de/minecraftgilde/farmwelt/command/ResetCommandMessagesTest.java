package de.minecraftgilde.farmwelt.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.ResetResult;
import de.minecraftgilde.farmwelt.reset.ResetStatus;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResetCommandMessagesTest {

    @ParameterizedTest
    @MethodSource("mappedStatuses")
    void mapsResetStatusToClearAdminMessage(ResetStatus status, String expectedText) {
        ResetResult result = new ResetResult("overworld", "test_farmwelt", status, "technisch", null);

        List<String> messages = ResetCommandMessages.forResult(result);

        assertTrue(messages.stream().anyMatch(message -> message.contains(expectedText)), messages::toString);
    }

    private static Stream<Arguments> mappedStatuses() {
        return Stream.of(
                Arguments.of(ResetStatus.SUCCESS, "erfolgreich zurückgesetzt"),
                Arguments.of(ResetStatus.NOT_CONFIGURED, "nicht konfiguriert"),
                Arguments.of(ResetStatus.DISABLED, "deaktiviert"),
                Arguments.of(ResetStatus.ALREADY_RUNNING, "bereits ein Reset"),
                Arguments.of(ResetStatus.INVALID_CONFIGURATION, "ungültig"),
                Arguments.of(ResetStatus.WORLD_NOT_FOUND, "nicht gefunden"),
                Arguments.of(ResetStatus.WORLD_NOT_LOADED, "derzeit nicht geladen"),
                Arguments.of(ResetStatus.PROTECTED_WORLD, "als Hauptwelt geschützt"),
                Arguments.of(ResetStatus.EVACUATION_FAILED, "evakuiert"),
                Arguments.of(ResetStatus.REGENERATE_FAILED, "durch Worlds nicht neu generiert"),
                Arguments.of(ResetStatus.STATE_SAVE_FAILED, "Reset-State konnte nicht gespeichert"),
                Arguments.of(ResetStatus.INTERNAL_ERROR, "internen Fehler")
        );
    }
}
