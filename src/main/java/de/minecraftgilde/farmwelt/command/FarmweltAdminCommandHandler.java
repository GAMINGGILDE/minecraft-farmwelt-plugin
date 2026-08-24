package de.minecraftgilde.farmwelt.command;

import de.minecraftgilde.farmwelt.reset.FarmworldResetConfig;
import de.minecraftgilde.farmwelt.reset.FarmworldResetExecutor;
import de.minecraftgilde.farmwelt.reset.FarmworldResetService;
import de.minecraftgilde.farmwelt.reset.FarmworldResetState;
import de.minecraftgilde.farmwelt.reset.FarmworldType;
import de.minecraftgilde.farmwelt.reset.ResetOptions;
import de.minecraftgilde.farmwelt.reset.ResetDueState;
import de.minecraftgilde.farmwelt.reset.ResetDueStateEvaluator;
import de.minecraftgilde.farmwelt.reset.ResetResult;
import de.minecraftgilde.farmwelt.reset.ResetStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FarmweltAdminCommandHandler {

    static final String STATUS_PERMISSION = "farmwelt.admin.status";
    static final String RELOAD_PERMISSION = "farmwelt.admin.reload";
    static final String RESET_PERMISSION = "farmwelt.admin.reset";
    private static final String NO_PERMISSION = "§cDu hast keine Berechtigung für diesen Befehl.";

    private final FarmworldResetService resetService;
    private final FarmworldResetExecutor resetExecutor;
    private final FarmworldStatusFormatter statusFormatter;
    private final ReloadAction reloadAction;
    private final ResetDueStateEvaluator dueStateEvaluator;
    private final Clock clock;
    private final Logger logger;

    public FarmweltAdminCommandHandler(
            FarmworldResetService resetService,
            FarmworldResetExecutor resetExecutor,
            FarmworldStatusFormatter statusFormatter,
            ReloadAction reloadAction,
            Clock clock,
            Logger logger
    ) {
        this.resetService = Objects.requireNonNull(resetService, "resetService");
        this.resetExecutor = Objects.requireNonNull(resetExecutor, "resetExecutor");
        this.statusFormatter = Objects.requireNonNull(statusFormatter, "statusFormatter");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
        this.dueStateEvaluator = new ResetDueStateEvaluator();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    boolean handle(AdminCommandAudience audience, String[] args) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            return false;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                handleStatus(audience, args);
                yield true;
            }
            case "reload" -> {
                handleReload(audience, args);
                yield true;
            }
            case "reset" -> {
                handleReset(audience, args);
                yield true;
            }
            default -> false;
        };
    }

    Collection<String> suggest(AdminCommandAudience audience, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            addIfPermitted(suggestions, audience, STATUS_PERMISSION, "status");
            addIfPermitted(suggestions, audience, RELOAD_PERMISSION, "reload");
            addIfPermitted(suggestions, audience, RESET_PERMISSION, "reset");
            return filterPrefix(suggestions, args[0]);
        }
        if (args.length == 2
                && "status".equalsIgnoreCase(args[0])
                && audience.hasPermission(STATUS_PERMISSION)) {
            return filterPrefix(configuredKeys(), args[1]);
        }
        if (args.length == 2
                && "reset".equalsIgnoreCase(args[0])
                && audience.hasPermission(RESET_PERMISSION)) {
            return filterPrefix(List.of("force"), args[1]);
        }
        if (args.length == 3
                && "reset".equalsIgnoreCase(args[0])
                && "force".equalsIgnoreCase(args[1])
                && audience.hasPermission(RESET_PERMISSION)) {
            return filterPrefix(configuredKeys(), args[2]);
        }
        if (args.length == 4
                && "reset".equalsIgnoreCase(args[0])
                && "force".equalsIgnoreCase(args[1])
                && audience.hasPermission(RESET_PERMISSION)) {
            Optional<FarmworldResetConfig> config = findConfig(args[2]);
            if (config.isPresent() && config.orElseThrow().farmworldType() == FarmworldType.END) {
                return filterPrefix(List.of("--dragon"), args[3]);
            }
        }
        return List.of();
    }

    private void handleStatus(AdminCommandAudience audience, String[] args) {
        if (!requirePermission(audience, STATUS_PERMISSION)) {
            return;
        }
        if (args.length > 2) {
            audience.sendMessage("§eVerwendung: /farmwelt status [welt]");
            return;
        }

        if (args.length == 1) {
            List<FarmworldResetStatusSnapshot> snapshots = resetService.getConfiguredWorlds().stream()
                    .map(this::snapshot)
                    .toList();
            audience.sendMessages(statusFormatter.formatOverview(snapshots));
            return;
        }

        Optional<FarmworldResetConfig> config = findConfig(args[1]);
        if (config.isEmpty()) {
            audience.sendMessages(List.of(
                    "§cUnbekannte Farmwelt '" + args[1] + "'.",
                    availableWorldsMessage()
            ));
            return;
        }
        audience.sendMessages(statusFormatter.formatDetails(snapshot(config.orElseThrow())));
    }

    private void handleReload(AdminCommandAudience audience, String[] args) {
        if (!requirePermission(audience, RELOAD_PERMISSION)) {
            return;
        }
        if (args.length != 1) {
            audience.sendMessage("§eVerwendung: /farmwelt reload");
            return;
        }

        try {
            reloadAction.reload();
            audience.sendMessage("§aFarmwelt-Konfiguration wurde neu geladen.");
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Farmwelt-Konfiguration konnte nicht vollständig neu geladen werden.", exception);
            audience.sendMessages(List.of(
                    "§cFarmwelt-Konfiguration konnte nicht neu geladen werden.",
                    "§cBitte Serverlog prüfen!"
            ));
        }
    }

    private void handleReset(AdminCommandAudience audience, String[] args) {
        if (!requirePermission(audience, RESET_PERMISSION)) {
            return;
        }
        if ((args.length != 3 && args.length != 4) || !"force".equalsIgnoreCase(args[1])) {
            sendResetUsage(audience);
            return;
        }
        boolean allowEnderDragon = args.length == 4;
        if (allowEnderDragon && !"--dragon".equalsIgnoreCase(args[3])) {
            sendResetUsage(audience);
            return;
        }

        Optional<FarmworldResetConfig> config = findConfig(args[2]);
        if (config.isEmpty()) {
            audience.sendMessages(List.of(
                    "§cDie Farmwelt '" + args[2] + "' ist nicht konfiguriert.",
                    availableWorldsMessage()
            ));
            return;
        }

        FarmworldResetConfig resetConfig = config.orElseThrow();
        if (allowEnderDragon && resetConfig.farmworldType() != FarmworldType.END) {
            audience.sendMessage("§cDie Option --dragon kann nur für eine End-Farmwelt verwendet werden.");
            return;
        }

        String farmworldKey = resetConfig.farmworldKey();
        String initiator = "CONSOLE".equals(audience.name())
                ? "CONSOLE"
                : "Admin '" + audience.name() + "'";
        logger.info(initiator + " hat einen manuellen Reset für Farmwelt '"
                + farmworldKey + "' angefordert.");

        final CompletableFuture<ResetResult> resetFuture;
        try {
            resetFuture = Objects.requireNonNull(
                    allowEnderDragon
                            ? resetExecutor.reset(farmworldKey, ResetOptions.allowingEnderDragon())
                            : resetExecutor.reset(farmworldKey),
                    "resetExecutor.reset(...)"
            );
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Manueller Reset für Farmwelt '" + farmworldKey
                    + "' konnte nicht gestartet werden.", exception);
            audience.sendMessages(ResetCommandMessages.forResult(internalError(farmworldKey, exception)));
            return;
        }

        audience.sendMessage("§eReset für Farmwelt '" + farmworldKey + "' wurde gestartet.");
        resetFuture.whenComplete((result, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Unerwarteter Fehler beim Abschluss des manuellen Resets für Farmwelt '"
                        + farmworldKey + "'.", failure);
                audience.sendMessages(ResetCommandMessages.forResult(internalError(farmworldKey, failure)));
                return;
            }
            if (result == null) {
                logger.severe("Reset-Engine lieferte kein Ergebnis für Farmwelt '" + farmworldKey + "'.");
                audience.sendMessages(ResetCommandMessages.forResult(internalError(
                        farmworldKey,
                        new IllegalStateException("Reset-Engine lieferte kein Ergebnis.")
                )));
                return;
            }
            audience.sendMessages(ResetCommandMessages.forResult(result));
        });
    }

    private void sendResetUsage(AdminCommandAudience audience) {
        audience.sendMessages(List.of(
                "§eVerwendung: /farmwelt reset force <welt>",
                "§eEnd-Event: /farmwelt reset force end --dragon"
        ));
    }

    private FarmworldResetStatusSnapshot snapshot(FarmworldResetConfig config) {
        Optional<FarmworldResetState> state = resetService.getState(config.farmworldKey());
        Instant evaluatedAt = clock.instant();
        ResetDueState dueState = dueStateEvaluator.evaluate(config, state, evaluatedAt);
        return new FarmworldResetStatusSnapshot(
                config,
                state,
                resetExecutor.isResetRunning(config.farmworldKey()),
                dueState,
                evaluatedAt
        );
    }

    private Optional<FarmworldResetConfig> findConfig(String requestedKey) {
        return resetService.getConfiguredWorlds().stream()
                .filter(config -> config.farmworldKey().equalsIgnoreCase(requestedKey))
                .findFirst();
    }

    private List<String> configuredKeys() {
        return resetService.getConfiguredWorlds().stream()
                .map(FarmworldResetConfig::farmworldKey)
                .toList();
    }

    private String availableWorldsMessage() {
        List<String> configuredKeys = configuredKeys();
        return configuredKeys.isEmpty()
                ? "§7Verfügbar: keine"
                : "§7Verfügbar: " + String.join(", ", configuredKeys);
    }

    private boolean requirePermission(AdminCommandAudience audience, String permission) {
        if (audience.hasPermission(permission)) {
            return true;
        }
        audience.sendMessage(NO_PERMISSION);
        return false;
    }

    private void addIfPermitted(
            List<String> suggestions,
            AdminCommandAudience audience,
            String permission,
            String suggestion
    ) {
        if (audience.hasPermission(permission)) {
            suggestions.add(suggestion);
        }
    }

    private Collection<String> filterPrefix(List<String> values, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }

    private ResetResult internalError(String farmworldKey, Throwable cause) {
        return new ResetResult(
                farmworldKey,
                "",
                ResetStatus.INTERNAL_ERROR,
                "Der Reset ist durch einen internen Fehler fehlgeschlagen.",
                cause
        );
    }

    @FunctionalInterface
    public interface ReloadAction {

        void reload() throws Exception;
    }
}
