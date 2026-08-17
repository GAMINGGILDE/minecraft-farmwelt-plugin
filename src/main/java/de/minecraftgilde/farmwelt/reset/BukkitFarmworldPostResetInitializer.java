package de.minecraftgilde.farmwelt.reset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

/** Bukkit/Paper/Folia implementation of the concrete post-reset settings. */
public final class BukkitFarmworldPostResetInitializer
        implements FarmworldPostResetInitializer, Listener {

    private static final long DRAGON_REMOVAL_VERIFICATION_DELAY_TICKS = 5L;

    private final Plugin plugin;
    private final FarmweltScheduler scheduler;
    private final Logger logger;
    private final GameruleValueConverter valueConverter;
    private final GameRuleAccess gameRuleAccess;
    private final Set<String> dragonSpawnSuppressedWorlds = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedSuppressedDragonSpawns = ConcurrentHashMap.newKeySet();

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

    /** Arms the spawn guard for configured end worlds before their first reset after startup. */
    public void initializeDragonSpawnGuard(Collection<FarmworldResetConfig> configurations) {
        Objects.requireNonNull(configurations, "configurations");
        for (FarmworldResetConfig config : configurations) {
            if (suppressesEnderDragon(config)) {
                suppressEnderDragonSpawns(config.worldName());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventSuppressedEnderDragonSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        String worldName = dragon.getWorld().getName();
        if (!dragonSpawnSuppressedWorlds.contains(worldName)) {
            return;
        }

        event.setCancelled(true);
        if (loggedSuppressedDragonSpawns.add(worldName)) {
            logger.info("Verz\u00f6gerten Enderdrachen-Spawn in '" + worldName
                    + "' gem\u00e4\u00df Dragon-Policy verhindert.");
        }
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
            allowEnderDragonSpawns(world.getName());
            logger.info("Enderdrache f\u00fcr diesen Reset ausdr\u00fccklich erlaubt.");
            return CompletableFuture.completedFuture(null);
        }
        if (config.postReset().end().isEmpty()) {
            allowEnderDragonSpawns(world.getName());
            return CompletableFuture.completedFuture(null);
        }
        if (config.postReset().end().orElseThrow().dragon()) {
            allowEnderDragonSpawns(world.getName());
            logger.info("Enderdrache gem\u00e4\u00df Post-Reset-Konfiguration erlaubt.");
            return CompletableFuture.completedFuture(null);
        }

        suppressEnderDragonSpawns(world.getName());
        logger.info("Dragon-Policy f\u00fcr '" + world.getName() + "' wird angewendet.");
        return scheduler.runRegion(world, 0, 0, () -> {
            DragonBattle battle = requireDragonBattle(world);
            if (battle.generateEndPortal(true)) {
                logger.info("Aktives End-Ausgangsportal f\u00fcr die Dragon-Policy erzeugt.");
            } else {
                logger.info("End-Ausgangsportal f\u00fcr die Dragon-Policy bereits vorhanden.");
            }
            battle.setPreviouslyKilled(true);
            logger.info("DragonBattle als bereits besiegt markiert.");
            return null;
        }).thenCompose(ignored -> scheduler.runGlobal(() -> inspectDragonPolicy(world)))
                .thenCompose(snapshot -> {
                    if (snapshot.activeDragons().isEmpty()) {
                        return finishDragonPolicy(world, snapshot);
                    }
                    logger.info("Enderdrache gefunden, Entfernung eingeplant.");
                    return removeEnderDragons(snapshot.activeDragons())
                            .thenCompose(ignored -> scheduler.runGlobalDelayed(
                                    DRAGON_REMOVAL_VERIFICATION_DELAY_TICKS,
                                    () -> inspectDragonPolicy(world)
                            ))
                            .thenCompose(verification -> finishDragonPolicy(world, verification));
                });
    }

    private CompletableFuture<Void> finishDragonPolicy(
            World world,
            DragonPolicySnapshot snapshot
    ) {
        String failureDetail = null;
        if (!snapshot.activeDragons().isEmpty()) {
            failureDetail = "Nach der Entfernung ist weiterhin ein aktiver "
                    + "Enderdrache vorhanden.";
        } else if (!snapshot.previouslyKilled()) {
            failureDetail = "DragonBattle ist nicht als bereits besiegt markiert.";
        } else if (!dragonSpawnSuppressedWorlds.contains(world.getName())) {
            failureDetail = "Der Spawn-Guard ist nicht aktiv.";
        }

        if (failureDetail != null) {
            logger.severe("Dragon-Policy f\u00fcr '" + world.getName()
                    + "' fehlgeschlagen: " + failureDetail);
            return CompletableFuture.failedFuture(new IllegalStateException(failureDetail));
        }

        logger.info("Dragon-Policy verifiziert: Kein aktiver Enderdrache vorhanden; "
                + "verz\u00f6gerte DragonBattle-Spawns werden verhindert.");
        return CompletableFuture.completedFuture(null);
    }

    private boolean suppressesEnderDragon(FarmworldResetConfig config) {
        return config.enabled()
                && config.farmworldType() == FarmworldType.END
                && config.postReset().end().isPresent()
                && !config.postReset().end().orElseThrow().dragon();
    }

    private void suppressEnderDragonSpawns(String worldName) {
        dragonSpawnSuppressedWorlds.add(worldName);
        loggedSuppressedDragonSpawns.remove(worldName);
    }

    private void allowEnderDragonSpawns(String worldName) {
        dragonSpawnSuppressedWorlds.remove(worldName);
        loggedSuppressedDragonSpawns.remove(worldName);
    }

    private DragonPolicySnapshot inspectDragonPolicy(World world) {
        DragonBattle battle = requireDragonBattle(world);
        List<EnderDragon> activeDragons = new ArrayList<>();
        Set<EnderDragon> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<UUID> uniqueIds = new HashSet<>();

        addActiveDragon(activeDragons, identities, uniqueIds, battle.getEnderDragon());
        for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
            addActiveDragon(activeDragons, identities, uniqueIds, dragon);
        }
        return new DragonPolicySnapshot(battle.hasBeenPreviouslyKilled(), List.copyOf(activeDragons));
    }

    private DragonBattle requireDragonBattle(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null) {
            throw new IllegalStateException(
                    "Bukkit lieferte f\u00fcr die Endfarm keinen DragonBattle."
            );
        }
        return battle;
    }

    private void addActiveDragon(
            List<EnderDragon> dragons,
            Set<EnderDragon> identities,
            Set<UUID> uniqueIds,
            EnderDragon dragon
    ) {
        if (dragon == null || !dragon.isValid() || dragon.isDead() || !identities.add(dragon)) {
            return;
        }
        UUID uniqueId = dragon.getUniqueId();
        if (uniqueId != null && !uniqueIds.add(uniqueId)) {
            return;
        }
        dragons.add(dragon);
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
                            logger.info("Enderdrache entfernt.");
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

    private record DragonPolicySnapshot(
            boolean previouslyKilled,
            List<EnderDragon> activeDragons
    ) {
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
