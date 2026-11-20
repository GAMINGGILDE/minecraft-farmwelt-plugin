package de.minecraftgilde.farmwelt.reset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Zentrale API zum Starten der vollständigen Reset-Pipeline. */
public interface FarmworldResetExecutor extends FarmworldAvailabilityService {

    CompletableFuture<ResetResult> reset(String farmworldKey);

    default CompletableFuture<ResetResult> reset(String farmworldKey, ResetOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.allowEnderDragon()) {
            throw new UnsupportedOperationException("Dieser Reset-Executor unterst\u00fctzt keine Optionen.");
        }
        return reset(farmworldKey);
    }

    default CompletableFuture<ResetResult> resetIfDue(String farmworldKey, Instant evaluatedAt) {
        return reset(farmworldKey);
    }

    default CompletableFuture<ResetResult> catchUpIfDue(String key, Instant evaluatedAt) {
        return resetIfDue(key, evaluatedAt);
    }

    default Optional<String> runningDiagnosis(String key) {
        return Optional.empty();
    }

    default void diagnoseRunningResets() {}

    boolean isResetRunning(String farmworldKey);
}
