package de.minecraftgilde.farmwelt.reset;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.thenextlvl.worlds.WorldsAccess;
import org.bukkit.World;

/** Keeps the external Worlds API out of the reset orchestration layer. */
public final class WorldsFarmworldLifecycleService implements FarmworldLifecycleService {

    private final WorldsAccess worldsAccess;

    WorldsFarmworldLifecycleService(WorldsAccess worldsAccess) {
        this.worldsAccess = Objects.requireNonNull(worldsAccess, "worldsAccess");
    }

    public static WorldsFarmworldLifecycleService connect() {
        WorldsAccess worldsAccess = Objects.requireNonNull(
                WorldsAccess.access(),
                "WorldsAccess.access() hat null geliefert."
        );
        if (!worldsAccess.isEnabled()) {
            throw new IllegalStateException("Das Worlds-Plugin ist nicht aktiviert.");
        }
        return new WorldsFarmworldLifecycleService(worldsAccess);
    }

    public String pluginVersion() {
        return worldsAccess.getPluginMeta().getVersion();
    }

    @Override
    public CompletableFuture<World> regenerate(World world) {
        CompletableFuture<World> regeneration = worldsAccess.regenerate(
                Objects.requireNonNull(world, "world"),
                builder -> builder.seed(null)
        );
        return Objects.requireNonNull(
                regeneration,
                "WorldsAccess.regenerate(...) hat null geliefert."
        );
    }
}
