package de.minecraftgilde.farmwelt.reset;

/** Lightweight boundary used by teleport flows that must not depend on the reset engine itself. */
@FunctionalInterface
public interface FarmworldAvailabilityService {

    boolean isFarmworldAvailable(String farmworldKey);
}
