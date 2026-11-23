package de.minecraftgilde.farmwelt.reset;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.thenextlvl.worlds.WorldOperationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.event.WorldRegenerateEvent;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

/** Keeps the external Worlds API out of the reset orchestration layer. */
public final class WorldsFarmworldLifecycleService
        implements FarmworldLifecycleService, Listener {

    private final Server server;
    private final WorldsAccess worldsAccess;
    private final EndDragonFightDataStore dragonFightDataStore;
    private final EndDragonFightCompatibility dragonFightCompatibility;
    private final Logger logger;
    private final FarmweltScheduler scheduler;
    private volatile boolean stopped;
    private final Map<World, PendingDataReset> pendingDataResets =
            Collections.synchronizedMap(new IdentityHashMap<>());

    WorldsFarmworldLifecycleService(
            Server server,
            WorldsAccess worldsAccess,
            EndDragonFightDataStore dragonFightDataStore,
            EndDragonFightCompatibility dragonFightCompatibility,
            FarmweltScheduler scheduler, Logger logger
    ) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.server = Objects.requireNonNull(server, "server");
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
    public static WorldsFarmworldLifecycleService connect(Logger logger, FarmweltScheduler scheduler) {
        WorldsAccess worldsAccess = Objects.requireNonNull(
                WorldsAccess.access(),
                "WorldsAccess.access() hat null geliefert."
        );
        if (!worldsAccess.isEnabled()) {
            throw new IllegalStateException("Das Worlds-Plugin ist nicht aktiviert.");
        }
        return new WorldsFarmworldLifecycleService(
                Bukkit.getServer(),
                worldsAccess,
                new EndDragonFightDataStore(Bukkit.getUnsafe().getDataVersion()),
                EndDragonFightCompatibility.runningServer(), scheduler,
                logger
        );
    }

    public String pluginVersion() {
        return worldsAccess.getPluginMeta().getVersion();
    }

    public void stop() {
        stopped = true;
    }

    @Override
    public CompletableFuture<World> regenerate(
            World world,
            FarmworldRegenerationOptions options
    ) {
        if (options.resetsEndDragonFightData()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "DragonBattle-Staging erfordert den Eigentümer des vorbereiteten Recovery-Versuchs."));
        }
        return regenerate(world, options, null, null);
    }

    @Override
    public CompletableFuture<World> regenerate(World world, FarmworldRegenerationOptions options,
            UUID attemptId, Consumer<BackupCleanup> afterReceipt) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(options, "options");

        PendingDataReset pendingReset = null;
        if (options.resetsEndDragonFightData()) {
            Objects.requireNonNull(afterReceipt, "afterReceipt");
            try {
                dragonFightCompatibility.requireSupported();
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, exception.getMessage(), exception);
                return CompletableFuture.failedFuture(exception);
            }
            pendingReset = new PendingDataReset(options.endDragonFightDataMode(), attemptId, world.getName());
            synchronized (pendingDataResets) {
                if (pendingDataResets.putIfAbsent(world, pendingReset) != null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "DragonBattle-Datenreset für '" + world.getName()
                                    + "' ist bereits vorbereitet."
                    ));
                }
            }
        }

        try {
            logger.info("Regeneration von '" + world.getName() + "' über Worlds mit zufälligem Seed gestartet.");
            var regeneration = Objects.requireNonNull(
                    worldsAccess.regenerate(world, builder -> builder.seed(null)),
                    "WorldsAccess.regenerate(...) hat null geliefert."
            );
            if (pendingReset == null) {
                return regeneration;
            }
            PendingDataReset expectedReset = pendingReset;
            var observed = Objects.requireNonNull(regeneration.handle(WorldsResult::new), "Worlds-Beobachter fehlt.");
            return observed.thenCompose(result -> finishDataReset(world, expectedReset, result, afterReceipt))
                    .whenComplete((ignored, failure) -> removePendingReset(world, expectedReset));
        } catch (RuntimeException | LinkageError exception) {
            if (pendingReset != null) logger.log(Level.SEVERE, pendingReset.description()
                    + " Worlds-Abschluss ungeklärt. Dateibestand zur Prüfung erhalten.", exception);
            removePendingReset(world, pendingReset);
            // Ohne verlässliche Rückkehr könnten Worlds-Dateioperationen noch laufen.
            throw exception;
        }
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

        synchronized (pendingReset) {
            if (stopped || pendingReset.unload != null || pendingReset.closed) {
                pendingReset.duplicateUnload = true;
                event.setCancelled(true);
                return;
            }
            pendingReset.unload = event;
        }
        try {
            World world = event.getWorld();
            dragonFightCompatibility.requireSupported();
            if (!BukkitFarmworldWorldOperations.isRegisteredEndFarm(
                    server, server.getWorlds(), world)) {
                throw new IllegalStateException("DragonBattle-Datenreset für '" + world.getName()
                        + "' erfordert eine registrierte, separate Endfarm.");
            }
            logger.info("DragonBattle-Saved-Data f\u00fcr '" + event.getWorld().getName()
                    + "' werden f\u00fcr Reset vorbereitet.");
            pendingReset.worldKey = world.getKey().asString();
            pendingReset.stagedData = dragonFightDataStore.stage(
                    event.getWorld().getWorldFolder().toPath(),
                    pendingReset.mode, pendingReset.id
            );
            if (pendingReset.stagedData.hadFightData()) {
                logger.info("Bestehende DragonBattle-Daten gesichert.");
            } else {
                logger.info("Für '" + event.getWorld().getName()
                        + "' waren keine alten DragonBattle-Daten vorhanden.");
            }
            logger.info("DragonBattle-Saved-Data erfolgreich auf '"
                    + pendingReset.mode + "' gesetzt.");
        } catch (IOException | RuntimeException exception) {
            pendingReset.preparationFailure = exception;
            event.setCancelled(true);
            logger.log(
                    Level.SEVERE,
                    pendingReset.description() + " Staging gescheitert. Dateibestand und Journal prüfen.",
                    exception
            );
        }
    }

    private record WorldsResult(World world, Throwable failure) {}

    private CompletableFuture<World> finishDataReset(World original, PendingDataReset pending, WorldsResult result,
                                                     Consumer<BackupCleanup> afterReceipt) {
        synchronized (pending) {
            pending.closed = true;
        }
        Throwable failure = result.failure();
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure == null) {
            if (!pending.regenerationEventReceived) {
                failure = new IllegalStateException("Worlds hat kein WorldRegenerateEvent ausgelöst.");
            } else if (pending.preparationFailure != null) {
                failure = pending.preparationFailure;
            } else if (pending.stagedData == null || pending.duplicateUnload) {
                failure = new IllegalStateException("Kein eindeutig abgeschlossenes Staging im WorldUnloadEvent.");
            } else if (result.world() == null || result.world() == original || pending.unload.isCancelled()) {
                failure = new IllegalStateException("Worlds-Ergebnis bestätigt keine neue Welt nach dem Unload.");
            } else {
                afterReceipt.accept(folder -> dragonFightDataStore.commit(pending.stagedData, folder));
                return CompletableFuture.completedFuture(result.world());
            }
        }
        if (pending.preparationFailure != null && pending.preparationFailure != failure) {
            failure.addSuppressed(pending.preparationFailure);
        }
        if (pending.stagedData == null || !confirmedUnloadVeto(pending, failure)) {
            logger.warning(pending.description() + " Kein belegter Rollback vor Entladung. Dateien erhalten.");
            return CompletableFuture.failedFuture(failure);
        }
        return rollbackAfterVeto(original, pending, failure);
    }

    private boolean confirmedUnloadVeto(PendingDataReset pending, Throwable failure) {
        return failure instanceof WorldOperationException worlds
                && worlds.reason() == WorldOperationException.Reason.UNLOAD_FAILED
                && pending.worldKey.equals(worlds.key())
                && pending.unload.isCancelled() && !pending.duplicateUnload;
    }

    private CompletableFuture<World> rollbackAfterVeto(World original, PendingDataReset pending, Throwable cause) {
        var data = pending.stagedData;
        var checked = scheduler.runAsync(() -> {
            dragonFightDataStore.prepareRollback(data);
            return data;
        });
        return checked.thenCompose(verified -> scheduler.runGlobal(() -> {
            if (stopped || pending.duplicateUnload || !pending.unload.isCancelled()
                    || !BukkitFarmworldWorldOperations.isRegisteredEndFarm(
                    server, server.getWorlds(), original)) {
                throw new IOException("Die alte Endfarm-Instanz ist nicht mehr eindeutig registriert.");
            }
            dragonFightDataStore.restoreVerified(verified);
            return verified;
        })).thenCompose(restored -> scheduler.runAsync(() -> {
            dragonFightDataStore.finishRollback(restored);
            logger.info(pending.description() + " Zustand vor dem Unload-Veto wiederhergestellt, Cleanup abgeschlossen.");
            return data;
        })).handle((ignored, rollbackFailure) -> {
            if (rollbackFailure != null) {
                cause.addSuppressed(rollbackFailure);
                logger.log(Level.SEVERE, pending.description()
                        + " Rollback oder Cleanup offen. " + data.diagnosis(), rollbackFailure);
            }
            throw new CompletionException(cause);
        });
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

    private static final class PendingDataReset {
        final FarmworldRegenerationOptions.EndDragonFightDataMode mode;
        final UUID id;
        final String worldName;
        volatile boolean regenerationEventReceived;
        volatile WorldUnloadEvent unload;
        volatile String worldKey;
        volatile boolean duplicateUnload;
        boolean closed;
        volatile EndDragonFightDataStore.StagedData stagedData;
        volatile Throwable preparationFailure;

        PendingDataReset(FarmworldRegenerationOptions.EndDragonFightDataMode mode, UUID id, String worldName) {
            this.mode = mode;
            this.id = Objects.requireNonNull(id);
            this.worldName = worldName;
        }

        String description() {
            return "Endfarm end (" + worldName + "), Versuch " + id + ", WORLDS_PENDING.";
        }
    }
}
