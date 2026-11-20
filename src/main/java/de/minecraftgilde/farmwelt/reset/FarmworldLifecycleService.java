package de.minecraftgilde.farmwelt.reset;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.bukkit.World;

/** Owns the technical lifecycle operation used to regenerate a loaded farmworld. */
public interface FarmworldLifecycleService {

    /** Ein synchroner Wurf liefert keinen Abschlussbeleg für einen begonnenen Aufruf. */
    CompletableFuture<World> regenerate(
            World world,
            FarmworldRegenerationOptions options
    );

    static boolean observationLost(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure instanceof CancellationException || failure instanceof TimeoutException;
    }
}
