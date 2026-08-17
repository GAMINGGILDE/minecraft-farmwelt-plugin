package de.minecraftgilde.farmwelt;

import de.minecraftgilde.farmwelt.command.FarmweltCommand;
import de.minecraftgilde.farmwelt.command.FarmweltAdminCommandHandler;
import de.minecraftgilde.farmwelt.command.FarmworldStatusFormatter;
import de.minecraftgilde.farmwelt.config.ConfigManager;
import de.minecraftgilde.farmwelt.gui.FarmweltMenu;
import de.minecraftgilde.farmwelt.listener.FarmweltGuiListener;
import de.minecraftgilde.farmwelt.listener.ResourceBreakListener;
import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.BukkitFarmworldWorldOperations;
import de.minecraftgilde.farmwelt.reset.FarmworldResetEngine;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FoliaFarmweltScheduler;
import de.minecraftgilde.farmwelt.reset.SecureWorldDirectoryService;
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
import java.util.stream.Collectors;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmweltPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private FarmweltMenu farmweltMenu;
    private FarmweltTeleportService teleportService;
    private ClaimProtectionService claimProtectionService;
    private ResourceDetectionService resourceDetectionService;
    private MessageService messageService;
    private ViolationService violationService;
    private JailActionService jailActionService;
    private FarmworldResetService resetService;
    private FarmworldResetEngine resetEngine;

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
        logResetStatus();

        BukkitFarmworldWorldOperations worldOperations = new BukkitFarmworldWorldOperations(
                this,
                () -> resetService.getConfiguredWorlds().stream()
                        .map(FarmworldResetConfig::worldName)
                        .collect(Collectors.toUnmodifiableSet())
        );
        resetEngine = new FarmworldResetEngine(
                resetService,
                worldOperations,
                new SecureWorldDirectoryService(
                        getServer().getWorldContainer().toPath(),
                        configManager::getMonitoredWorlds,
                        worldOperations.getProtectedMainWorldDirectories()
                ),
                new FoliaFarmweltScheduler(this),
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

        getLogger().info("Farmwelt wurde gestartet.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Farmwelt wurde gestoppt.");
    }

    public void reloadFarmweltConfiguration() throws IOException, InvalidConfigurationException {
        // Validate first: JavaPlugin#reloadConfig logs malformed YAML without reporting it to the command caller.
        YamlConfiguration validation = new YamlConfiguration();
        validation.load(getDataFolder().toPath().resolve("config.yml").toFile());
        reloadConfig();
        configManager.loadFarmweltMenuItems();
        configManager.loadFarmworldResetConfigs();
        configManager.loadResourceMonitorConfig();
        if (!resetService.reload(configManager.getFarmworldResetConfigs())) {
            throw new IllegalStateException("Reset-Konfiguration und Reset-State konnten nicht neu geladen werden.");
        }
        logResetStatus();
        claimProtectionService.reload();
        violationService.reload(configManager);
    }

    public FarmworldResetEngine getResetEngine() {
        return resetEngine;
    }

    private FarmweltCommand createFarmweltCommand() {
        FarmweltAdminCommandHandler adminCommandHandler = new FarmweltAdminCommandHandler(
                resetService,
                resetEngine,
                new FarmworldStatusFormatter(Clock.systemUTC(), ZoneId.systemDefault()),
                this::reloadFarmweltConfiguration,
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
