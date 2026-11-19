package de.minecraftgilde.farmwelt.gui;

import de.minecraftgilde.farmwelt.command.GermanDurationFormatter;
import de.minecraftgilde.farmwelt.reset.FarmworldAvailabilityService;
import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Ergänzt die konfigurierten Beschreibungen beim Menüöffnen um den veröffentlichten Reset-Termin. */
public final class FarmweltMenuLore {

    private final FarmworldResetService resetService;
    private final FarmworldAvailabilityService availabilityService;
    private final Clock clock;
    private final DateTimeFormatter dateFormatter;
    private final GermanDurationFormatter durationFormatter = new GermanDurationFormatter();

    public FarmweltMenuLore(
            FarmworldResetService resetService,
            FarmworldAvailabilityService availabilityService,
            Clock clock,
            ZoneId zoneId
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.availabilityService = Objects.requireNonNull(availabilityService, "availabilityService");
        this.clock = Objects.requireNonNull(clock, "clock");
        dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'")
                .withZone(Objects.requireNonNull(zoneId, "zoneId"));
    }

    public List<Component> forWorld(FarmweltMenuItem menuItem) {
        List<Component> lore = new ArrayList<>();
        menuItem.lore().forEach(line -> lore.add(Component.text(line)));
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }

        if (!availabilityService.isFarmworldAvailable(menuItem.id())) {
            lore.add(Component.text("Reset läuft", NamedTextColor.RED));
            lore.add(Component.text("Teleport vorübergehend gesperrt", NamedTextColor.GRAY));
        } else {
            lore.addAll(resetLore(menuItem.id()));
            lore.add(Component.empty());
            lore.add(Component.text("Klicken zum Teleportieren", NamedTextColor.GREEN));
        }
        return List.copyOf(lore);
    }

    private List<Component> resetLore(String farmworldKey) {
        Optional<FarmworldResetConfig> config;
        Optional<FarmworldResetState> state;
        // Config und State müssen auch bei einem parallelen Reload zusammenpassen.
        synchronized (resetService) {
            config = resetService.getConfig(farmworldKey);
            state = resetService.getState(farmworldKey);
            if (config.flatMap(resetService::recoveryProblem).isPresent()) {
                return List.of(Component.text("Reset bis zur Klärung gesperrt", NamedTextColor.RED));
            }
        }

        if (config.isPresent() && !config.orElseThrow().enabled()) {
            return List.of(Component.text("Automatischer Reset deaktiviert", NamedTextColor.GRAY));
        }
        if (config.isEmpty() || state.isEmpty()
                || !farmworldKey.equals(state.orElseThrow().farmworldKey())) {
            return List.of(Component.text("Termin nicht verfügbar", NamedTextColor.GRAY));
        }

        FarmworldResetState schedule = state.orElseThrow();
        Duration remaining = Duration.between(clock.instant(), schedule.nextReset());
        if (remaining.isNegative() || remaining.isZero()) {
            return List.of(Component.text("Reset fällig", NamedTextColor.RED));
        }

        NamedTextColor color = remaining.compareTo(Duration.ofMinutes(5)) <= 0
                ? NamedTextColor.RED
                : remaining.compareTo(Duration.ofHours(1)) <= 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY;
        String approximation = remaining.compareTo(Duration.ofMinutes(1)) < 0 ? "" : "ca. ";
        return List.of(
                Component.text("Nächster Reset: " + dateFormatter.format(schedule.nextReset()), color),
                Component.text("Verbleibend: " + approximation + durationFormatter.format(remaining), color)
        );
    }
}
