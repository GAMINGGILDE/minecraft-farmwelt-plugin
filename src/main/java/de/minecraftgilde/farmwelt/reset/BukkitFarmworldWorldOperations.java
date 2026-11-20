package de.minecraftgilde.farmwelt.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

public final class BukkitFarmworldWorldOperations implements FarmworldWorldOperations {

    private static final Set<NamespacedKey> VANILLA_MAIN_DIMENSION_KEYS = Set.of(
            NamespacedKey.minecraft("overworld"),
            NamespacedKey.minecraft("the_nether"),
            NamespacedKey.minecraft("the_end")
    );

    private final Plugin plugin;
    private final FoliaFarmweltScheduler scheduler;
    private final Supplier<Set<String>> resetWorldNames;

    public BukkitFarmworldWorldOperations(Plugin plugin) {
        this(plugin, Set::of);
    }

    public BukkitFarmworldWorldOperations(
            Plugin plugin,
            Supplier<Set<String>> resetWorldNames
    ) {
        this(plugin, resetWorldNames, new FoliaFarmweltScheduler(plugin));
    }

    public BukkitFarmworldWorldOperations(Plugin plugin, Supplier<Set<String>> resetWorldNames,
                                          FoliaFarmweltScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
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
                isProtectedMainWorld(plugin.getServer().getWorlds(), world)
        );
    }

    @Override
    public CompletableFuture<FarmworldEvacuationResult> evacuatePlayers(World resetWorld) {
        if (resetWorld.getPlayers().isEmpty()) {
            return CompletableFuture.completedFuture(
                    FarmworldEvacuationResult.completed(List.of())
            );
        }

        World safeWorld = findSafeWorld(plugin.getServer(), resetWorld);
        if (safeWorld == null) {
            return CompletableFuture.completedFuture(
                    FarmworldEvacuationResult.failed(List.of(), null)
            );
        }

        Location safeSpawn = safeWorld.getSpawnLocation();
        List<CompletableFuture<PlayerEvacuationAttempt>> teleports = new ArrayList<>();
        for (Player player : List.copyOf(resetWorld.getPlayers())) {
            teleports.add(evacuatePlayer(player, resetWorld, safeSpawn));
        }

        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .handle((ignored, aggregateFailure) -> aggregate(teleports));
    }

    @Override
    public boolean hasPlayers(World world) {
        return !world.getPlayers().isEmpty();
    }

    private CompletableFuture<PlayerEvacuationAttempt> evacuatePlayer(
            Player player,
            World resetWorld,
            Location safeSpawn
    ) {
        return scheduler.runEntity(player, () -> {
            if (player.getWorld() != resetWorld) {
                return CompletableFuture.completedFuture(PlayerEvacuationAttempt.notRequired());
            }
            var teleport = player.teleportAsync(safeSpawn.clone(), TeleportCause.PLUGIN);
            if (teleport == null) {
                throw new FarmworldEvacuationResult.UnconfirmedTeleportException(
                        "teleportAsync hat kein Future geliefert. Teleportende ungeklärt.", null);
            }
            return teleport.handle((success, failure) -> {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof CancellationException) {
                        throw new FarmworldEvacuationResult.UnconfirmedTeleportException(
                                "Teleport-Future wurde abgebrochen. Teleportende ungeklärt.", cause);
                    }
                    throw new CompletionException(cause);
                }
                return Boolean.TRUE.equals(success) ? PlayerEvacuationAttempt.evacuated(player)
                        : PlayerEvacuationAttempt.failed();
            });
        }, accepted -> {
            throw new IllegalStateException(accepted ? "Spieler vor Evakuierung retired."
                    : "Entity-Scheduler hat die Evakuierung abgelehnt.");
        }).thenCompose(attempt -> attempt);
    }

    private FarmworldEvacuationResult aggregate(
            List<CompletableFuture<PlayerEvacuationAttempt>> teleports
    ) {
        List<Player> evacuatedPlayers = new ArrayList<>();
        Throwable firstFailure = null;
        boolean successful = true;
        for (CompletableFuture<PlayerEvacuationAttempt> teleport : teleports) {
            try {
                PlayerEvacuationAttempt attempt = teleport.join();
                successful &= attempt.successful();
                if (attempt.evacuatedPlayer() != null) {
                    evacuatedPlayers.add(attempt.evacuatedPlayer());
                }
            } catch (CompletionException | CancellationException exception) {
                successful = false;
                Throwable cause = unwrap(exception);
                if (firstFailure == null || cause instanceof FarmworldEvacuationResult.UnconfirmedTeleportException) {
                    firstFailure = cause;
                }
            }
        }
        return successful
                ? FarmworldEvacuationResult.completed(evacuatedPlayers)
                : FarmworldEvacuationResult.failed(evacuatedPlayers, firstFailure);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private World findSafeWorld(Server server, World resetWorld) {
        for (World world : server.getWorlds()) {
            if (world.getKey().equals(NamespacedKey.minecraft("overworld"))
                    && isSafeDestination(world, resetWorld)) {
                return world;
            }
        }

        List<World> loadedWorlds = server.getWorlds();
        for (World world : loadedWorlds) {
            if (isProtectedMainWorld(loadedWorlds, world) && isSafeDestination(world, resetWorld)) {
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
                && resetWorldNames.get().stream().noneMatch(world.getName()::equalsIgnoreCase);
    }

    static boolean isRegisteredEndFarm(Server server, List<World> loadedWorlds, World world) {
        return world.getEnvironment() == World.Environment.THE_END
                && server.getWorld(world.getName()) == world
                && !isProtectedMainWorld(loadedWorlds, world);
    }

    private static boolean isProtectedMainWorld(List<World> loadedWorlds, World world) {
        return loadedWorlds.isEmpty() || loadedWorlds.getFirst() == world
                || world.getKey() == null || VANILLA_MAIN_DIMENSION_KEYS.contains(world.getKey());
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

    private record PlayerEvacuationAttempt(boolean successful, Player evacuatedPlayer) {

        private static PlayerEvacuationAttempt notRequired() {
            return new PlayerEvacuationAttempt(true, null);
        }

        private static PlayerEvacuationAttempt evacuated(Player player) {
            return new PlayerEvacuationAttempt(true, player);
        }

        private static PlayerEvacuationAttempt failed() {
            return new PlayerEvacuationAttempt(false, null);
        }
    }
}
