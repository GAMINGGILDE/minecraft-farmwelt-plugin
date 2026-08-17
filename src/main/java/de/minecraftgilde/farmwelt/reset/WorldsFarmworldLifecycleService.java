package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.event.WorldRegenerateEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

/** Keeps the external Worlds API out of the reset orchestration layer. */
public final class WorldsFarmworldLifecycleService
        implements FarmworldLifecycleService, Listener {

    private final WorldsAccess worldsAccess;
    private final EndDragonFightDataStore dragonFightDataStore;
    private final EndDragonFightCompatibility dragonFightCompatibility;
    private final Logger logger;
    private final Map<World, PendingDataReset> pendingDataResets =
            Collections.synchronizedMap(new IdentityHashMap<>());

    WorldsFarmworldLifecycleService(
            WorldsAccess worldsAccess,
            EndDragonFightDataStore dragonFightDataStore,
            EndDragonFightCompatibility dragonFightCompatibility,
            Logger logger
    ) {
        this.worldsAccess = Objects.requireNonNull(worldsAccess, "worldsAccess");
        this.dragonFightDataStore = Objects.requireNonNull(
                dragonFightDataStore,
                "dragonFightDataStore"
        );
        this.dragonFightCompatibility = Objects.requireNonNull(
                dragonFightCompatibility,
                "dragonFightCompatibility"
        );
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @SuppressWarnings("deprecation")
    public static WorldsFarmworldLifecycleService connect(Logger logger) {
        WorldsAccess worldsAccess = Objects.requireNonNull(
                WorldsAccess.access(),
                "WorldsAccess.access() hat null geliefert."
        );
        if (!worldsAccess.isEnabled()) {
            throw new IllegalStateException("Das Worlds-Plugin ist nicht aktiviert.");
        }
        return new WorldsFarmworldLifecycleService(
                worldsAccess,
                new EndDragonFightDataStore(Bukkit.getUnsafe().getDataVersion()),
                EndDragonFightCompatibility.runningServer(),
                logger
        );
    }

    public String pluginVersion() {
        return worldsAccess.getPluginMeta().getVersion();
    }

    @Override
    public CompletableFuture<World> regenerate(
            World world,
            FarmworldRegenerationOptions options
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(options, "options");

        PendingDataReset pendingReset = null;
        if (options.resetEndDragonFightData()) {
            try {
                dragonFightCompatibility.requireSupported();
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, exception.getMessage(), exception);
                return CompletableFuture.failedFuture(exception);
            }
            pendingReset = new PendingDataReset();
            synchronized (pendingDataResets) {
                if (pendingDataResets.putIfAbsent(world, pendingReset) != null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "DragonBattle-Datenreset für '" + world.getName()
                                    + "' ist bereits vorbereitet."
                    ));
                }
            }
        }

        final CompletableFuture<World> regeneration;
        try {
            regeneration = Objects.requireNonNull(
                    worldsAccess.regenerate(world, builder -> builder.seed(null)),
                    "WorldsAccess.regenerate(...) hat null geliefert."
            );
        } catch (RuntimeException exception) {
            removePendingReset(world, pendingReset);
            if (pendingReset != null) {
                rollbackDataReset(world, pendingReset, exception);
            }
            return CompletableFuture.failedFuture(exception);
        }

        if (pendingReset == null) {
            return regeneration;
        }
        PendingDataReset expectedReset = pendingReset;
        return regeneration.handle((regeneratedWorld, failure) -> finishDataReset(
                world,
                expectedReset,
                regeneratedWorld,
                failure
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void resetEndDragonFightData(WorldRegenerateEvent event) {
        PendingDataReset pendingReset = pendingDataResets.get(event.getWorld());
        if (pendingReset == null) {
            return;
        }

        pendingReset.regenerationEventReceived = true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareEndDragonFightData(WorldUnloadEvent event) {
        PendingDataReset pendingReset = pendingDataResets.get(event.getWorld());
        if (pendingReset == null || !pendingReset.regenerationEventReceived) {
            return;
        }

        pendingReset.unloadEventReceived = true;
        try {
            logger.info("DragonBattle-Saved-Data f\u00fcr '" + event.getWorld().getName()
                    + "' werden f\u00fcr Reset vorbereitet.");
            pendingReset.stagedData = dragonFightDataStore.stage(
                    event.getWorld().getWorldFolder().toPath()
            );
            if (pendingReset.stagedData.hadFightData()) {
                logger.info("Bestehende DragonBattle-Daten gesichert.");
            } else {
                logger.info("Für '" + event.getWorld().getName()
                        + "' waren keine alten DragonBattle-Daten vorhanden.");
            }
            logger.info("DragonBattle-Saved-Data erfolgreich zur\u00fcckgesetzt.");
        } catch (IOException | RuntimeException exception) {
            pendingReset.preparationFailure = exception;
            event.setCancelled(true);
            logger.log(
                    Level.SEVERE,
                    "DragonBattle-Daten für '" + event.getWorld().getName()
                            + "' konnten nicht sicher zurückgesetzt werden.",
                    exception
            );
        }
    }

    private World finishDataReset(
            World originalWorld,
            PendingDataReset pendingReset,
            World regeneratedWorld,
            Throwable regenerationFailure
    ) {
        removePendingReset(originalWorld, pendingReset);

        Throwable failure = regenerationFailure;
        if (failure == null && !pendingReset.regenerationEventReceived) {
            failure = new IllegalStateException(
                    "Worlds hat kein WorldRegenerateEvent für '"
                            + originalWorld.getName() + "' ausgelöst."
            );
        }
        if (failure == null && !pendingReset.unloadEventReceived) {
            failure = new IllegalStateException(
                    "Worlds hat kein WorldUnloadEvent für '"
                            + originalWorld.getName() + "' ausgelöst."
            );
        }
        if (failure == null && pendingReset.preparationFailure != null) {
            failure = pendingReset.preparationFailure;
        }

        if (failure != null) {
            rollbackDataReset(originalWorld, pendingReset, failure);
            throw asCompletionException(failure);
        }

        commitDataReset(originalWorld, pendingReset);
        return regeneratedWorld;
    }

    private void commitDataReset(World world, PendingDataReset pendingReset) {
        if (pendingReset.stagedData == null) {
            return;
        }
        try {
            dragonFightDataStore.commit(pendingReset.stagedData);
        } catch (IOException exception) {
            logger.log(
                    Level.WARNING,
                    "Temporäre Sicherung der alten DragonBattle-Daten für '"
                            + world.getName() + "' konnte nicht entfernt werden.",
                    exception
            );
        }
    }

    private void rollbackDataReset(
            World world,
            PendingDataReset pendingReset,
            Throwable failure
    ) {
        if (pendingReset.stagedData == null) {
            return;
        }
        try {
            dragonFightDataStore.rollback(pendingReset.stagedData);
            if (pendingReset.stagedData.hadFightData()) {
                logger.info("Sicherung der DragonBattle-Daten für '" + world.getName()
                        + "' nach fehlgeschlagener Worlds-Regeneration wiederhergestellt.");
            }
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            logger.log(
                    Level.SEVERE,
                    "Sicherung der DragonBattle-Daten für '" + world.getName()
                            + "' konnte nach fehlgeschlagener Worlds-Regeneration nicht "
                            + "wiederhergestellt werden.",
                    rollbackFailure
            );
        }
    }

    private void removePendingReset(World world, PendingDataReset pendingReset) {
        if (pendingReset == null) {
            return;
        }
        synchronized (pendingDataResets) {
            if (pendingDataResets.get(world) == pendingReset) {
                pendingDataResets.remove(world);
            }
        }
    }

    private CompletionException asCompletionException(Throwable failure) {
        return failure instanceof CompletionException completionException
                ? completionException
                : new CompletionException(failure);
    }

    private static final class PendingDataReset {

        private volatile boolean regenerationEventReceived;
        private volatile boolean unloadEventReceived;
        private volatile EndDragonFightDataStore.StagedData stagedData;
        private volatile Throwable preparationFailure;
    }
}
