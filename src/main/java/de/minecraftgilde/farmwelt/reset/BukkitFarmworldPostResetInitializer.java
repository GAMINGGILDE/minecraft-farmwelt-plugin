package de.minecraftgilde.farmwelt.reset;

import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.INITIAL_FIGHT;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.PRESERVE;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode.SUPPRESSED;
import static de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.effectiveDragonPolicy;

import de.minecraftgilde.farmwelt.reset.FarmworldRegenerationOptions.EndDragonFightDataMode;
import io.papermc.paper.event.block.DragonEggFormEvent;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

/** Bukkit/Paper/Folia implementation of the concrete post-reset settings. */
public final class BukkitFarmworldPostResetInitializer
        implements FarmworldPostResetInitializer, Listener {

    private static final long DRAGON_REMOVAL_VERIFICATION_DELAY_TICKS = 5L;
    private static final long DRAGON_KILL_PORTAL_REPAIR_DELAY_TICKS = 1L;
    private static final int MINIMUM_VANILLA_DRAGON_RESPAWN_CRYSTALS = 4;
    private static final int EXIT_PORTAL_HORIZONTAL_VERIFICATION_RADIUS = 4;
    private static final int EXIT_PORTAL_VERTICAL_VERIFICATION_RADIUS = 2;
    private static final int END_ORIGIN_CHUNK_X = 0;
    private static final int END_ORIGIN_CHUNK_Z = 0;
    private static final int END_PODIUM_X = 0;
    private static final int END_PODIUM_Z = 0;

    private final Plugin plugin;
    private final FarmweltScheduler scheduler;
    private final Logger logger;
    private final GameruleValueConverter valueConverter;
    private final GameRuleAccess gameRuleAccess;
    private final EndDragonFightRuntimeAccess dragonFightRuntimeAccess;
    private final EndDragonFightCompatibility dragonCompatibility;
    private volatile Map<String, EndWorldIdentity> registeredEndFarms = Map.of();
    private long policyRevision;
    private final AtomicReference<Set<String>> configuredEndWorlds =
            new AtomicReference<>(Set.of());
    private final AtomicReference<Set<String>> configuredDragonSuppressionWorlds =
            new AtomicReference<>(Set.of());
    private final ConcurrentHashMap<String, Integer> activeResetDragonSuppressionWorlds =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> activeResetDragonAllowWorlds =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> temporaryDragonAllowWorlds =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WeakReference<World>> pendingOneTimeDragonSpawnWorlds =
            new ConcurrentHashMap<>();
    private final Set<String> loggedSuppressedDragonSpawns = ConcurrentHashMap.newKeySet();

    public BukkitFarmworldPostResetInitializer(
            Plugin plugin,
            FarmweltScheduler scheduler,
            Logger logger
    ) {
        this(
                plugin,
                scheduler,
                logger,
                new GameruleValueConverter(),
                registryResolver(),
                EndDragonFightCompatibility.runningServer(),
                EndDragonFightRuntimeAccess.runningServer()
        );
    }

    BukkitFarmworldPostResetInitializer(
            Plugin plugin,
            FarmweltScheduler scheduler,
            Logger logger,
            GameruleValueConverter valueConverter,
            GameRuleAccess gameRuleAccess,
            EndDragonFightCompatibility dragonCompatibility,
            EndDragonFightRuntimeAccess dragonFightRuntimeAccess
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.valueConverter = Objects.requireNonNull(valueConverter, "valueConverter");
        this.gameRuleAccess = Objects.requireNonNull(gameRuleAccess, "gameRuleAccess");
        this.dragonCompatibility = Objects.requireNonNull(dragonCompatibility, "dragonCompatibility");
        this.dragonFightRuntimeAccess = Objects.requireNonNull(
                dragonFightRuntimeAccess,
                "dragonFightRuntimeAccess"
        );
        refreshEndWorldIdentities();
    }

    // Die geordnete Bukkit-Namensregistry nur beim Start oder auf der Globalregion lesen.
    private synchronized void refreshEndWorldIdentities() {
        try {
            var server = plugin.getServer();
            List<World> worlds = server.getWorlds();
            List<WorldReference> order = worlds.stream().map(WorldReference::new).toList();
            Map<String, EndWorldIdentity> eligible = new HashMap<>();
            for (int i = 0; i < worlds.size(); i++) {
                World world = worlds.get(i);
                if (BukkitFarmworldWorldOperations.isRegisteredEndFarm(server, worlds, world)) {
                    eligible.put(world.getName(), new EndWorldIdentity(order.get(i), order.subList(0, i)));
                }
            }
            registeredEndFarms = Map.copyOf(eligible);
            pendingOneTimeDragonSpawnWorlds.entrySet().removeIf(entry -> {
                var identity = eligible.get(entry.getKey());
                return identity == null || identity.farm().reference().get() != entry.getValue().get();
            });
        } catch (RuntimeException failure) {
            registeredEndFarms = Map.of();
            throw failure;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin.getServer().isGlobalTickThread()) {
            refreshEndWorldIdentities();
        } else {
            scheduleIdentityRefresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public synchronized void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        var identity = registeredEndFarms.get(world.getName());
        if (identity != null && identity.farm().reference().get() == world) {
            var eligible = new HashMap<>(registeredEndFarms);
            eligible.remove(world.getName());
            registeredEndFarms = Map.copyOf(eligible);
        }
        // Ein zugelassenes Event bestätigt weder das Schließen noch das Entfernen der Welt.
        scheduleIdentityRefresh();
    }

    private void scheduleIdentityRefresh() {
        scheduler.runGlobalDelayed(1L, () -> {
            refreshEndWorldIdentities();
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Weltidentitäten konnten nicht aktualisiert werden.", unwrap(failure));
            }
        });
    }

    private record WorldReference(NamespacedKey key, WeakReference<World> reference) {
        WorldReference(World world) {
            this(world.getKey(), new WeakReference<>(world));
        }

        boolean isRegistered(Server server) {
            World world = reference.get();
            return world != null && key != null && server.getWorld(key) == world;
        }
    }

    private record EndWorldIdentity(WorldReference farm, List<WorldReference> predecessors) {
        boolean matches(Server server, World world) {
            // Worlds kann eine Welt auch nach dem Abgleich noch entfernen. Der Key-Lookup
            // liest in 26.1.2 die per Copy-on-Write ersetzte Level-Tabelle, keine Bukkit-Weltliste.
            // Mindestens eine früher geladene Instanz muss der Farm noch vorangehen.
            return farm.reference().get() == world && farm.isRegistered(server)
                    && predecessors.stream().anyMatch(previous -> previous.isRegistered(server));
        }
    }

    /** Atomically replaces the persistent spawn-guard policy with the supplied configuration. */
    public synchronized void synchronizeDragonSpawnGuards(
            Collection<FarmworldResetConfig> configurations
    ) {
        Objects.requireNonNull(configurations, "configurations");
        Set<String> synchronizedEndWorlds = new HashSet<>();
        Set<String> synchronizedWorlds = new HashSet<>();
        for (FarmworldResetConfig config : configurations) {
            Objects.requireNonNull(config, "configurations darf keine null-Eintr\u00e4ge enthalten");
            var policy = effectiveDragonPolicy(config, ResetOptions.defaults());
            if (policy == PRESERVE) {
                continue;
            }
            try {
                dragonCompatibility.requireSupported();
            } catch (IllegalStateException exception) {
                logger.warning("End-Policy für '" + config.worldName() + "': " + exception.getMessage());
                continue;
            }
            if (!registeredEndFarms.containsKey(config.worldName())) {
                logger.warning("End-Policy für '" + config.worldName()
                        + "' derzeit gesperrt. Weltname, Ladestatus, End-Dimension und Hauptweltschutz prüfen.");
            }
            synchronizedEndWorlds.add(config.worldName());
            if (policy == SUPPRESSED) {
                synchronizedWorlds.add(config.worldName());
            }
        }

        Set<String> newEndWorlds = Set.copyOf(synchronizedEndWorlds);
        Set<String> previousEndWorlds = configuredEndWorlds.get();
        Set<String> newWorlds = Set.copyOf(synchronizedWorlds);
        Set<String> previousWorlds = configuredDragonSuppressionWorlds.get();
        Set<String> changedWorlds = changedWorlds(previousWorlds, newWorlds);
        if (!previousEndWorlds.equals(newEndWorlds) || !changedWorlds.isEmpty()) {
            policyRevision++;
        }
        configuredEndWorlds.set(newEndWorlds);
        configuredDragonSuppressionWorlds.set(newWorlds);
        try {
            logConfiguredGuardChanges(previousWorlds, newWorlds);
            changedWorlds.forEach(pendingOneTimeDragonSpawnWorlds::remove);
            changedWorlds.forEach(loggedSuppressedDragonSpawns::remove);
            logger.info("Dragon-Spawn-Guards mit aktueller Konfiguration synchronisiert.");
        } catch (RuntimeException exception) {
            configuredEndWorlds.set(previousEndWorlds);
            configuredDragonSuppressionWorlds.set(previousWorlds);
            throw exception;
        }
    }

    @Override
    public ResetScope beginReset(FarmworldResetConfig config, ResetOptions options) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(options, "options");
        if (!config.enabled() || config.farmworldType() != FarmworldType.END) {
            return ResetScope.NOOP;
        }

        String worldName = config.worldName();
        pendingOneTimeDragonSpawnWorlds.remove(worldName);
        if (options.allowEnderDragon()) {
            incrementPolicy(temporaryDragonAllowWorlds, worldName);
            loggedSuppressedDragonSpawns.remove(worldName);
            return resetScope(temporaryDragonAllowWorlds, worldName);
        }
        if (suppressesEnderDragon(config)) {
            incrementPolicy(activeResetDragonSuppressionWorlds, worldName);
            loggedSuppressedDragonSpawns.remove(worldName);
            return resetScope(activeResetDragonSuppressionWorlds, worldName);
        }
        incrementPolicy(activeResetDragonAllowWorlds, worldName);
        loggedSuppressedDragonSpawns.remove(worldName);
        return resetScope(activeResetDragonAllowWorlds, worldName);
    }

    /** Clears all persistent and temporary guard state during plugin shutdown. */
    public synchronized void shutdownDragonSpawnGuards() {
        policyRevision++;
        configuredEndWorlds.set(Set.of());
        configuredDragonSuppressionWorlds.set(Set.of());
        activeResetDragonSuppressionWorlds.clear();
        activeResetDragonAllowWorlds.clear();
        temporaryDragonAllowWorlds.clear();
        pendingOneTimeDragonSpawnWorlds.clear();
        loggedSuppressedDragonSpawns.clear();
    }

    /** Returns whether the spawn guard applies before inspecting the live DragonBattle state. */
    public boolean shouldSuppressEnderDragonSpawn(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        var allowance = pendingOneTimeDragonSpawnWorlds.get(worldName);
        World pending = allowance == null ? null : allowance.get();
        if (temporaryDragonAllowWorlds.containsKey(worldName)
                || activeResetDragonAllowWorlds.containsKey(worldName)
                || (pending != null && isRegisteredEndWorld(worldName, pending))) {
            return false;
        }
        return configuredDragonSuppressionWorlds.get().contains(worldName)
                || activeResetDragonSuppressionWorlds.containsKey(worldName);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventSuppressedEnderDragonSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            checkDragonSpawn(event, dragon.getWorld());
        }
    }

    private synchronized void checkDragonSpawn(CreatureSpawnEvent event, World world) {
        String worldName = world.getName();
        if (!shouldSuppressEnderDragonSpawn(worldName) || !isAllowedEndWorld(world)) {
            return;
        }
        if (isVanillaEnderDragonRespawnInProgress(world)) {
            logger.info("Vanilla-Enderdrachen-Respawn in '" + worldName
                    + "' erkannt; Spawn trotz Dragon-Suppression erlaubt.");
            return;
        }

        event.setCancelled(true);
        if (loggedSuppressedDragonSpawns.add(worldName)) {
            logger.info("Verz\u00f6gerten Enderdrachen-Spawn in '" + worldName
                    + "' gem\u00e4\u00df Dragon-Policy verhindert.");
        }
    }

    private boolean isVanillaEnderDragonRespawnInProgress(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || !battle.hasBeenPreviouslyKilled()) {
            return false;
        }

        DragonBattle.RespawnPhase phase = battle.getRespawnPhase();
        if (phase != DragonBattle.RespawnPhase.NONE) {
            return true;
        }

        // Paper clears the phase immediately before adding the respawned dragon, while the
        // crystals remain associated with the battle until after CreatureSpawnEvent returns.
        return battle.getRespawnCrystals().size()
                >= MINIMUM_VANILLA_DRAGON_RESPAWN_CRYSTALS;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void completeOneTimeEnderDragonSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        World world = dragon.getWorld();
        String worldName = world.getName();
        var allowance = pendingOneTimeDragonSpawnWorlds.get(worldName);
        if (allowance != null && allowance.get() == world && isAllowedEndWorld(world)
                && pendingOneTimeDragonSpawnWorlds.remove(worldName, allowance)) {
            logger.info("Einmalige Enderdrachen-Freigabe f\u00fcr '" + worldName
                    + "' beim erfolgreichen Spawn eingel\u00f6st.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public synchronized void ensureExitPortalAfterDragonKill(DragonEggFormEvent event) {
        World world = event.getBlock().getWorld();
        if (!configuredEndWorlds.get().contains(world.getName()) || !isAllowedEndWorld(world)) {
            return;
        }

        long revision = policyRevision;
        scheduler.runRegionDelayed(world, 0, 0, DRAGON_KILL_PORTAL_REPAIR_DELAY_TICKS, () -> {
            repairExitPortal(world, revision);
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.log(
                        Level.SEVERE,
                        "End-Ausgangsportal konnte nach dem Drachenkampf in '"
                                + world.getName() + "' nicht sichergestellt werden.",
                        unwrap(failure)
                );
            }
        });
    }

    private synchronized void repairExitPortal(World world, long revision) {
        if (revision != policyRevision || !configuredEndWorlds.get().contains(world.getName())
                || !isAllowedEndWorld(world)) {
            return;
        }
        applyFightState(world.getName(), world, SUPPRESSED);
        logger.info("Aktives End-Ausgangsportal nach dem Drachenkampf wiederhergestellt und verifiziert.");
    }

    private boolean isAllowedEndWorld(World world) {
        return isRegisteredEndWorld(world.getName(), world) && dragonCompatibility.isSupported();
    }

    private boolean isRegisteredEndWorld(String name, World world) {
        var identity = registeredEndFarms.get(name);
        return identity != null && identity.matches(plugin.getServer(), world);
    }

    private void requireDragonTarget(String expectedName, World world) {
        dragonCompatibility.requireSupported();
        if (!isRegisteredEndWorld(expectedName, world)) {
            throw new IllegalStateException("Keine geladene, separate Endfarm für '" + expectedName + "'.");
        }
    }

    private synchronized DragonBattle applyFightState(
            String expectedName, World world, EndDragonFightDataMode mode
    ) {
        requireDragonTarget(expectedName, world);
        DragonBattle battle = requireDragonBattle(world);
        int surface = centralEndIslandSurfaceY(world);
        switch (mode) {
            case INITIAL_FIGHT -> dragonFightRuntimeAccess.prepareInitialFight(battle, surface);
            case SUPPRESSED -> {
                dragonFightRuntimeAccess.suppress(battle, surface);
                verifyActiveExitPortal(world, battle);
            }
            case PRESERVE -> throw new IllegalArgumentException("PRESERVE erlaubt keinen DragonBattle-Zugriff.");
        }
        return battle;
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

        EndDragonFightDataMode policy = effectiveDragonPolicy(config, options);
        final CompletableFuture<Void> initialization;
        try {
            initialization = scheduler.runGlobal(() -> {
                if (policy != PRESERVE) {
                    requireDragonTarget(config.worldName(), regeneratedWorld);
                }
                applyGamerules(config, regeneratedWorld);
                applyWorldBorder(config, regeneratedWorld);
                return null;
            }).thenCompose(ignored -> applyEndDragonPolicy(config, regeneratedWorld, policy, options));
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
            EndDragonFightDataMode policy,
            ResetOptions options
    ) {
        if (policy == PRESERVE) {
            return CompletableFuture.completedFuture(null);
        }
        if (policy == INITIAL_FIGHT) {
            return prepareAllowedEndDragon(config, world, options.allowEnderDragon());
        }

        logger.info("Dragon-Policy f\u00fcr '" + world.getName() + "' wird angewendet.");
        // DragonBattle and the central exit portal are owned by the End origin region on Folia.
        return loadEndOriginChunk(world).thenCompose(ignored -> scheduler.runRegion(
                world,
                END_ORIGIN_CHUNK_X,
                END_ORIGIN_CHUNK_Z,
                () -> {
                    applyFightState(config.worldName(), world, SUPPRESSED);
                    logger.info("DragonBattle vollständig beendet und aktives "
                            + "End-Ausgangsportal verifiziert.");
                    return null;
                }
        )).thenCompose(ignored -> scheduler.runGlobal(() -> inspectDragonPolicy(world)))
                .thenCompose(snapshot -> {
                    if (snapshot.activeDragons().isEmpty()) {
                        return finishDragonPolicy(world, snapshot);
                    }
                    logger.info("Enderdrache gefunden, Entfernung eingeplant.");
                    return removeEnderDragons(world, snapshot.activeDragons())
                            .thenCompose(ignored -> scheduler.runGlobalDelayed(
                                    DRAGON_REMOVAL_VERIFICATION_DELAY_TICKS,
                                    () -> inspectDragonPolicy(world)
                            ))
                            .thenCompose(verification -> finishDragonPolicy(world, verification));
                });
    }

    private void verifyActiveExitPortal(World world, DragonBattle battle) {
        Location portalLocation = battle.getEndPortalLocation();
        if (portalLocation == null) {
            throw new IllegalStateException(
                    "Aktives End-Ausgangsportal konnte nicht verifiziert werden: "
                            + "DragonBattle lieferte keine Portalposition."
            );
        }
        if (!hasActiveExitPortal(world, portalLocation)) {
            throw new IllegalStateException(
                    "Aktives End-Ausgangsportal konnte nicht verifiziert werden: Im Bereich "
                            + "der DragonBattle-Portalposition wurden keine END_PORTAL-Blöcke "
                            + "gefunden."
            );
        }
    }

    private boolean hasActiveExitPortal(World world, Location portalLocation) {
        int centerX = portalLocation.getBlockX();
        int centerY = portalLocation.getBlockY();
        int centerZ = portalLocation.getBlockZ();
        for (int y = centerY - EXIT_PORTAL_VERTICAL_VERIFICATION_RADIUS;
                y <= centerY + EXIT_PORTAL_VERTICAL_VERIFICATION_RADIUS;
                y++) {
            for (int x = centerX - EXIT_PORTAL_HORIZONTAL_VERIFICATION_RADIUS;
                    x <= centerX + EXIT_PORTAL_HORIZONTAL_VERIFICATION_RADIUS;
                    x++) {
                for (int z = centerZ - EXIT_PORTAL_HORIZONTAL_VERIFICATION_RADIUS;
                        z <= centerZ + EXIT_PORTAL_HORIZONTAL_VERIFICATION_RADIUS;
                        z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.END_PORTAL) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private CompletableFuture<Void> prepareAllowedEndDragon(
            FarmworldResetConfig config,
            World world,
            boolean oneTimeOverride
    ) {
        return loadEndOriginChunk(world).thenCompose(ignored -> scheduler.runRegion(
                world,
                END_ORIGIN_CHUNK_X,
                END_ORIGIN_CHUNK_Z,
                () -> {
                    DragonBattle battle = applyFightState(config.worldName(), world, INITIAL_FIGHT);
                    if (battle.hasBeenPreviouslyKilled()) {
                        throw new IllegalStateException(
                                "DragonBattle konnte nicht auf einen frischen Erstkampf gesetzt "
                                        + "werden."
                        );
                    }
                    logger.info("DragonBattle als frischer Erstkampf initialisiert.");

                    if (oneTimeOverride) {
                        if (suppressesEnderDragon(config)) {
                            EnderDragon activeDragon = battle.getEnderDragon();
                            if (activeDragon == null
                                    || !activeDragon.isValid()
                                    || activeDragon.isDead()) {
                                pendingOneTimeDragonSpawnWorlds.put(world.getName(), new WeakReference<>(world));
                                logger.info("Einmalige Enderdrachen-Freigabe f\u00fcr den "
                                        + "verz\u00f6gerten Vanilla-Spawn in '" + world.getName()
                                        + "' vorgemerkt.");
                            } else {
                                pendingOneTimeDragonSpawnWorlds.remove(world.getName());
                            }
                        } else {
                            pendingOneTimeDragonSpawnWorlds.remove(world.getName());
                        }
                        logger.info("Enderdrache f\u00fcr diesen Reset ausdr\u00fccklich aktiviert.");
                    } else {
                        pendingOneTimeDragonSpawnWorlds.remove(world.getName());
                        logger.info("Enderdrache gem\u00e4\u00df Post-Reset-Konfiguration aktiviert.");
                    }
                    return null;
                }
        ));
    }

    private CompletableFuture<Void> loadEndOriginChunk(World world) {
        try {
            return Objects.requireNonNull(
                    world.getChunkAtAsync(END_ORIGIN_CHUNK_X, END_ORIGIN_CHUNK_Z, true),
                    "Bukkit lieferte kein Future für den zentralen End-Chunk."
            ).thenApply(chunk -> {
                Objects.requireNonNull(chunk, "Bukkit lieferte keinen zentralen End-Chunk.");
                return null;
            });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private int centralEndIslandSurfaceY(World world) {
        return world.getHighestBlockYAt(
                END_PODIUM_X,
                END_PODIUM_Z,
                HeightMap.MOTION_BLOCKING_NO_LEAVES
        );
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
        } else if (!shouldSuppressEnderDragonSpawn(world.getName())) {
            failureDetail = "Der Spawn-Guard ist nicht aktiv.";
        }

        if (failureDetail != null) {
            logger.severe("Dragon-Policy f\u00fcr '" + world.getName()
                    + "' fehlgeschlagen: " + failureDetail);
            return CompletableFuture.failedFuture(new IllegalStateException(failureDetail));
        }

        logger.info("Dragon-Policy verifiziert: Kein aktiver Enderdrache vorhanden; "
                + "verz\u00f6gerte DragonBattle-Spawns werden verhindert.");
        logger.info("Spawn-Guard f\u00fcr '" + world.getName() + "' aktiv.");
        return CompletableFuture.completedFuture(null);
    }

    private boolean suppressesEnderDragon(FarmworldResetConfig config) {
        return effectiveDragonPolicy(config, ResetOptions.defaults()) == SUPPRESSED;
    }

    private void logConfiguredGuardChanges(Set<String> previousWorlds, Set<String> newWorlds) {
        newWorlds.stream()
                .filter(worldName -> !previousWorlds.contains(worldName))
                .sorted()
                .forEach(worldName -> logger.info(
                        "Dragon-Spawn-Guard aktiviert: " + worldName
                ));
        previousWorlds.stream()
                .filter(worldName -> !newWorlds.contains(worldName))
                .sorted()
                .forEach(worldName -> logger.info(
                        "Dragon-Spawn-Guard deaktiviert: " + worldName
                ));
    }

    private Set<String> changedWorlds(Set<String> previousWorlds, Set<String> newWorlds) {
        Set<String> changed = new HashSet<>(previousWorlds);
        changed.addAll(newWorlds);
        Set<String> unchanged = new HashSet<>(previousWorlds);
        unchanged.retainAll(newWorlds);
        changed.removeAll(unchanged);
        return changed;
    }

    private void incrementPolicy(ConcurrentHashMap<String, Integer> policies, String worldName) {
        policies.merge(worldName, 1, Integer::sum);
    }

    private ResetScope resetScope(
            ConcurrentHashMap<String, Integer> policies,
            String worldName
    ) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            policies.computeIfPresent(
                    worldName,
                    (ignored, count) -> count == 1 ? null : count - 1
            );
            loggedSuppressedDragonSpawns.remove(worldName);
        };
    }

    private DragonPolicySnapshot inspectDragonPolicy(World world) {
        if (!isAllowedEndWorld(world)) {
            throw new IllegalStateException("Die zu prüfende Endfarm ist nicht mehr freigegeben.");
        }
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

    private CompletableFuture<Void> removeEnderDragons(World world, Collection<EnderDragon> dragons) {
        CompletableFuture<?>[] removals = dragons.stream()
                .map(dragon -> removeEnderDragon(world, dragon))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(removals);
    }

    private CompletableFuture<Void> removeEnderDragon(World world, EnderDragon dragon) {
        CompletableFuture<Void> removal = new CompletableFuture<>();
        try {
            boolean scheduled = dragon.getScheduler().execute(
                    plugin,
                    () -> {
                        try {
                            if (dragon.getWorld() != world || !isAllowedEndWorld(world)) {
                                throw new IllegalStateException("Der Drache gehört nicht mehr zur freigegebenen Endfarm.");
                            }
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
