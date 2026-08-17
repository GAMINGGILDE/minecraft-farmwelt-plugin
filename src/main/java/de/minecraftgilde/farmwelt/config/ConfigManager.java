package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.gui.FarmweltMenuItem;
import de.minecraftgilde.farmwelt.gui.TeleportAction;
import de.minecraftgilde.farmwelt.model.ResourceWorldRule;
import de.minecraftgilde.farmwelt.model.ResourceWorldType;
import de.minecraftgilde.farmwelt.model.ViolationAction;
import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.ResetIntervalParser;
import java.time.Duration;
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

    private final JavaPlugin plugin;
    private final ResetIntervalParser resetIntervalParser = new ResetIntervalParser();
    private List<FarmweltMenuItem> farmweltMenuItems = List.of();
    private List<FarmworldResetConfig> farmworldResetConfigs = List.of();
    private boolean resourceMonitorEnabled;
    private String resourceMonitorMode = "audit";
    private Set<String> monitoredWorlds = Set.of();
    private Set<String> ignoredWorlds = Set.of();
    private String bypassPermission = "farmwelt.bypass";
    private String notifyPermission = "farmwelt.notify";
    private boolean auditNotifyStaff = true;
    private boolean auditLogToConsole = true;
    private String staffMessage = "&e[Farmwelt-Audit] &f{player} hat &c{block} &fin &7{world} &fbei &7{x} {y} {z} &fabgebaut. Kategorie: &7{category}";
    private int auditLogCooldownSeconds = DEFAULT_AUDIT_LOG_COOLDOWN_SECONDS;
    private int violationWindowSeconds = 600;
    private Map<ViolationAction, ViolationActionConfig> violationActionConfigs = Map.of();
    private JailActionConfig jailActionConfig = createDefaultJailActionConfig();
    private Map<String, ResourceWorldRule> resourceWorldRules = Map.of();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
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
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("farmworlds");
        if (section == null) {
            farmworldResetConfigs = List.of();
            return;
        }

        List<FarmworldResetConfig> loadedConfigs = new ArrayList<>();
        for (String farmworldKey : section.getKeys(false)) {
            ConfigurationSection farmworldSection = section.getConfigurationSection(farmworldKey);
            if (farmworldSection == null) {
                continue;
            }

            ConfigurationSection resetSection = farmworldSection.getConfigurationSection("reset");
            if (resetSection == null) {
                continue;
            }

            boolean enabled = farmworldSection.getBoolean("enabled", true)
                    && resetSection.getBoolean("enabled", false);
            FarmworldResetConfig resetConfig = loadFarmworldResetConfig(
                    farmworldKey,
                    resetSection,
                    enabled
            );
            if (resetConfig != null) {
                loadedConfigs.add(resetConfig);
            }
        }

        farmworldResetConfigs = List.copyOf(loadedConfigs);
    }

    public List<FarmworldResetConfig> getFarmworldResetConfigs() {
        return farmworldResetConfigs;
    }

    public void loadResourceMonitorConfig() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("resource-monitor");
        if (section == null) {
            resourceMonitorEnabled = false;
            resourceWorldRules = Map.of();
            violationActionConfigs = createDefaultViolationActionConfigs();
            jailActionConfig = createDefaultJailActionConfig();
            plugin.getLogger().warning("Config-Bereich 'resource-monitor' fehlt. Der Ressourcenmonitor bleibt deaktiviert.");
            return;
        }

        resourceMonitorEnabled = section.getBoolean("enabled", false);
        resourceMonitorMode = section.getString("mode", "audit").toLowerCase(Locale.ROOT);
        monitoredWorlds = toStringSet(section.getStringList("monitored-worlds"));
        ignoredWorlds = toStringSet(section.getStringList("ignored-worlds"));
        bypassPermission = section.getString("bypass-permission", "farmwelt.bypass");
        notifyPermission = section.getString("notify-permission", "farmwelt.notify");
        violationWindowSeconds = Math.max(1, section.getInt("violation-window-seconds", 600));

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

        resourceWorldRules = loadResourceWorldRules(section.getConfigurationSection("world-rules"));
        violationActionConfigs = loadViolationActionConfigs(section.getConfigurationSection("actions"));
        jailActionConfig = loadJailActionConfig(section.getConfigurationSection("actions"));
    }

    public boolean isResourceMonitorEnabled() {
        return resourceMonitorEnabled;
    }

    public boolean isResourceMonitorAuditMode() {
        return "audit".equalsIgnoreCase(resourceMonitorMode);
    }

    public boolean isResourceMonitorWarnMode() {
        return "warn".equalsIgnoreCase(resourceMonitorMode);
    }

    public boolean isResourceMonitorEnforceMode() {
        return "enforce".equalsIgnoreCase(resourceMonitorMode);
    }

    public String getResourceMonitorMode() {
        return resourceMonitorMode == null ? "" : resourceMonitorMode;
    }

    public boolean isMonitoredWorld(String worldName) {
        return monitoredWorlds.contains(worldName);
    }

    public boolean isIgnoredWorld(String worldName) {
        return ignoredWorlds.contains(worldName);
    }

    public boolean hasResourceWorldRule(String worldName) {
        return resourceWorldRules.containsKey(worldName);
    }

    public Optional<ResourceWorldRule> getResourceWorldRule(String worldName) {
        return Optional.ofNullable(resourceWorldRules.get(worldName));
    }

    public String getBypassPermission() {
        return bypassPermission == null ? "" : bypassPermission;
    }

    public String getNotifyPermission() {
        return notifyPermission == null ? "" : notifyPermission;
    }

    public boolean isAuditNotifyStaff() {
        return auditNotifyStaff;
    }

    public boolean isAuditLogToConsole() {
        return auditLogToConsole;
    }

    public String getStaffMessage() {
        return staffMessage == null ? "" : staffMessage;
    }

    public int getAuditLogCooldownSeconds() {
        return auditLogCooldownSeconds;
    }

    public int getViolationWindowSeconds() {
        return violationWindowSeconds;
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
        return jailActionConfig.enabled();
    }

    public String getJailMode() {
        return jailActionConfig.mode() == null ? "notify-only" : jailActionConfig.mode();
    }

    public int getJailAfterBlockedAttempts() {
        return jailActionConfig.afterBlockedAttempts();
    }

    public int getJailCooldownMinutes() {
        return jailActionConfig.cooldownMinutes();
    }

    public boolean isJailExecuteOncePerWindow() {
        return jailActionConfig.executeOncePerWindow();
    }

    public String getJailCommand() {
        return jailActionConfig.command() == null ? "" : jailActionConfig.command();
    }

    public boolean isJailNotifyStaff() {
        return jailActionConfig.notifyStaff();
    }

    public String getJailStaffMessage() {
        return jailActionConfig.staffMessage() == null ? "" : jailActionConfig.staffMessage();
    }

    public String getJailPlayerMessage() {
        return jailActionConfig.playerMessage() == null ? "" : jailActionConfig.playerMessage();
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

    private FarmworldResetConfig loadFarmworldResetConfig(
            String farmworldKey,
            ConfigurationSection resetSection,
            boolean enabled
    ) {
        String worldName = resetSection.getString("world");
        if (worldName == null || worldName.isBlank()) {
            plugin.getLogger().warning("Reset-Konfiguration für Farmwelt '" + farmworldKey
                    + "' enthält keinen Bukkit-Weltnamen. Reset für diese Farmwelt wurde deaktiviert.");
            return null;
        }

        Object intervalValue = resetSection.get("interval");
        String intervalText = intervalValue instanceof String value ? value : null;
        Optional<Duration> interval = resetIntervalParser.parse(intervalText);
        if (interval.isEmpty()) {
            plugin.getLogger().warning("Ungültiges Reset-Intervall '" + String.valueOf(intervalValue)
                    + "' für Farmwelt '" + farmworldKey
                    + "'. Reset für diese Farmwelt wurde deaktiviert.");
            return null;
        }

        return new FarmworldResetConfig(
                farmworldKey,
                worldName.trim(),
                enabled,
                interval.orElseThrow()
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

    private ViolationActionConfig getViolationActionConfig(ViolationAction action) {
        ViolationActionConfig config = violationActionConfigs.get(action);
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
