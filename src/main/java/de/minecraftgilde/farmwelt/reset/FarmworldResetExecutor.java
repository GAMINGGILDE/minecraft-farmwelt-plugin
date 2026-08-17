package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CompletableFuture;

/** Command-facing API of the reset engine. */
public interface FarmworldResetExecutor extends FarmworldAvailabilityService {

    CompletableFuture<ResetResult> reset(String farmworldKey);

    boolean isResetRunning(String farmworldKey);
}
