package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
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

    boolean isResetRunning(String farmworldKey);
}
