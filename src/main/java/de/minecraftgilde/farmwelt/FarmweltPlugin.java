package de.minecraftgilde.farmwelt;

import de.minecraftgilde.farmwelt.command.FarmweltCommand;
import de.minecraftgilde.farmwelt.command.FarmweltAdminCommandHandler;
import de.minecraftgilde.farmwelt.command.FarmworldStatusFormatter;
import de.minecraftgilde.farmwelt.config.ConfigManager;
import de.minecraftgilde.farmwelt.gui.FarmweltMenu;
import de.minecraftgilde.farmwelt.listener.FarmweltGuiListener;
import de.minecraftgilde.farmwelt.listener.ResourceBreakListener;
import de.minecraftgilde.farmwelt.reset.AutomaticResetScheduler;
import de.minecraftgilde.farmwelt.reset.BukkitResetNotificationAudience;
import de.minecraftgilde.farmwelt.reset.BukkitResetPlayerNotificationAudience;
import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.BukkitFarmworldPostResetInitializer;
import de.minecraftgilde.farmwelt.reset.BukkitFarmworldWorldOperations;
import de.minecraftgilde.farmwelt.reset.FarmworldResetEngine;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FoliaFarmweltScheduler;
import de.minecraftgilde.farmwelt.reset.ResetNotificationService;
import de.minecraftgilde.farmwelt.reset.ResetWarningTracker;
import de.minecraftgilde.farmwelt.reset.StartupResetCoordinator;
import de.minecraftgilde.farmwelt.reset.WorldsFarmworldLifecycleService;
import de.minecraftgilde.farmwelt.reset.YamlResetStateRepository;
import de.minecraftgilde.farmwelt.service.ClaimProtectionService;
import de.minecraftgilde.farmwelt.service.FarmweltTeleportService;
import de.minecraftgilde.farmwelt.service.JailActionService;
import de.minecraftgilde.farmwelt.service.MessageService;
import de.minecraftgilde.farmwelt.service.ResourceDetectionService;
import de.minecraftgilde.farmwelt.service.ViolationService;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmweltPlugin extends JavaPlugin {

    private final AtomicBoolean configurationReloadInProgress = new AtomicBoolean();
    private ConfigManager configManager;
    private FarmweltMenu farmweltMenu;
    private FarmweltTeleportService teleportService;
    private ClaimProtectionService claimProtectionService;
    private ResourceDetectionService resourceDetectionService;
    private MessageService messageService;
    private ViolationService violationService;
    private JailActionService jailActionService;
    private StartupResetCoordinator startupResetCoordinator;
    private FarmworldResetService resetService;
    private ResetNotificationService resetNotificationService;
    private FarmworldResetEngine resetEngine;
    private WorldsFarmworldLifecycleService worldsLifecycleService;
    private BukkitFarmworldPostResetInitializer postResetInitializer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!initializeWorldsIntegration()) {
            return;
        }

        configManager = new ConfigManager(this);
        configManager.loadFarmweltMenuItems();
        configManager.loadFarmworldResetConfigs();
        configManager.loadResourceMonitorConfig();

        resetService = new FarmworldResetService(
                new YamlResetStateRepository(getDataFolder().toPath().resolve("reset-state.yml"), getLogger()),
                Clock.systemUTC(),
                getLogger()
        );
        if (!resetService.reload(configManager.getFarmworldResetConfigs())) {
            throw new IllegalStateException("Reset-Konfiguration und Reset-State konnten nicht geladen werden.");
        }
        resetNotificationService = new ResetNotificationService(
                resetService,
                new ResetWarningTracker(),
                new BukkitResetNotificationAudience(this),
                new BukkitResetPlayerNotificationAudience(this),
                ZoneId.systemDefault(),
                getLogger()
        );
        logResetStatus();

        BukkitFarmworldWorldOperations worldOperations = new BukkitFarmworldWorldOperations(
                this,
                () -> resetService.getConfiguredWorlds().stream()
                        .map(FarmworldResetConfig::worldName)
                        .collect(Collectors.toUnmodifiableSet())
        );
        FoliaFarmweltScheduler resetScheduler = new FoliaFarmweltScheduler(this);
        postResetInitializer = new BukkitFarmworldPostResetInitializer(
                this,
                resetScheduler,
                getLogger()
        );
        postResetInitializer.synchronizeDragonSpawnGuards(resetService.getConfiguredWorlds());
        getServer().getPluginManager().registerEvents(postResetInitializer, this);
        resetEngine = new FarmworldResetEngine(
                resetService,
                worldOperations,
                worldsLifecycleService,
                postResetInitializer,
                resetScheduler,
                resetNotificationService,
                getLogger()
        );

        farmweltMenu = new FarmweltMenu(configManager);
        teleportService = new FarmweltTeleportService(this, resetEngine);
        claimProtectionService = new ClaimProtectionService(this);
        resourceDetectionService = new ResourceDetectionService(configManager);
        messageService = new MessageService(this, configManager);
        violationService = new ViolationService(configManager);
        jailActionService = new JailActionService(this, configManager, messageService);
        FarmweltCommand farmweltCommand = createFarmweltCommand();
        registerCommand(farmweltCommand);
        getServer().getPluginManager().registerEvents(farmweltCommand, this);
        getServer().getPluginManager().registerEvents(new FarmweltGuiListener(teleportService), this);
        getServer().getPluginManager().registerEvents(
                new ResourceBreakListener(
                        configManager,
                        claimProtectionService,
                        resourceDetectionService,
                        messageService,
                        violationService,
                        jailActionService
                ),
                this
        );

        AutomaticResetScheduler automaticResetScheduler = new AutomaticResetScheduler(
                this,
                getServer().getGlobalRegionScheduler(),
                resetService,
                resetEngine,
                resetNotificationService,
                Clock.systemUTC()
        );
        startupResetCoordinator = new StartupResetCoordinator(
                this,
                getServer().getGlobalRegionScheduler(),
                automaticResetScheduler,
                Clock.systemUTC()
        );
        startupResetCoordinator.start();

        getLogger().info("Farmwelt wurde gestartet.");
    }

    @Override
    public void onDisable() {
        if (startupResetCoordinator != null) {
            startupResetCoordinator.stop();
        }
        if (postResetInitializer != null) {
            postResetInitializer.shutdownDragonSpawnGuards();
        }
        getLogger().info("Farmwelt wurde gestoppt.");
    }

    public void reloadFarmweltConfiguration() throws IOException, InvalidConfigurationException {
        if (!configurationReloadInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Ein Config-Reload läuft bereits.");
        }
        try {
            // JavaPlugin#reloadConfig meldet ungültiges YAML nicht zuverlässig an den Command-Aufrufer.
            YamlConfiguration validation = new YamlConfiguration();
            validation.load(getDataFolder().toPath().resolve("config.yml").toFile());
            reloadConfig();
            configManager.loadFarmworldResetConfigs();
            if (!resetService.reload(configManager.getFarmworldResetConfigs())) {
                throw new IllegalStateException("Reset-Konfiguration und Reset-State konnten nicht neu geladen werden.");
            }
            configManager.loadFarmweltMenuItems();
            configManager.loadResourceMonitorConfig();
            resetNotificationService.reload();
            postResetInitializer.synchronizeDragonSpawnGuards(resetService.getConfiguredWorlds());
            logResetStatus();
            claimProtectionService.reload();
            violationService.reload(configManager);
        } finally {
            configurationReloadInProgress.set(false);
        }
    }

    public FarmworldResetEngine getResetEngine() {
        return resetEngine;
    }

    private boolean initializeWorldsIntegration() {
        try {
            worldsLifecycleService = WorldsFarmworldLifecycleService.connect(getLogger());
            getServer().getPluginManager().registerEvents(worldsLifecycleService, this);
            getLogger().info("Worlds " + worldsLifecycleService.pluginVersion() + " erkannt.");
            getLogger().info("Worlds-Integration initialisiert. Reset-Lifecycle wird über Worlds ausgeführt.");
            return true;
        } catch (RuntimeException | LinkageError exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Worlds-Integration konnte nicht initialisiert werden. Farmwelt wird deaktiviert; "
                            + "ein Fallback auf Bukkit-World-Lifecycle ist nicht verfügbar.",
                    exception
            );
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    private FarmweltCommand createFarmweltCommand() {
        FarmweltAdminCommandHandler adminCommandHandler = new FarmweltAdminCommandHandler(
                resetService,
                resetEngine,
                new FarmworldStatusFormatter(ZoneId.systemDefault()),
                this::reloadFarmweltConfiguration,
                Clock.systemUTC(),
                getLogger()
        );
        return new FarmweltCommand(
                this,
                farmweltMenu,
                claimProtectionService,
                resourceDetectionService,
                violationService,
                configManager,
                adminCommandHandler
        );
    }

    private void logResetStatus() {
        getLogger().info("Reset-System: " + resetService.getConfiguredWorlds().size()
                + " Farmwelten konfiguriert.");
        for (FarmworldResetConfig resetConfig : resetService.getConfiguredWorlds()) {
            resetService.getState(resetConfig.farmworldKey()).ifPresent(state -> getLogger().info(
                    "Reset-System: Farmwelt '" + resetConfig.farmworldKey()
                            + "' nächster Reset: " + state.nextReset()
            ));
        }
    }

    private void registerCommand(FarmweltCommand farmweltCommand) {
        registerCommand(
                "farmwelt",
                "Öffnet die Farmwelt-Auswahl.",
                farmweltCommand
        );
    }
}
