package de.minecraftgilde.farmwelt.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitFarmworldWorldOperations implements FarmworldWorldOperations {

    private static final String PREFERRED_SAFE_WORLD = "world";
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
                world.getWorldFolder().toPath().toAbsolutePath().normalize(),
                toFarmworldType(world.getEnvironment())
        );
    }

    @Override
    public CompletableFuture<Boolean> evacuatePlayers(FarmworldResetConfig resetConfig) {
        Server server = plugin.getServer();
        World resetWorld = server.getWorld(resetConfig.worldName());
        if (resetWorld == null || resetWorld.getPlayers().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        World safeWorld = findSafeWorld(server, resetConfig.worldName());
        if (safeWorld == null) {
            return CompletableFuture.completedFuture(false);
        }

        Location safeSpawn = safeWorld.getSpawnLocation();
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (Player player : List.copyOf(resetWorld.getPlayers())) {
            teleports.add(evacuatePlayer(player, resetConfig.worldName(), safeSpawn));
        }

        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> teleports.stream().allMatch(this::completedSuccessfully));
    }

    @Override
    public boolean hasPlayers(FarmworldResetConfig resetConfig) {
        World world = plugin.getServer().getWorld(resetConfig.worldName());
        return world != null && !world.getPlayers().isEmpty();
    }

    @Override
    public boolean unload(FarmworldResetConfig resetConfig) {
        World world = plugin.getServer().getWorld(resetConfig.worldName());
        if (world == null) {
            return true;
        }
        if (!world.getPlayers().isEmpty()) {
            return false;
        }

        // Saving is intentionally disabled: every file is deleted immediately after unloading.
        return plugin.getServer().unloadWorld(world, false)
                && plugin.getServer().getWorld(resetConfig.worldName()) == null;
    }

    @Override
    public boolean isLoaded(FarmworldResetConfig resetConfig) {
        return plugin.getServer().getWorld(resetConfig.worldName()) != null;
    }

    @Override
    public boolean createAndValidate(FarmworldResetConfig resetConfig) {
        Server server = plugin.getServer();
        if (server.getWorld(resetConfig.worldName()) != null) {
            return false;
        }

        World.Environment expectedEnvironment = toEnvironment(resetConfig.farmworldType());
        World createdWorld = server.createWorld(
                new WorldCreator(resetConfig.worldName()).environment(expectedEnvironment)
        );
        if (createdWorld == null) {
            return false;
        }

        World loadedWorld = server.getWorld(resetConfig.worldName());
        return loadedWorld != null
                && loadedWorld == createdWorld
                && loadedWorld.getEnvironment() == expectedEnvironment;
    }

    private CompletableFuture<Boolean> evacuatePlayer(
            Player player,
            String resetWorldName,
            Location safeSpawn
    ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean scheduled = player.getScheduler().execute(
                plugin,
                () -> {
                    if (!player.getWorld().getName().equals(resetWorldName)) {
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

    private World findSafeWorld(Server server, String resetWorldName) {
        World preferredWorld = server.getWorld(PREFERRED_SAFE_WORLD);
        if (isSafeDestination(preferredWorld, resetWorldName)) {
            return preferredWorld;
        }

        for (World world : server.getWorlds()) {
            if (isSafeDestination(world, resetWorldName)) {
                return world;
            }
        }
        return null;
    }

    private boolean isSafeDestination(World world, String resetWorldName) {
        return world != null
                && world.getEnvironment() == World.Environment.NORMAL
                && !world.getName().equals(resetWorldName)
                && !resetWorldNames.get().contains(world.getName());
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

    private World.Environment toEnvironment(FarmworldType farmworldType) {
        return switch (farmworldType) {
            case OVERWORLD -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        };
    }
}
