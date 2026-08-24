package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.FarmweltPlugin;
import de.minecraftgilde.farmwelt.config.ConfigManager;
import de.minecraftgilde.farmwelt.gui.FarmweltMenu;
import de.minecraftgilde.farmwelt.model.ResourceMatch;
import de.minecraftgilde.farmwelt.model.ResourceWorldRule;
import de.minecraftgilde.farmwelt.model.ViolationAction;
import de.minecraftgilde.farmwelt.model.ViolationSnapshot;
import de.minecraftgilde.farmwelt.service.ClaimProtectionService;
import de.minecraftgilde.farmwelt.service.ResourceDetectionService;
import de.minecraftgilde.farmwelt.service.ViolationService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.Nullable;

public final class FarmweltCommand implements BasicCommand, Listener {

    private static final String USE_PERMISSION = "farmwelt.use";
    private static final String ADMIN_PERMISSION = "farmwelt.admin";

    private final FarmweltPlugin plugin;
    private final FarmweltMenu farmweltMenu;
    private final ClaimProtectionService claimProtectionService;
    private final ResourceDetectionService resourceDetectionService;
    private final ViolationService violationService;
    private final ConfigManager configManager;
    private final FarmweltAdminCommandHandler adminCommandHandler;
    private final Set<UUID> monitorDebugPlayers = ConcurrentHashMap.newKeySet();

