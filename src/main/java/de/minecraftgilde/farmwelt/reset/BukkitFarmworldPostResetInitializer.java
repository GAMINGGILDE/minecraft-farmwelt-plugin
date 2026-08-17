package de.minecraftgilde.farmwelt.reset;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.EnderDragon;
import org.bukkit.plugin.Plugin;

/** Bukkit/Paper/Folia implementation of the concrete post-reset settings. */
public final class BukkitFarmworldPostResetInitializer implements FarmworldPostResetInitializer {

    private final Plugin plugin;
    private final FarmweltScheduler scheduler;
    private final Logger logger;
    private final GameruleValueConverter valueConverter;
    private final GameRuleAccess gameRuleAccess;

    public BukkitFarmworldPostResetInitializer(
            Plugin plugin,
            FarmweltScheduler scheduler,
            Logger logger
    ) {
        this(plugin, scheduler, logger, new GameruleValueConverter(), registryResolver());
    }

    BukkitFarmworldPostResetInitializer(
            Plugin plugin,
            FarmweltScheduler scheduler,
            Logger logger,
            GameruleValueConverter valueConverter,
            GameRuleAccess gameRuleAccess
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.valueConverter = Objects.requireNonNull(valueConverter, "valueConverter");
        this.gameRuleAccess = Objects.requireNonNull(gameRuleAccess, "gameRuleAccess");
    }

    @Override
    public CompletableFuture<PostResetResult> apply(
            FarmworldResetConfig config,
            World regeneratedWorld,
            ResetOptions options
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(regeneratedWorld, "regeneratedWorld");
        Objects.requireNonNull(options, "options");

        logger.info("Post-Reset-Initialisierung f\u00fcr '" + regeneratedWorld.getName()
                + "' gestartet.");

        final CompletableFuture<Void> initialization;
        try {
            initialization = scheduler.runGlobal(() -> {
                applyGamerules(config, regeneratedWorld);
                applyWorldBorder(config, regeneratedWorld);
                return null;
            }).thenCompose(ignored -> applyEndDragonPolicy(config, regeneratedWorld, options));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(config, exception));
        }

