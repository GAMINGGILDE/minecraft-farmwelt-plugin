package de.minecraftgilde.farmwelt.reset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResetNotificationMessageFormatterTest {

    @Test
    void replacesWorldTimeAndLocalizedNextResetPlaceholders() {
        ResetNotificationMessageFormatter formatter = new ResetNotificationMessageFormatter(
                ZoneId.of("Europe/Berlin")
        );

        assertEquals(
                "&eDie &6Test-Farmwelt&e wird in &61 Stunde&e zurückgesetzt (01.09.2026 12:00).",
                formatter.formatWarning(
                        "&eDie &6{world}&e wird in &6{time}&e zurückgesetzt ({next-reset}).",
                        "Test-Farmwelt",
                        Duration.ofHours(1),
                        Instant.parse("2026-09-01T10:00:00Z")
                )
        );
    }

    @Test
    void formatsConfiguredThresholdInsteadOfActualRemainingTime() {
        ResetNotificationMessageFormatter formatter = new ResetNotificationMessageFormatter(
                ZoneId.of("UTC")
        );

        assertEquals(
                "5 Minuten",
                formatter.formatWarning(
                        "{time}",
                        "Farmwelt",
                        Duration.ofMinutes(5),
                        Instant.parse("2026-09-01T12:00:00Z")
                )
        );
    }

    @Test
    void formatsLifecyclePlaceholdersAndUsesFallbackWithoutState() {
        ResetNotificationMessageFormatter formatter = new ResetNotificationMessageFormatter(
                ZoneId.of("UTC")
        );

        assertEquals(
                "Endfarm / 01.10.2026 12:00",
                formatter.formatLifecycle(
                        "{world} / {next-reset}",
                        "Endfarm",
                        Optional.of(Instant.parse("2026-10-01T12:00:00Z"))
                )
        );
        assertEquals(
                "Farmwelt / unbekannt",
                formatter.formatLifecycle(
                        "{world} / {next-reset}",
                        "Farmwelt",
                        Optional.empty()
                )
        );
    }
}
