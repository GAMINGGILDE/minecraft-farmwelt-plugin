package de.minecraftgilde.farmwelt.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.ResetStateRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FarmweltMenuLoreTest {

    @TempDir
    static Path dataDirectory;

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private final InMemoryRepository repository = new InMemoryRepository();
    private final FarmworldResetService resetService = new FarmworldResetService(
            repository, dataDirectory, CLOCK, Logger.getAnonymousLogger()
    );
    private final Set<String> resetting = new HashSet<>();
    private final FarmweltMenuLore menuLore = new FarmweltMenuLore(
            resetService, key -> !resetting.contains(key), CLOCK, ZONE
    );

    @Test
    void appendsPersistedDateAndRemainingTimeWithoutChangingConfiguredLore() {
        load("overworld", true, NOW.plus(Duration.ofDays(3)).plus(Duration.ofHours(4)));
        FarmweltMenuItem item = item("overworld", List.of("Normale Farmwelt", "Eigene Beschreibung"));

        assertEquals(List.of(
                "Normale Farmwelt",
                "Eigene Beschreibung",
                "",
                "Nächster Reset: 09.09.2026, 18:00 Uhr",
                "Verbleibend: ca. 3 Tage, 4 Stunden",
                "",
                "Klicken zum Teleportieren"
        ), text(menuLore.forWorld(item)));
        assertEquals(List.of("Normale Farmwelt", "Eigene Beschreibung"), item.lore());
        assertEquals(0, repository.saves);
    }

    @ParameterizedTest
    @CsvSource({"3601,gray", "3600,yellow", "301,yellow", "300,red", "60,red", "1,red"})
    void highlightsBothResetLinesAtTheWarningBoundaries(long seconds, String colorName) {
        load("overworld", true, NOW.plusSeconds(seconds));

        List<Component> lore = menuLore.forWorld(item("overworld"));

        NamedTextColor expectedColor = NamedTextColor.NAMES.value(colorName);
        assertEquals(expectedColor, lore.get(0).color());
        assertEquals(expectedColor, lore.get(1).color());
    }

    @Test
    void showsLessThanOneMinuteWithoutRoundingToZero() {
        load("overworld", true, NOW.plusSeconds(59));

        assertTrue(text(menuLore.forWorld(item("overworld"))).contains("Verbleibend: unter 1 Minute"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -86400})
    void dueScheduleShowsDueInsteadOfRunningOrANegativeCountdown(long seconds) {
        load("overworld", true, NOW.plusSeconds(seconds));

        assertEquals(List.of("Reset fällig", "", "Klicken zum Teleportieren"),
                text(menuLore.forWorld(item("overworld"))));
        assertEquals(0, repository.saves);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void runningResetTakesPriorityOverScheduleAndDisabledConfiguration(boolean enabled) {
        load("overworld", enabled, NOW.minusSeconds(1));
        resetting.add("overworld");

        List<Component> lore = menuLore.forWorld(item("overworld"));

        assertEquals(List.of("Reset läuft", "Teleport vorübergehend gesperrt"), text(lore));
        assertEquals(NamedTextColor.RED, lore.getFirst().color());
    }

    @Test
    void runningResetRemainsVisibleAfterConfigurationWasRemoved() {
        resetting.add("overworld");

        assertEquals(List.of("Reset läuft", "Teleport vorübergehend gesperrt"),
                text(menuLore.forWorld(item("overworld"))));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 86400})
    void disabledScheduleDoesNotAdvertiseAHistoricalDate(long seconds) {
        load("overworld", false, NOW.plusSeconds(seconds));

        assertEquals(List.of("Automatischer Reset deaktiviert", "", "Klicken zum Teleportieren"),
                text(menuLore.forWorld(item("overworld"))));
    }

    @Test
    void disabledScheduleWithoutStateIsStillExplicitlyDisabled() {
        assertTrue(resetService.reload(List.of(config("overworld", false, Duration.ofDays(30)))));

        assertEquals("Automatischer Reset deaktiviert",
                text(menuLore.forWorld(item("overworld"))).getFirst());
    }

    @Test
    void entryWithoutResetConfigurationHasAnUnavailableDate() {
        assertEquals(List.of("Termin nicht verfügbar", "", "Klicken zum Teleportieren"),
                text(menuLore.forWorld(item("custom-world"))));
    }

    @Test
    void mismatchedStateDoesNotAdvertiseAnotherWorldsDate() {
        repository.states = Map.of("overworld", state("nether", NOW.plusSeconds(3600)));
        assertTrue(resetService.reload(List.of(config("overworld", true, Duration.ofDays(30)))));

        assertEquals("Termin nicht verfügbar", text(menuLore.forWorld(item("overworld"))).getFirst());
    }

    @Test
    void usesLogicalIdForScheduleAndAvailabilityEvenWithDifferentNames() {
        load("nether", true, NOW.plusSeconds(3600));
        resetting.add("overworld");

        assertEquals("Nächster Reset: 06.09.2026, 15:00 Uhr",
                text(menuLore.forWorld(item("nether"))).getFirst());
        resetting.add("nether");
        assertEquals("Reset läuft", text(menuLore.forWorld(item("nether"))).getFirst());
    }

    @Test
    void reopeningAfterIntervalReloadKeepsThePersistedDate() {
        load("overworld", true, NOW.plus(Duration.ofDays(3)));
        List<Component> beforeReload = menuLore.forWorld(item("overworld"));

        assertTrue(resetService.reload(List.of(config("overworld", true, Duration.ofDays(60)))));

        assertEquals(beforeReload, menuLore.forWorld(item("overworld")));
    }

    @Test
    void reopeningAfterCompletionShowsTheNewlyPublishedDate() throws IOException {
        load("overworld", true, NOW.minusSeconds(1));
        assertEquals("Reset fällig", text(menuLore.forWorld(item("overworld"))).getFirst());
        resetting.add("overworld");
        assertEquals("Reset läuft", text(menuLore.forWorld(item("overworld"))).getFirst());

        resetService.completeReset("overworld");
        resetting.remove("overworld");

        assertEquals("Nächster Reset: 06.10.2026, 14:00 Uhr",
                text(menuLore.forWorld(item("overworld"))).getFirst());
    }

    @Test
    void failedPersistenceDoesNotAdvertiseAnUnpublishedDate() {
        load("overworld", true, NOW.minusSeconds(1));
        repository.failSaves = true;

        assertThrows(IOException.class, () -> resetService.completeReset("overworld"));

        assertEquals("Reset fällig", text(menuLore.forWorld(item("overworld"))).getFirst());
    }

    @Test
    void recoveryReplacesCountdownAndKeepsTeleportHint(@TempDir Path data) throws IOException {
        java.nio.file.Files.writeString(data.resolve("reset-recovery.version"), "1\n");
        var service = new FarmworldResetService(repository, data, CLOCK, Logger.getAnonymousLogger());
        assertTrue(service.reload(List.of(config("overworld", true, Duration.ofDays(30)))));
        var lore = new FarmweltMenuLore(service, key -> true, CLOCK, ZONE);
        assertEquals(List.of("Reset bis zur Klärung gesperrt", "", "Klicken zum Teleportieren"),
                text(lore.forWorld(item("overworld"))));
    }

    @Test
    void formatsWinterTimeInTheSameServerZone() {
        load("overworld", true, Instant.parse("2026-12-01T12:00:00Z"));

        assertEquals("Nächster Reset: 01.12.2026, 13:00 Uhr",
                text(menuLore.forWorld(item("overworld"))).getFirst());
    }

    private void load(String key, boolean enabled, Instant nextReset) {
        repository.states = Map.of(key, state(key, nextReset));
        assertTrue(resetService.reload(List.of(config(key, enabled, Duration.ofDays(30)))));
    }

    private FarmworldResetConfig config(String key, boolean enabled, Duration interval) {
        return new FarmworldResetConfig(key, "resource_dimension_3", enabled, interval);
    }

    private FarmworldResetState state(String key, Instant nextReset) {
        return new FarmworldResetState(key, Optional.empty(), nextReset);
    }

    private FarmweltMenuItem item(String key) {
        return item(key, List.of());
    }

    private FarmweltMenuItem item(String key, List<String> lore) {
        return new FarmweltMenuItem(key, "Goldgrube", null, 11, lore,
                new TeleportAction("command", "player", "rtp world resource_dimension_3"));
    }

    private List<String> text(List<Component> lore) {
        return lore.stream().map(component -> ((TextComponent) component).content()).toList();
    }

    private static final class InMemoryRepository implements ResetStateRepository {

        private Map<String, FarmworldResetState> states = Map.of();
        private int saves;
        private boolean failSaves;

        @Override
        public Map<String, FarmworldResetState> load() {
            return states;
        }

        @Override
        public void save(Map<String, FarmworldResetState> states) throws IOException {
            if (failSaves) {
                throw new IOException("Simulierter Persistenzfehler");
            }
            this.states = Map.copyOf(states);
            saves++;
        }
    }
}