        return initialization.handle((ignored, throwable) -> {
            if (throwable != null) {
                return failure(config, unwrap(throwable));
            }
            logger.info("Post-Reset-Initialisierung f\u00fcr '" + regeneratedWorld.getName()
                    + "' abgeschlossen.");
            return PostResetResult.success();
        });
    }

    private void applyGamerules(FarmworldResetConfig config, World world) {
        for (var entry : config.postReset().gamerules().entrySet()) {
            String configuredName = entry.getKey();
            ResolvedGameRule gameRule;
            try {
                gameRule = gameRuleAccess.resolve(configuredName);
            } catch (RuntimeException exception) {
                logger.warning("Unbekannte Gamerule '" + configuredName + "' f\u00fcr Farmwelt '"
                        + config.farmworldKey() + "'.");
                throw new IllegalArgumentException(
                        "Gamerule '" + configuredName + "' konnte nicht aufgel\u00f6st werden.",
                        exception
                );
            }
            if (gameRule == null) {
                logger.warning("Unbekannte Gamerule '" + configuredName + "' f\u00fcr Farmwelt '"
                        + config.farmworldKey() + "'.");
                throw new IllegalArgumentException("Unbekannte Gamerule '" + configuredName + "'.");
            }

            applyGamerule(world, gameRule, entry.getValue());
            logger.info("Gamerule " + configuredName + " = " + entry.getValue() + " gesetzt.");
        }
    }

    private void applyGamerule(World world, ResolvedGameRule gameRule, Object configuredValue) {
        final Object typedValue;
        try {
            typedValue = valueConverter.convert(configuredValue, gameRule.type());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Ung\u00fcltiger Wert '" + configuredValue + "' f\u00fcr Gamerule '"
                            + gameRule.key() + "'.",
                    exception
            );
        }
        if (!gameRule.setter().set(world, typedValue)) {
            throw new IllegalStateException(
                    "Gamerule '" + gameRule.key() + "' wurde von Bukkit abgelehnt."
            );
        }
    }

    private void applyWorldBorder(FarmworldResetConfig config, World world) {
        if (config.postReset().worldBorder().isEmpty()) {
            return;
        }

        double size = config.postReset().worldBorder().orElseThrow().size();
        WorldBorder worldBorder = Objects.requireNonNull(
                world.getWorldBorder(),
                "Bukkit lieferte keine WorldBorder."
        );
        if (size > worldBorder.getMaxSize()) {
            throw new IllegalArgumentException(
                    "WorldBorder-Gr\u00f6\u00dfe " + formatNumber(size)
                            + " \u00fcberschreitet das Bukkit-Maximum "
                            + formatNumber(worldBorder.getMaxSize()) + "."
            );
        }
        worldBorder.setSize(size);
        logger.info("WorldBorder auf " + formatNumber(size) + " gesetzt.");
    }

    private CompletableFuture<Void> applyEndDragonPolicy(
            FarmworldResetConfig config,
            World world,
            ResetOptions options
    ) {
        if (config.farmworldType() != FarmworldType.END) {
            return CompletableFuture.completedFuture(null);
        }
        if (options.allowEnderDragon()) {
            logger.info("Enderdrache f\u00fcr diesen Reset ausdr\u00fccklich erlaubt.");
            return CompletableFuture.completedFuture(null);
        }
        if (config.postReset().end().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (config.postReset().end().orElseThrow().dragon()) {
            logger.info("Enderdrache gem\u00e4\u00df Post-Reset-Konfiguration erlaubt.");
            return CompletableFuture.completedFuture(null);
        }

        return scheduler.runGlobal(() -> List.copyOf(
                world.getEntitiesByClass(EnderDragon.class)
        )).thenCompose(this::removeEnderDragons).thenRun(() ->
                logger.info("Enderdrache gem\u00e4\u00df Reset-Policy entfernt.")
        );
    }

    private CompletableFuture<Void> removeEnderDragons(Collection<EnderDragon> dragons) {
        CompletableFuture<?>[] removals = dragons.stream()
                .map(this::removeEnderDragon)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(removals);
    }

    private CompletableFuture<Void> removeEnderDragon(EnderDragon dragon) {
        CompletableFuture<Void> removal = new CompletableFuture<>();
        try {
            boolean scheduled = dragon.getScheduler().execute(
                    plugin,
                    () -> {
                        try {
                            dragon.remove();
                            removal.complete(null);
                        } catch (RuntimeException exception) {
                            removal.completeExceptionally(exception);
                        }
                    },
                    () -> removal.complete(null),
                    1L
            );
            if (!scheduled) {
                // A retired entity is no longer an active dragon and already satisfies the policy.
                removal.complete(null);
            }
        } catch (RuntimeException exception) {
            removal.completeExceptionally(exception);
        }
        return removal;
    }

    private PostResetResult failure(FarmworldResetConfig config, Throwable cause) {
        String message = "Farmwelt '" + config.worldName() + "' wurde regeneriert, aber die "
                + "Post-Reset-Initialisierung ist fehlgeschlagen.";
        logger.log(Level.SEVERE, message, cause);
        return PostResetResult.failure(message, cause);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String formatNumber(double number) {
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    private static GameRuleAccess registryResolver() {
        return configuredName -> {
            NamespacedKey key = NamespacedKey.fromString(configuredName);
            GameRule<?> gameRule = key == null ? null : Registry.GAME_RULE.get(key);
            return gameRule == null ? null : resolve(gameRule);
        };
    }

    private static <T> ResolvedGameRule resolve(GameRule<T> gameRule) {
        return new ResolvedGameRule(
                gameRule.getKey().getKey(),
                gameRule.getType(),
                (world, value) -> world.setGameRule(gameRule, gameRule.getType().cast(value))
        );
    }

    @FunctionalInterface
    interface GameRuleAccess {

        ResolvedGameRule resolve(String configuredName);
    }

    record ResolvedGameRule(String key, Class<?> type, GameRuleSetter setter) {

        ResolvedGameRule {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(setter, "setter");
        }
    }

    @FunctionalInterface
    interface GameRuleSetter {

        boolean set(World world, Object value);
    }
}