    public FarmweltCommand(
            FarmweltPlugin plugin,
            FarmweltMenu farmweltMenu,
            ClaimProtectionService claimProtectionService,
            ResourceDetectionService resourceDetectionService,
            ViolationService violationService,
            ConfigManager configManager,
            FarmweltAdminCommandHandler adminCommandHandler
    ) {
        this.plugin = plugin;
        this.farmweltMenu = farmweltMenu;
        this.claimProtectionService = claimProtectionService;
        this.resourceDetectionService = resourceDetectionService;
        this.violationService = violationService;
        this.configManager = configManager;
        this.adminCommandHandler = adminCommandHandler;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 0) {
            handleSubCommand(sender, args);
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur von Spielern ausgeführt werden.");
            return;
        }

        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage("Dafür hast du keine Berechtigung.");
            return;
        }

        farmweltMenu.open(player);
    }

    @Override
    public @Nullable String permission() {
        return null;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        BukkitCommandAudience audience = new BukkitCommandAudience(plugin, sender);
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(adminCommandHandler.suggest(audience, args));
            if (canUseAdminCommand(sender) && "info".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                suggestions.add("info");
            }
            if (canUseAdminCommand(sender)
                    && sender instanceof Player
                    && "debug".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                suggestions.add("debug");
            }
            return suggestions;
        }

        Collection<String> adminSuggestions = adminCommandHandler.suggest(audience, args);
        if (!adminSuggestions.isEmpty()) {
            return adminSuggestions;
        }

        if (args.length == 2
                && "debug".equalsIgnoreCase(args[0])
                && sender instanceof Player
                && canUseAdminCommand(sender)) {
            return List.of("claim", "monitor", "violations");
        }

        if (args.length == 3
                && "debug".equalsIgnoreCase(args[0])
                && "violations".equalsIgnoreCase(args[1])
                && sender instanceof Player
                && canUseAdminCommand(sender)) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }

        return List.of();
    }

    private void handleSubCommand(CommandSender sender, String[] args) {
        if (adminCommandHandler.handle(new BukkitCommandAudience(plugin, sender), args)) {
            return;
        }

        if (args.length == 1 && "info".equalsIgnoreCase(args[0])) {
            handleInfo(sender);
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl kann nur von Spielern ausgeführt werden.");
            return;
        }

        if (args.length == 2 && "debug".equalsIgnoreCase(args[0]) && "claim".equalsIgnoreCase(args[1])) {
            handleClaimDebug(player);
            return;
        }

        if (args.length == 2 && "debug".equalsIgnoreCase(args[0]) && "monitor".equalsIgnoreCase(args[1])) {
            handleMonitorDebug(player);
            return;
        }

        if ((args.length == 2 || args.length == 3)
                && "debug".equalsIgnoreCase(args[0])
                && "violations".equalsIgnoreCase(args[1])) {
            handleViolationsDebug(player, args);
            return;
        }

        sender.sendMessage("Unbekannter Farmwelt-Befehl.");
    }

    private void handleInfo(CommandSender sender) {
        if (!canUseAdminCommand(sender)) {
            sender.sendMessage("Dafür hast du keine Berechtigung.");
            return;
        }

        sender.sendMessage("Farmwelt " + plugin.getPluginMeta().getVersion());
        sender.sendMessage("Geladene Farmwelten: " + configManager.getFarmweltMenuItems().size());
        sender.sendMessage("Ressourcenmonitor: " + yesNo(configManager.isResourceMonitorEnabled())
                + " (Modus: " + configManager.getResourceMonitorMode() + ")");
        sender.sendMessage("Claim-Provider: " + claimProtectionService.getProviderName());
        sender.sendMessage("Claim-Hook aktiv: " + yesNo(claimProtectionService.isAvailable()));
        sender.sendMessage("BetterRTP aktiv: " + yesNo(isPluginEnabled("BetterRTP")));
        sender.sendMessage("GriefPrevention aktiv: " + yesNo(isPluginEnabled("GriefPrevention")));
        sender.sendMessage("Jail-Modus: " + configManager.getJailMode());
    }

    private void handleClaimDebug(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage("Dafür hast du keine Berechtigung.");
            return;
        }

        boolean claimProtectionActive = claimProtectionService.isAvailable();
        boolean insideClaim = claimProtectionService.isInsideClaim(player.getLocation());

        player.sendMessage("Claim-Provider: " + claimProtectionService.getProviderName());
        player.sendMessage("Claim-Schutz aktiv: " + yesNo(claimProtectionActive));
        player.sendMessage("Position liegt in Claim: " + yesNo(insideClaim));
    }

    private void handleMonitorDebug(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage("Dafür hast du keine Berechtigung.");
            return;
        }

        UUID playerId = player.getUniqueId();
        if (monitorDebugPlayers.remove(playerId)) {
            player.sendMessage("Monitor-Debug deaktiviert.");
            return;
        }

        monitorDebugPlayers.add(playerId);
        player.sendMessage("Monitor-Debug aktiviert. Rechtsklicke einen Block, um ihn zu prüfen.");
        player.sendMessage("Mit /farmwelt debug monitor schaltest du den Modus wieder aus.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMonitorDebugInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!monitorDebugPlayers.contains(player.getUniqueId())) {
            return;
        }

        if (!player.hasPermission(ADMIN_PERMISSION)) {
            monitorDebugPlayers.remove(player.getUniqueId());
            player.sendMessage("Dafür hast du keine Berechtigung.");
            player.sendMessage("Monitor-Debug deaktiviert.");
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        event.setCancelled(true);
        sendMonitorDebug(player, block);
    }

    @EventHandler
    public void onMonitorDebugQuit(PlayerQuitEvent event) {
        monitorDebugPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void sendMonitorDebug(Player player, Block block) {
        World world = block.getWorld();
        String worldName = world.getName();
        boolean monitorEnabled = configManager.isResourceMonitorEnabled();
        boolean validMode = configManager.isResourceMonitorAuditMode()
                || configManager.isResourceMonitorWarnMode()
                || configManager.isResourceMonitorEnforceMode();
        boolean claimFailDisablesMonitor = claimProtectionService.wouldDisableResourceMonitor();
        boolean monitoredWorld = configManager.isMonitoredWorld(worldName);
        boolean ignoredWorld = configManager.isIgnoredWorld(worldName);
        boolean hasWorldRule = configManager.hasResourceWorldRule(worldName);
        Optional<ResourceWorldRule> worldRule = configManager.getResourceWorldRule(worldName);
        Optional<ResourceMatch> match = resourceDetectionService.detect(world, block.getType());
        boolean insideClaim = claimProtectionService.isInsideClaim(block.getLocation());
        boolean skippedByClaim = claimProtectionService.shouldSkipInsideClaims() && insideClaim;
        String bypassPermission = configManager.getBypassPermission();
        boolean hasBypass = !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
        int currentCount = violationService.getSnapshot(player.getUniqueId())
                .map(ViolationSnapshot::currentCount)
                .orElse(0);
        int nextCount = currentCount + 1;
        boolean cancelBreakEnabled = configManager.isViolationActionEnabled(ViolationAction.CANCEL_BREAK);
        int cancelBreakThreshold = configManager.getViolationActionAfterBlocks(ViolationAction.CANCEL_BREAK);
        boolean cancelBreakThresholdReached = nextCount >= cancelBreakThreshold;
        boolean wouldBeHandled = monitorEnabled
                && validMode
                && !claimFailDisablesMonitor
                && !hasBypass
                && monitoredWorld
                && !ignoredWorld
                && hasWorldRule
                && match.isPresent()
                && !skippedByClaim;
        boolean wouldBeBlocked = wouldBeHandled
                && configManager.isResourceMonitorEnforceMode()
                && cancelBreakEnabled
                && cancelBreakThresholdReached;

        player.sendMessage("Monitor-Debug für den angeklickten Block:");
        player.sendMessage("Welt: " + worldName);
        player.sendMessage("Position: " + block.getX() + " " + block.getY() + " " + block.getZ());
        player.sendMessage("Block: " + block.getType().name());
        player.sendMessage("Ressourcenmonitor aktiv: " + yesNo(monitorEnabled));
        player.sendMessage("Modus: " + configManager.getResourceMonitorMode());
        player.sendMessage("Claim-Fail-Mode deaktiviert Monitor: " + yesNo(claimFailDisablesMonitor));
        player.sendMessage("Welt überwacht: " + yesNo(monitoredWorld));
        player.sendMessage("Welt ignoriert: " + yesNo(ignoredWorld));
        player.sendMessage("Weltregel vorhanden: " + yesNo(hasWorldRule));
        player.sendMessage("Weltregel-Typ: " + worldRule.map(rule -> rule.getType().getConfigValue()).orElse("-"));
        player.sendMessage("Kategorie: " + match.map(ResourceMatch::category).orElse("-"));
        player.sendMessage("In Claim: " + yesNo(insideClaim));
        player.sendMessage("Würde wegen Claim ignoriert: " + yesNo(skippedByClaim));
        player.sendMessage("Spieler hat Bypass: " + yesNo(hasBypass));
        player.sendMessage("Würde als Ressource erkannt: " + yesNo(match.isPresent()));
        player.sendMessage("Würde vom Monitor geprüft: " + yesNo(wouldBeHandled));
        player.sendMessage("Aktuelle Verstöße: " + currentCount);
        player.sendMessage("Verstöße nach erkanntem Abbau: " + nextCount);
        player.sendMessage("Blockieren aktiv konfiguriert: " + yesNo(cancelBreakEnabled));
        player.sendMessage("Blockier-Schwelle: " + cancelBreakThreshold);
        player.sendMessage("Blockier-Schwelle erreicht: " + yesNo(cancelBreakThresholdReached));
        player.sendMessage("Würde aktuell blockiert werden: " + yesNo(wouldBeBlocked));
    }

    private void handleViolationsDebug(Player player, String[] args) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage("Dafür hast du keine Berechtigung.");
            return;
        }

        Player target = player;
        if (args.length == 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                player.sendMessage("Spieler ist nicht online: " + args[2]);
                return;
            }
        }

        Player finalTarget = target;
        violationService.getSnapshot(finalTarget.getUniqueId()).ifPresentOrElse(
                snapshot -> sendViolationSnapshot(player, finalTarget, snapshot),
                () -> sendEmptyViolationSnapshot(player, finalTarget)
        );
    }

    private void sendViolationSnapshot(Player player, Player target, ViolationSnapshot snapshot) {
        if (!player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("Violation-Status für: " + target.getName());
        }

        player.sendMessage("Verstöße im aktuellen Zeitfenster: " + snapshot.currentCount());
        player.sendMessage("Blockierte Versuche: " + snapshot.blockedCount());
        player.sendMessage("Zeitfenster: " + violationService.getWindowSeconds() + " Sekunden");
        player.sendMessage("Verbleibende Zeit: " + violationService.getRemainingWindowSeconds(snapshot) + " Sekunden");
        sendViolationThresholds(player, snapshot.currentCount());
        sendJailStatus(player, snapshot.jailActionExecutedInCurrentWindow());
        player.sendMessage("Letzter Block: " + snapshot.latestBlock().name());
        player.sendMessage("Kategorie: " + snapshot.latestCategory());
        player.sendMessage("Letzte Position: " + snapshot.latestWorld()
                + " " + snapshot.latestX()
                + " " + snapshot.latestY()
                + " " + snapshot.latestZ());
    }

    private void sendEmptyViolationSnapshot(Player player, Player target) {
        if (!player.getUniqueId().equals(target.getUniqueId())) {
            player.sendMessage("Violation-Status für: " + target.getName());
        }

        player.sendMessage("Verstöße im aktuellen Zeitfenster: 0");
        player.sendMessage("Blockierte Versuche: 0");
        player.sendMessage("Zeitfenster: " + violationService.getWindowSeconds() + " Sekunden");
        sendViolationThresholds(player, 0);
        sendJailStatus(player, false);
        player.sendMessage("Es wurde keine aktuelle Ressource erfasst.");
    }

    private void sendViolationThresholds(Player player, int currentCount) {
        player.sendMessage("Warn-Schwelle: " + configManager.getViolationActionAfterBlocks(ViolationAction.WARNING));
        player.sendMessage("Staff-Schwelle: " + configManager.getViolationActionAfterBlocks(ViolationAction.NOTIFY_STAFF));
        player.sendMessage("Blockier-Schwelle: " + configManager.getViolationActionAfterBlocks(ViolationAction.CANCEL_BREAK));
        player.sendMessage("Blockieren aktiv: " + yesNo(isCancelBreakActive(currentCount)));
        player.sendMessage("Modus: " + configManager.getResourceMonitorMode());
    }

    private void sendJailStatus(Player player, boolean jailActionExecutedInCurrentWindow) {
        player.sendMessage("Jail-Modus: " + configManager.getJailMode());
        player.sendMessage("Jail-Schwelle: " + configManager.getJailAfterBlockedAttempts());
        player.sendMessage("Jail-Cooldown: " + configManager.getJailCooldownMinutes() + " Minuten");
        player.sendMessage("Jail-Aktion in diesem Fenster bereits ausgelöst: " + yesNo(jailActionExecutedInCurrentWindow));
    }

    private boolean isCancelBreakActive(int currentCount) {
        return configManager.isResourceMonitorEnforceMode()
                && configManager.isViolationActionEnabled(ViolationAction.CANCEL_BREAK)
                && currentCount >= configManager.getViolationActionAfterBlocks(ViolationAction.CANCEL_BREAK);
    }

    private boolean canUseAdminCommand(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission(ADMIN_PERMISSION);
    }

    private boolean isPluginEnabled(String pluginName) {
        return plugin.getServer().getPluginManager().isPluginEnabled(pluginName);
    }

    private String yesNo(boolean value) {
        return value ? "ja" : "nein";
    }
}
