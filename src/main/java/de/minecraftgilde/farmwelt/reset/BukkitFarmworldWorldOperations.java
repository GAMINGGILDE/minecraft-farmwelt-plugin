package de.minecraftgilde.farmwelt.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitFarmworldWorldOperations implements FarmworldWorldOperations {

    private static final Set<NamespacedKey> VANILLA_MAIN_DIMENSION_KEYS = Set.of(
            NamespacedKey.minecraft("overworld"),
            NamespacedKey.minecraft("the_nether"),
            NamespacedKey.minecraft("the_end")
    );

    private final JavaPlugin plugin;
    private final Supplier<Set<String>> resetWorldNames;

    public BukkitFarmworldWorldOperations(JavaPlugin plugin) {
        this(plugin, Set::of);
    }

    public BukkitFarmworldWorldOperations(
            JavaPlugin plugin,
            Supplier<Set<String>> resetWorldNames
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resetWorldNames = Objects.requireNonNull(resetWorldNames, "resetWorldNames");
    }

    @Override
    public WorldInspection inspect(FarmworldResetConfig resetConfig) {
        World world = plugin.getServer().getWorld(resetConfig.worldName());
        if (world == null) {
            return WorldInspection.unloaded();
        }

        return WorldInspection.loaded(
                world,
                toFarmworldType(world.getEnvironment()),
                isProtectedMainWorld(plugin.getServer(), world)
        );
    }

    @Override
    public CompletableFuture<Boolean> evacuatePlayers(World resetWorld) {
        if (resetWorld.getPlayers().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        World safeWorld = findSafeWorld(plugin.getServer(), resetWorld);
        if (safeWorld == null) {
            return CompletableFuture.completedFuture(false);
        }

        Location safeSpawn = safeWorld.getSpawnLocation();
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (Player player : List.copyOf(resetWorld.getPlayers())) {
            teleports.add(evacuatePlayer(player, resetWorld, safeSpawn));
        }

        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> teleports.stream().allMatch(this::completedSuccessfully));
    }

    @Override
    public boolean hasPlayers(World world) {
        return !world.getPlayers().isEmpty();
    }

    private CompletableFuture<Boolean> evacuatePlayer(
            Player player,
            World resetWorld,
            Location safeSpawn
    ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean scheduled = player.getScheduler().execute(
                plugin,
                () -> {
                    if (player.getWorld() != resetWorld) {
                        result.complete(true);
                        return;
                    }

                    player.teleportAsync(safeSpawn.clone(), TeleportCause.PLUGIN)
                            .whenComplete((teleported, failure) -> {
                                if (failure != null) {
                                    result.completeExceptionally(failure);
                                } else {
                                    result.complete(Boolean.TRUE.equals(teleported));
                                }
                            });
                },
                () -> result.complete(false),
                1L
        );
        if (!scheduled) {
            result.complete(false);
        }
        return result;
    }

    private boolean completedSuccessfully(CompletableFuture<Boolean> future) {
        return !future.isCompletedExceptionally() && Boolean.TRUE.equals(future.getNow(false));
    }

    private World findSafeWorld(Server server, World resetWorld) {
        for (World world : server.getWorlds()) {
            if (world.getKey().equals(NamespacedKey.minecraft("overworld"))
                    && isSafeDestination(world, resetWorld)) {
                return world;
            }
        }

        for (World world : server.getWorlds()) {
            if (isProtectedMainWorld(server, world) && isSafeDestination(world, resetWorld)) {
                return world;
            }
        }

        for (World world : server.getWorlds()) {
            if (isSafeDestination(world, resetWorld)) {
                return world;
            }
        }
        return null;
    }

    private boolean isSafeDestination(World world, World resetWorld) {
        return world.getEnvironment() == World.Environment.NORMAL
                && world != resetWorld
                && !resetWorldNames.get().contains(world.getName());
    }

    private boolean isProtectedMainWorld(Server server, World world) {
        List<World> loadedWorlds = server.getWorlds();
        return (!loadedWorlds.isEmpty() && loadedWorlds.getFirst() == world)
                || VANILLA_MAIN_DIMENSION_KEYS.contains(world.getKey());
    }

    private FarmworldType toFarmworldType(World.Environment environment) {
        return switch (environment) {
            case NORMAL -> FarmworldType.OVERWORLD;
            case NETHER -> FarmworldType.NETHER;
            case THE_END -> FarmworldType.END;
            case CUSTOM -> throw new IllegalStateException(
                    "Benutzerdefinierte Dimensionen werden nicht als Farmwelt unterstützt."
            );
        };
    }
}
