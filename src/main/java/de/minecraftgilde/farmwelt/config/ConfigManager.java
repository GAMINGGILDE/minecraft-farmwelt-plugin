package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.gui.FarmweltMenuItem;
import de.minecraftgilde.farmwelt.gui.TeleportAction;
import de.minecraftgilde.farmwelt.model.ResourceWorldRule;
import de.minecraftgilde.farmwelt.model.ResourceWorldType;
import de.minecraftgilde.farmwelt.model.ViolationAction;
import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigManager {

    public static final int FARMWELT_MENU_SIZE = 45;
    public static final int FARMWELT_MENU_CONTENT_SIZE = 27;
    public static final int FARMWELT_MENU_CONTENT_OFFSET = 9;
    private static final int DEFAULT_AUDIT_LOG_COOLDOWN_SECONDS = 120;
    private static final String DEFAULT_AUDIT_STAFF_MESSAGE =
            "&e[Farmwelt-Audit] &f{player} hat &c{block} &fin &7{world} &fbei &7{x} {y} {z} &fabgebaut. Kategorie: &7{category}";

    private final JavaPlugin plugin;
    private final FarmworldResetConfigParser resetConfigParser;
    private volatile List<FarmweltMenuItem> farmweltMenuItems = List.of();
    private volatile List<FarmworldResetConfig> farmworldResetConfigs = List.of();
    private volatile ResourceMonitorConfig resourceMonitorConfig =
            createDefaultResourceMonitorConfig();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.resetConfigParser = new FarmworldResetConfigParser(plugin.getLogger());
    }

    public void loadFarmweltMenuItems() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("farmworlds");
        if (section == null) {
            plugin.getLogger().warning("Config-Bereich 'farmworlds' fehlt. Es werden keine Farmwelten angezeigt.");
            farmweltMenuItems = List.of();
            return;
        }

        List<FarmweltMenuItem> loadedItems = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection farmworldSection = section.getConfigurationSection(key);
            if (farmworldSection == null) {
                plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' ist kein gültiger Config-Bereich.");
                continue;
            }

            FarmweltMenuItem item = loadFarmweltMenuItem(key, farmworldSection);
            if (item != null) {
                loadedItems.add(item);
            }
        }

        farmweltMenuItems = Collections.unmodifiableList(loadedItems);
    }

    public List<FarmweltMenuItem> getFarmweltMenuItems() {
        return farmweltMenuItems;
    }

    public void loadFarmworldResetConfigs() {
        farmworldResetConfigs = resetConfigParser.parse(
                plugin.getConfig().getConfigurationSection("farmworlds")
        );
    }

    public List<FarmworldResetConfig> getFarmworldResetConfigs() {
        return farmworldResetConfigs;
    }

    public void loadResourceMonitorConfig() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("resource-monitor");
        if (section == null) {
            resourceMonitorConfig = createDefaultResourceMonitorConfig();
            plugin.getLogger().warning("Config-Bereich 'resource-monitor' fehlt. Der Ressourcenmonitor bleibt deaktiviert.");
            return;
        }

        String configuredMode = section.getString("mode", "audit");
        String resourceMonitorMode = configuredMode == null
                ? "audit"
                : configuredMode.toLowerCase(Locale.ROOT);
        boolean auditNotifyStaff = true;
        boolean auditLogToConsole = true;
        String staffMessage = DEFAULT_AUDIT_STAFF_MESSAGE;
        int auditLogCooldownSeconds = DEFAULT_AUDIT_LOG_COOLDOWN_SECONDS;

        ConfigurationSection auditSection = section.getConfigurationSection("audit");
        if (auditSection != null) {
            auditNotifyStaff = auditSection.getBoolean("notify-staff", true);
            auditLogToConsole = auditSection.getBoolean("log-to-console", true);
            staffMessage = auditSection.getString("staff-message", staffMessage);
            auditLogCooldownSeconds = Math.max(
                    0,
                    auditSection.getInt("log-cooldown-seconds", DEFAULT_AUDIT_LOG_COOLDOWN_SECONDS)
            );
        }

        resourceMonitorConfig = new ResourceMonitorConfig(
                section.getBoolean("enabled", false),
                resourceMonitorMode,
                toStringSet(section.getStringList("monitored-worlds")),
                toStringSet(section.getStringList("ignored-worlds")),
                section.getString("bypass-permission", "farmwelt.bypass"),
                section.getString("notify-permission", "farmwelt.notify"),
                auditNotifyStaff,
                auditLogToConsole,
                staffMessage,
                auditLogCooldownSeconds,
                Math.max(1, section.getInt("violation-window-seconds", 600)),
                loadViolationActionConfigs(section.getConfigurationSection("actions")),
                loadJailActionConfig(section.getConfigurationSection("actions")),
                loadResourceWorldRules(section.getConfigurationSection("world-rules"))
        );
    }

    public boolean isResourceMonitorEnabled() {
        return resourceMonitorConfig.enabled();
    }

    public boolean isResourceMonitorAuditMode() {
        return "audit".equalsIgnoreCase(resourceMonitorConfig.mode());
    }

    public boolean isResourceMonitorWarnMode() {
        return "warn".equalsIgnoreCase(resourceMonitorConfig.mode());
    }

    public boolean isResourceMonitorEnforceMode() {
        return "enforce".equalsIgnoreCase(resourceMonitorConfig.mode());
    }

    public String getResourceMonitorMode() {
        String mode = resourceMonitorConfig.mode();
        return mode == null ? "" : mode;
    }

    public boolean isMonitoredWorld(String worldName) {
        return resourceMonitorConfig.monitoredWorlds().contains(worldName);
    }

    public Set<String> getMonitoredWorlds() {
        return resourceMonitorConfig.monitoredWorlds();
    }

    public boolean isIgnoredWorld(String worldName) {
        return resourceMonitorConfig.ignoredWorlds().contains(worldName);
    }

    public boolean hasResourceWorldRule(String worldName) {
        return resourceMonitorConfig.resourceWorldRules().containsKey(worldName);
    }

    public Optional<ResourceWorldRule> getResourceWorldRule(String worldName) {
        return Optional.ofNullable(resourceMonitorConfig.resourceWorldRules().get(worldName));
    }

    public String getBypassPermission() {
        String permission = resourceMonitorConfig.bypassPermission();
        return permission == null ? "" : permission;
    }

    public String getNotifyPermission() {
        String permission = resourceMonitorConfig.notifyPermission();
        return permission == null ? "" : permission;
    }

    public boolean isAuditNotifyStaff() {
        return resourceMonitorConfig.auditNotifyStaff();
    }

    public boolean isAuditLogToConsole() {
        return resourceMonitorConfig.auditLogToConsole();
    }

    public String getStaffMessage() {
        String message = resourceMonitorConfig.staffMessage();
        return message == null ? "" : message;
    }

    public int getAuditLogCooldownSeconds() {
        return resourceMonitorConfig.auditLogCooldownSeconds();
    }

    public int getViolationWindowSeconds() {
        return resourceMonitorConfig.violationWindowSeconds();
    }

    public boolean isViolationActionEnabled(ViolationAction action) {
        return getViolationActionConfig(action).enabled();
    }

    public int getViolationActionAfterBlocks(ViolationAction action) {
        return getViolationActionConfig(action).afterBlocks();
    }

    public int getViolationActionCooldownSeconds(ViolationAction action) {
        return getViolationActionConfig(action).cooldownSeconds();
    }

    public String getViolationActionContent(ViolationAction action) {
        return getViolationActionConfig(action).content();
    }

    public String getViolationActionActionbarContent(ViolationAction action) {
        return getViolationActionConfig(action).actionbarContent();
    }

    public boolean isJailActionEnabled() {
        return resourceMonitorConfig.jailActionConfig().enabled();
    }

    public String getJailMode() {
        String mode = resourceMonitorConfig.jailActionConfig().mode();
        return mode == null ? "notify-only" : mode;
    }

    public int getJailAfterBlockedAttempts() {
        return resourceMonitorConfig.jailActionConfig().afterBlockedAttempts();
    }

    public int getJailCooldownMinutes() {
        return resourceMonitorConfig.jailActionConfig().cooldownMinutes();
    }

    public boolean isJailExecuteOncePerWindow() {
        return resourceMonitorConfig.jailActionConfig().executeOncePerWindow();
    }

    public String getJailCommand() {
        String command = resourceMonitorConfig.jailActionConfig().command();
        return command == null ? "" : command;
    }

    public boolean isJailNotifyStaff() {
        return resourceMonitorConfig.jailActionConfig().notifyStaff();
    }

    public String getJailStaffMessage() {
        String message = resourceMonitorConfig.jailActionConfig().staffMessage();
        return message == null ? "" : message;
    }

    public String getJailPlayerMessage() {
        String message = resourceMonitorConfig.jailActionConfig().playerMessage();
        return message == null ? "" : message;
    }

    private FarmweltMenuItem loadFarmweltMenuItem(String key, ConfigurationSection section) {
        if (!section.getBoolean("enabled", true)) {
            return null;
        }

        String displayName = section.getString("display-name");
        if (displayName == null || displayName.isBlank()) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat keinen Anzeigenamen und wird übersprungen.");
            return null;
        }

        String iconName = section.getString("icon");
        Material icon = iconName == null ? null : Material.matchMaterial(iconName.toUpperCase(Locale.ROOT));
        if (icon == null || !icon.isItem()) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat ein ungültiges Icon und wird übersprungen: " + iconName);
            return null;
        }

        int slot = section.getInt("slot", -1);
        if (slot < 0 || slot >= FARMWELT_MENU_CONTENT_SIZE) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat einen Slot außerhalb der GUI und wird übersprungen: " + slot);
            return null;
        }

        TeleportAction teleportAction = loadTeleportAction(key, section);
        if (teleportAction == null) {
            return null;
        }

        return new FarmweltMenuItem(
                key,
                displayName,
                icon,
                slot,
                section.getStringList("lore"),
                teleportAction
        );
    }

    private TeleportAction loadTeleportAction(String key, ConfigurationSection section) {
        ConfigurationSection teleportSection = section.getConfigurationSection("teleport");
        if (teleportSection == null) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat keine Teleport-Konfiguration und wird übersprungen.");
            return null;
        }

        String type = teleportSection.getString("type");
        if (type == null || type.isBlank()) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat keinen Teleport-Typ und wird übersprungen.");
            return null;
        }

        if (!"command".equalsIgnoreCase(type)) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' nutzt einen nicht unterstützten Teleport-Typ und wird übersprungen: " + type);
            return null;
        }

        String sender = teleportSection.getString("sender", "player");
        if (sender == null || sender.isBlank()) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat keinen Teleport-Absender. Es wird 'player' verwendet.");
            sender = "player";
        }

        String normalizedSender = sender.toLowerCase(Locale.ROOT);
        if (!"player".equals(normalizedSender) && !"console".equals(normalizedSender)) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' nutzt einen unbekannten Teleport-Absender. Es wird 'player' verwendet: " + sender);
            normalizedSender = "player";
        }

        String command = teleportSection.getString("command");
        if (command == null || command.isBlank()) {
            plugin.getLogger().warning("Farmwelt-Eintrag '" + key + "' hat keinen Teleport-Befehl und wird übersprungen.");
            return null;
        }

        return new TeleportAction(
                type.toLowerCase(Locale.ROOT),
                normalizedSender,
                command
        );
    }

    private Map<String, ResourceWorldRule> loadResourceWorldRules(ConfigurationSection section) {
        if (section == null) {
            plugin.getLogger().warning("Config-Bereich 'resource-monitor.world-rules' fehlt. Der Ressourcenmonitor hat keine Weltregeln.");
            return Map.of();
        }

        Map<String, ResourceWorldRule> loadedRules = new HashMap<>();
        for (String worldName : section.getKeys(false)) {
            ConfigurationSection ruleSection = section.getConfigurationSection(worldName);
            if (ruleSection == null) {
                plugin.getLogger().warning("Ressourcenregel für Welt '" + worldName + "' ist kein gültiger Config-Bereich.");
                continue;
            }

            ResourceWorldRule rule = loadResourceWorldRule(worldName, ruleSection);
            if (rule != null) {
                loadedRules.put(worldName, rule);
            }
        }

        return Collections.unmodifiableMap(loadedRules);
    }

    private Map<ViolationAction, ViolationActionConfig> loadViolationActionConfigs(ConfigurationSection section) {
        Map<ViolationAction, ViolationActionConfig> defaults = createDefaultViolationActionConfigs();
        if (section == null) {
            return defaults;
        }

        EnumMap<ViolationAction, ViolationActionConfig> loadedConfigs = new EnumMap<>(ViolationAction.class);
        loadedConfigs.put(ViolationAction.WARNING, loadViolationActionConfig(
                section,
                "warning",
                defaults.get(ViolationAction.WARNING),
                "message"
        ));
        loadedConfigs.put(ViolationAction.NOTIFY_STAFF, loadViolationActionConfig(
                section,
                "notify-staff",
                defaults.get(ViolationAction.NOTIFY_STAFF),
                "message"
        ));
        loadedConfigs.put(ViolationAction.CANCEL_BREAK, loadViolationActionConfig(
                section,
                "cancel-break",
                defaults.get(ViolationAction.CANCEL_BREAK),
                "message"
        ));
        loadedConfigs.put(ViolationAction.JAIL, loadViolationActionConfig(
                section,
                "jail",
                defaults.get(ViolationAction.JAIL),
                "command"
        ));

        return Collections.unmodifiableMap(loadedConfigs);
    }

    private ViolationActionConfig loadViolationActionConfig(
            ConfigurationSection actionsSection,
            String path,
            ViolationActionConfig defaults,
            String contentKey
    ) {
        ConfigurationSection section = actionsSection.getConfigurationSection(path);
        if (section == null) {
            return defaults;
        }

        return new ViolationActionConfig(
                section.getBoolean("enabled", defaults.enabled()),
                Math.max(1, section.getInt("after-blocks", defaults.afterBlocks())),
                Math.max(0, section.getInt("cooldown-seconds", defaults.cooldownSeconds())),
                section.getString(contentKey, defaults.content()),
                section.getString("actionbar-message", defaults.actionbarContent())
        );
    }

    private JailActionConfig loadJailActionConfig(ConfigurationSection actionsSection) {
        JailActionConfig defaults = createDefaultJailActionConfig();
        if (actionsSection == null) {
            return defaults;
        }

        ConfigurationSection section = actionsSection.getConfigurationSection("jail");
        if (section == null) {
            return defaults;
        }

        String mode = normalizeJailMode(section.getString("mode", defaults.mode()));
        return new JailActionConfig(
                section.getBoolean("enabled", defaults.enabled()),
                mode,
                Math.max(1, section.getInt("after-blocked-attempts", defaults.afterBlockedAttempts())),
                Math.max(0, section.getInt("cooldown-minutes", defaults.cooldownMinutes())),
                section.getBoolean("execute-once-per-window", defaults.executeOncePerWindow()),
                section.getString("command", defaults.command()),
                section.getBoolean("notify-staff", defaults.notifyStaff()),
                section.getString("staff-message", defaults.staffMessage()),
                section.getString("player-message", defaults.playerMessage())
        );
    }

    private String normalizeJailMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "notify-only";
        }

        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(normalizedMode)
                || "notify-only".equals(normalizedMode)
                || "execute-command".equals(normalizedMode)) {
            return normalizedMode;
        }

        plugin.getLogger().warning("Unbekannter Jail-Modus konfiguriert: " + mode + ". Es wird 'notify-only' verwendet.");
        return "notify-only";
    }

    private Map<ViolationAction, ViolationActionConfig> createDefaultViolationActionConfigs() {
        EnumMap<ViolationAction, ViolationActionConfig> defaults = new EnumMap<>(ViolationAction.class);
        defaults.put(ViolationAction.WARNING, new ViolationActionConfig(
                true,
                5,
                60,
                "&eBitte nutze für Ressourcen die Farmwelten mit &6/farmwelt&e.",
                ""
        ));
        defaults.put(ViolationAction.NOTIFY_STAFF, new ViolationActionConfig(
                true,
                10,
                60,
                "&e[Farmwelt] &f{player} baut Ressourcen in &7{world} &fbei &7{x} {y} {z} &fab. Verstöße im Zeitfenster: &c{count}&f. Kategorie: &7{category}",
                ""
        ));
        defaults.put(ViolationAction.CANCEL_BREAK, new ViolationActionConfig(
                true,
                15,
                10,
                "&cDer Ressourcenabbau in dieser Welt ist jetzt blockiert. Bitte nutze die Farmwelten mit &e/farmwelt&c.",
                "&cRessourcenabbau blockiert! Nutze &e/farmwelt&c."
        ));
        defaults.put(ViolationAction.JAIL, new ViolationActionConfig(
                false,
                40,
                0,
                "jail {player} farmwelt",
                ""
        ));
        return Collections.unmodifiableMap(defaults);
    }

    private JailActionConfig createDefaultJailActionConfig() {
        return new JailActionConfig(
                false,
                "notify-only",
                20,
                60,
                true,
                "jail {player} farmwelt",
                true,
                "&c[Farmwelt] &f{player} hat trotz Blockierung weiter Ressourcenabbau versucht. Blockierte Versuche: &c{blocked-count}&f. Jail wäre jetzt möglich.",
                "&cDu wurdest wegen wiederholtem Ressourcenabbau in der Hauptwelt ins Gefängnis gesetzt."
        );
    }

    private ResourceMonitorConfig createDefaultResourceMonitorConfig() {
        return new ResourceMonitorConfig(
                false,
                "audit",
                Set.of(),
                Set.of(),
                "farmwelt.bypass",
                "farmwelt.notify",
                true,
                true,
                DEFAULT_AUDIT_STAFF_MESSAGE,
                DEFAULT_AUDIT_LOG_COOLDOWN_SECONDS,
                600,
                createDefaultViolationActionConfigs(),
                createDefaultJailActionConfig(),
                Map.of()
        );
    }

    private ViolationActionConfig getViolationActionConfig(ViolationAction action) {
        ViolationActionConfig config = resourceMonitorConfig
                .violationActionConfigs()
                .get(action);
        if (config != null) {
            return config;
        }

        return createDefaultViolationActionConfigs().get(action);
    }

    private ResourceWorldRule loadResourceWorldRule(String worldName, ConfigurationSection section) {
        String typeName = section.getString("type");
        Optional<ResourceWorldType> type = ResourceWorldType.fromConfigValue(typeName);
        if (type.isEmpty()) {
            plugin.getLogger().warning("Ressourcenregel für Welt '" + worldName + "' hat einen ungültigen Typ: " + typeName);
            return null;
        }

        ResourceWorldType worldType = type.get();
        if (worldType == ResourceWorldType.OVERWORLD) {
            return new ResourceWorldRule(
                    worldName,
                    worldType,
                    loadOverworldResourceSet(worldName, section),
                    loadProtectedItemSet(worldName, section)
            );
        }

        return new ResourceWorldRule(
                worldName,
                worldType,
                loadMaterialSet("resource-monitor.world-rules." + worldName + ".resources", section.getStringList("resources")),
                loadProtectedItemSet(worldName, section)
        );
    }

    private Set<Material> loadOverworldResourceSet(String worldName, ConfigurationSection section) {
        Set<Material> resources = loadMaterialSet(
                "resource-monitor.world-rules." + worldName + ".resources",
                section.getStringList("resources")
        );
        if (!resources.isEmpty()) {
            return resources;
        }

        Set<Material> surfaceResources = loadMaterialSet(
                "resource-monitor.world-rules." + worldName + ".surface-resources",
                section.getStringList("surface-resources")
        );
        Set<Material> undergroundResources = loadMaterialSet(
                "resource-monitor.world-rules." + worldName + ".underground-resources",
                section.getStringList("underground-resources")
        );
        if (surfaceResources.isEmpty() && undergroundResources.isEmpty()) {
            return Set.of();
        }

        plugin.getLogger().warning("Ressourcenregel für Overworld '" + worldName
                + "' nutzt alte surface-/underground-Listen. Diese werden als gemeinsame resources-Liste geladen.");
        EnumSet<Material> combinedResources = EnumSet.noneOf(Material.class);
        combinedResources.addAll(surfaceResources);
        combinedResources.addAll(undergroundResources);
        return combinedResources;
    }

    private Set<Material> loadProtectedItemSet(String worldName, ConfigurationSection section) {
        return loadMaterialSet(
                "resource-monitor.world-rules." + worldName + ".protected-items",
                section.getStringList("protected-items"),
                false
        );
    }

    private Set<Material> loadMaterialSet(String configPath, List<String> materialNames) {
        return loadMaterialSet(configPath, materialNames, true);
    }

    private Set<Material> loadMaterialSet(String configPath, List<String> materialNames, boolean requireBlock) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        for (String materialName : materialNames) {
            if (materialName == null || materialName.isBlank()) {
                continue;
            }

            String normalizedName = materialName.trim().toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(normalizedName);
            if (material == null || (requireBlock && !material.isBlock()) || (!requireBlock && !material.isItem())) {
                plugin.getLogger().warning("Ungültiges Material in " + configPath + ": " + materialName);
                continue;
            }

            materials.add(material);
        }

        return materials;
    }

    private Set<String> toStringSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.trim());
            }
        }

        return Collections.unmodifiableSet(set);
    }

    private record ViolationActionConfig(
            boolean enabled,
            int afterBlocks,
            int cooldownSeconds,
            String content,
            String actionbarContent
    ) {
    }

    private record ResourceMonitorConfig(
            boolean enabled,
            String mode,
            Set<String> monitoredWorlds,
            Set<String> ignoredWorlds,
            String bypassPermission,
            String notifyPermission,
            boolean auditNotifyStaff,
            boolean auditLogToConsole,
            String staffMessage,
            int auditLogCooldownSeconds,
            int violationWindowSeconds,
            Map<ViolationAction, ViolationActionConfig> violationActionConfigs,
            JailActionConfig jailActionConfig,
            Map<String, ResourceWorldRule> resourceWorldRules
    ) {
    }

    private record JailActionConfig(
            boolean enabled,
            String mode,
            int afterBlockedAttempts,
            int cooldownMinutes,
            boolean executeOncePerWindow,
            String command,
            boolean notifyStaff,
            String staffMessage,
            String playerMessage
    ) {
    }
}
