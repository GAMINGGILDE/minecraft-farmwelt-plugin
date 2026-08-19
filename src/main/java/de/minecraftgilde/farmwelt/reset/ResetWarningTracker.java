package de.minecraftgilde.farmwelt.reset;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Hält ausschließlich den transienten Versandzustand der Countdown-Warnungen. */
public final class ResetWarningTracker {

    private final Map<String, WarningCycle> cycles = new HashMap<>();

    /**
     * Ermittelt neu fällige Warnschwellen. Beim ersten Snapshot eines Zyklus wird höchstens
     * die zeitlich nächste bereits erreichte Schwelle zurückgegeben und ältere werden übersprungen.
     */
    public synchronized List<Duration> evaluate(
            String farmworldKey,
            Instant nextReset,
            Instant now,
            List<Duration> configuredWarnings
    ) {
        Objects.requireNonNull(farmworldKey, "farmworldKey");
        Objects.requireNonNull(nextReset, "nextReset");
        Objects.requireNonNull(now, "now");
        List<Duration> warnings = normalize(configuredWarnings);

        if (warnings.isEmpty()) {
            cycles.remove(farmworldKey);
            return List.of();
        }
        if (!now.isBefore(nextReset)) {
            return List.of();
        }

        Duration remaining = Duration.between(now, nextReset);
        WarningCycle cycle = cycles.get(farmworldKey);
        if (cycle == null || !cycle.nextReset.equals(nextReset)) {
            WarningCycle newCycle = new WarningCycle(nextReset, warnings);
            cycles.put(farmworldKey, newCycle);
            return initializeCycle(newCycle, remaining);
        }

        if (!cycle.configuredWarnings.equals(warnings)) {
            cycle.configuredWarnings = warnings;
            return initializeChangedConfiguration(cycle, remaining);
        }

        List<Duration> dueWarnings = reachedWarnings(warnings, remaining).stream()
                .filter(warning -> !cycle.sentWarnings.contains(warning))
                .toList();
        cycle.sentWarnings.addAll(dueWarnings);
        return dueWarnings;
    }

    public synchronized void retainFarmworlds(Set<String> activeFarmworldKeys) {
        Objects.requireNonNull(activeFarmworldKeys, "activeFarmworldKeys");
        cycles.keySet().retainAll(activeFarmworldKeys);
    }

    private List<Duration> initializeCycle(WarningCycle cycle, Duration remaining) {
        List<Duration> reachedWarnings = reachedWarnings(
                cycle.configuredWarnings,
                remaining
        );
        cycle.sentWarnings.addAll(reachedWarnings);
        if (reachedWarnings.isEmpty()) {
            return List.of();
        }
        return List.of(reachedWarnings.getLast());
    }

    private List<Duration> initializeChangedConfiguration(
            WarningCycle cycle,
            Duration remaining
    ) {
        List<Duration> reachedWarnings = reachedWarnings(
                cycle.configuredWarnings,
                remaining
        );
        if (reachedWarnings.isEmpty()) {
            return List.of();
        }

        Duration closestWarning = reachedWarnings.getLast();
        boolean alreadySent = cycle.sentWarnings.contains(closestWarning);
        cycle.sentWarnings.addAll(reachedWarnings);
        return alreadySent ? List.of() : List.of(closestWarning);
    }

    private List<Duration> reachedWarnings(List<Duration> warnings, Duration remaining) {
        return warnings.stream()
                .filter(warning -> remaining.compareTo(warning) <= 0)
                .toList();
    }

    private List<Duration> normalize(List<Duration> configuredWarnings) {
        Objects.requireNonNull(configuredWarnings, "configuredWarnings");
        TreeSet<Duration> normalized = new TreeSet<>(Comparator.reverseOrder());
        for (Duration warning : configuredWarnings) {
            Objects.requireNonNull(warning, "warning");
            if (warning.isZero() || warning.isNegative()) {
                throw new IllegalArgumentException("Warning-Zeitpunkte müssen positiv sein.");
            }
            normalized.add(warning);
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private static final class WarningCycle {

        private final Instant nextReset;
        private final Set<Duration> sentWarnings = new HashSet<>();
        private List<Duration> configuredWarnings;

        private WarningCycle(Instant nextReset, List<Duration> configuredWarnings) {
            this.nextReset = nextReset;
            this.configuredWarnings = configuredWarnings;
        }
    }
}
