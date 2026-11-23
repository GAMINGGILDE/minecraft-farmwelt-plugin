package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.World;

/** Owns the technical lifecycle operation used to regenerate a loaded farmworld. */
public interface FarmworldLifecycleService {

    /** Ein synchroner Wurf liefert keinen Abschlussbeleg für einen begonnenen Aufruf. */
    CompletableFuture<World> regenerate(
            World world,
            FarmworldRegenerationOptions options
    );

    /** Der Eigentümer darf das Cleanup erst mit dem gespeicherten Zeitplanbeleg ausführen. */
    default CompletableFuture<World> regenerate(World world, FarmworldRegenerationOptions options,
            UUID attemptId, Consumer<BackupCleanup> afterReceipt) {
        return regenerate(world, options);
    }

    @FunctionalInterface
    interface BackupCleanup {
        void run(Path confirmedWorldFolder) throws IOException;
    }

    static boolean observationLost(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure instanceof CancellationException || failure instanceof TimeoutException;
    }
}
