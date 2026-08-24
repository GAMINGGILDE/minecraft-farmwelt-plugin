package de.minecraftgilde.farmwelt.service;

import de.minecraftgilde.farmwelt.claim.ClaimProtectionProvider;
import de.minecraftgilde.farmwelt.claim.GriefPreventionClaimProtectionProvider;
import de.minecraftgilde.farmwelt.claim.NoopClaimProtectionProvider;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClaimProtectionService {

    private static final String PROVIDER_GRIEF_PREVENTION = "GriefPrevention";
    private static final String FAIL_MODE_DISABLE_MONITOR = "disable-monitor";

    private final JavaPlugin plugin;
    private volatile ClaimProtectionState state;

    public ClaimProtectionService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("resource-monitor.claim-protection");
        boolean enabled = section != null && section.getBoolean("enabled", false);
        boolean skipInsideClaims = section == null
                || section.getBoolean("skip-inside-claims", true);
        String configuredProviderName = section == null
                ? PROVIDER_GRIEF_PREVENTION
                : section.getString("provider", PROVIDER_GRIEF_PREVENTION);
        String failMode = section == null
                ? FAIL_MODE_DISABLE_MONITOR
                : section.getString("fail-mode", FAIL_MODE_DISABLE_MONITOR);
        boolean ignoreHeight = section == null || section.getBoolean("ignore-height", true);

        ClaimProtectionProvider provider = createProvider(
                enabled,
                configuredProviderName,
                ignoreHeight
        );
        boolean resourceMonitorWouldBeDisabled = enabled
                && !provider.isAvailable()
                && FAIL_MODE_DISABLE_MONITOR.equalsIgnoreCase(failMode);
        ClaimProtectionState loadedState = new ClaimProtectionState(
                enabled,
                skipInsideClaims,
                configuredProviderName,
                provider,
                resourceMonitorWouldBeDisabled
        );
        state = loadedState;
        logStartupState(loadedState);
    }

    public boolean isAvailable() {
        ClaimProtectionState snapshot = state;
        return snapshot.enabled() && snapshot.provider().isAvailable();
    }

    public boolean isInsideClaim(Location location) {
        ClaimProtectionState snapshot = state;
        if (!snapshot.enabled() || !snapshot.provider().isAvailable() || location == null) {
            return false;
        }

        try {
            return snapshot.provider().isInsideClaim(location);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Fehler bei der Claim-Prüfung.", exception);
            return false;
        }
    }

    public boolean shouldSkipInsideClaims() {
        ClaimProtectionState snapshot = state;
        return snapshot.enabled() && snapshot.skipInsideClaims();
    }

    public String getProviderName() {
        return state.provider().getName();
    }

    public boolean wouldDisableResourceMonitor() {
        return state.resourceMonitorWouldBeDisabled();
    }

    private ClaimProtectionProvider createProvider(
            boolean enabled,
            String configuredProviderName,
            boolean ignoreHeight
    ) {
        if (!enabled) {
            return new NoopClaimProtectionProvider();
        }

        if (!PROVIDER_GRIEF_PREVENTION.equalsIgnoreCase(configuredProviderName)) {
            plugin.getLogger().warning("Unbekannter Claim-Provider konfiguriert: " + configuredProviderName);
            return new NoopClaimProtectionProvider();
        }

        GriefPreventionClaimProtectionProvider griefPreventionProvider =
                new GriefPreventionClaimProtectionProvider(plugin, ignoreHeight);
        if (!griefPreventionProvider.isAvailable()) {
            plugin.getLogger().warning("Claim-Schutz ist aktiviert, aber GriefPrevention ist nicht verfügbar.");
            return new NoopClaimProtectionProvider();
        }

        return griefPreventionProvider;
    }

    private void logStartupState(ClaimProtectionState snapshot) {
        boolean griefPreventionFound = plugin.getServer().getPluginManager().getPlugin(PROVIDER_GRIEF_PREVENTION) != null;
        plugin.getLogger().info("Claim-Schutz aktiviert: " + yesNo(snapshot.enabled()));
        plugin.getLogger().info("Konfigurierter Claim-Provider: "
                + snapshot.configuredProviderName());
        plugin.getLogger().info("GriefPrevention gefunden: " + yesNo(griefPreventionFound));
        plugin.getLogger().info("Claim-Hook aktiv: "
                + yesNo(snapshot.enabled() && snapshot.provider().isAvailable()));
        plugin.getLogger().info("Claims werden vom Ressourcenmonitor übersprungen: "
                + yesNo(snapshot.enabled() && snapshot.skipInsideClaims()));

        if (snapshot.resourceMonitorWouldBeDisabled()) {
            plugin.getLogger().warning("Der Ressourcenmonitor wird wegen fehlendem Claim-Provider deaktiviert.");
        }
    }

    private String yesNo(boolean value) {
        return value ? "ja" : "nein";
    }

    private record ClaimProtectionState(
            boolean enabled,
            boolean skipInsideClaims,
            String configuredProviderName,
            ClaimProtectionProvider provider,
            boolean resourceMonitorWouldBeDisabled
    ) {
    }
}
