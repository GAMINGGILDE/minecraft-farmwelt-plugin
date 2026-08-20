package de.minecraftgilde.farmwelt.service;

import de.minecraftgilde.farmwelt.config.ConfigManager;
import de.minecraftgilde.farmwelt.model.ResourceMatch;
import de.minecraftgilde.farmwelt.model.ViolationAction;
import de.minecraftgilde.farmwelt.model.ViolationRecord;
import de.minecraftgilde.farmwelt.model.ViolationResult;
import de.minecraftgilde.farmwelt.model.ViolationSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class ViolationService {

    private final ConcurrentMap<UUID, ViolationRecord> records = new ConcurrentHashMap<>();
    private volatile RuntimeConfig runtimeConfig;

    public ViolationService(ConfigManager configManager) {
        reload(configManager);
    }

    public void reload(ConfigManager configManager) {
        int windowSeconds = configManager.getViolationWindowSeconds();
        ActionConfig warningConfig = new ActionConfig(
                configManager.isViolationActionEnabled(ViolationAction.WARNING),
                configManager.getViolationActionAfterBlocks(ViolationAction.WARNING),
                configManager.getViolationActionCooldownSeconds(ViolationAction.WARNING)
        );
        ActionConfig staffNotifyConfig = new ActionConfig(
                configManager.isViolationActionEnabled(ViolationAction.NOTIFY_STAFF),
                configManager.getViolationActionAfterBlocks(ViolationAction.NOTIFY_STAFF),
                configManager.getViolationActionCooldownSeconds(ViolationAction.NOTIFY_STAFF)
        );
        ActionConfig cancelBreakConfig = new ActionConfig(
                configManager.isViolationActionEnabled(ViolationAction.CANCEL_BREAK),
                configManager.getViolationActionAfterBlocks(ViolationAction.CANCEL_BREAK),
                configManager.getViolationActionCooldownSeconds(ViolationAction.CANCEL_BREAK)
        );
        JailActionConfig jailConfig = new JailActionConfig(
                configManager.isJailActionEnabled() && !"disabled".equalsIgnoreCase(configManager.getJailMode()),
                configManager.getJailAfterBlockedAttempts(),
                configManager.getJailCooldownMinutes(),
                configManager.isJailExecuteOncePerWindow()
        );
        runtimeConfig = new RuntimeConfig(
                windowSeconds,
                windowSeconds * 1000L,
                warningConfig,
                staffNotifyConfig,
                cancelBreakConfig,
                jailConfig
        );
    }

    public ViolationResult registerViolation(
            Player player,
            Block block,
            ResourceMatch match,
            boolean runWarnActions,
            boolean runCancelActions
    ) {
        RuntimeConfig config = runtimeConfig;
        UUID playerId = player.getUniqueId();
        Instant now = Instant.now();
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Material material = match.material();
        String category = match.category();
        AtomicReference<ViolationRecord> updatedRecord = new AtomicReference<>();
        AtomicReference<Set<ViolationAction>> actionsToRun = new AtomicReference<>(Set.of());

        records.compute(playerId, (ignored, existingRecord) -> {
            boolean startNewWindow = existingRecord == null
                    || isExpired(existingRecord, now, config);
            int count = startNewWindow ? 1 : existingRecord.currentCount() + 1;
            int blockedCount = startNewWindow ? 0 : existingRecord.blockedCount();
            Instant windowStart = startNewWindow ? now : existingRecord.windowStart();
            Instant lastWarningTime = existingRecord == null ? null : existingRecord.lastWarningTime();
            Instant lastStaffNotifyTime = existingRecord == null ? null : existingRecord.lastStaffNotifyTime();
            Instant lastCancelBreakTime = existingRecord == null ? null : existingRecord.lastCancelBreakTime();
            Instant lastBlockedAttemptTime = startNewWindow ? null : existingRecord.lastBlockedAttemptTime();
            boolean jailActionExecutedInCurrentWindow = !startNewWindow
                    && existingRecord.jailActionExecutedInCurrentWindow();
            Instant lastJailActionTime = existingRecord == null ? null : existingRecord.lastJailActionTime();
            EnumSet<ViolationAction> actions = EnumSet.noneOf(ViolationAction.class);

            if (runWarnActions
                    && shouldRunAction(config.warning(), count, lastWarningTime, now)) {
                actions.add(ViolationAction.WARNING);
                lastWarningTime = now;
            }

            if (runWarnActions
                    && shouldRunAction(config.staffNotify(), count, lastStaffNotifyTime, now)) {
                actions.add(ViolationAction.NOTIFY_STAFF);
                lastStaffNotifyTime = now;
            }

            if (runCancelActions
                    && shouldRunAction(config.cancelBreak(), count, lastCancelBreakTime, now)) {
                actions.add(ViolationAction.CANCEL_BREAK);
                lastCancelBreakTime = now;
            }

            ViolationRecord newRecord = new ViolationRecord(
                    playerId,
                    count,
                    blockedCount,
                    windowStart,
                    now,
                    lastBlockedAttemptTime,
                    worldName,
                    x,
                    y,
                    z,
                    material,
                    category,
                    lastWarningTime,
                    lastStaffNotifyTime,
                    lastCancelBreakTime,
                    jailActionExecutedInCurrentWindow,
                    lastJailActionTime
            );
            updatedRecord.set(newRecord);
            actionsToRun.set(actions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(actions)));
            return newRecord;
        });

        return new ViolationResult(updatedRecord.get().toSnapshot(), actionsToRun.get());
    }

    public ViolationResult registerBlockedAttempt(Player player, Block block, ResourceMatch match) {
        RuntimeConfig config = runtimeConfig;
        UUID playerId = player.getUniqueId();
        Instant now = Instant.now();
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Material material = match.material();
        String category = match.category();
        AtomicReference<ViolationRecord> updatedRecord = new AtomicReference<>();
        AtomicReference<Set<ViolationAction>> actionsToRun = new AtomicReference<>(Set.of());

        records.compute(playerId, (ignored, existingRecord) -> {
            boolean startNewWindow = existingRecord == null
                    || isExpired(existingRecord, now, config);
            int count = startNewWindow ? 0 : existingRecord.currentCount();
            int blockedCount = startNewWindow ? 1 : existingRecord.blockedCount() + 1;
            Instant windowStart = startNewWindow ? now : existingRecord.windowStart();
            Instant lastViolationTime = startNewWindow ? now : existingRecord.lastViolationTime();
            Instant lastWarningTime = existingRecord == null ? null : existingRecord.lastWarningTime();
            Instant lastStaffNotifyTime = existingRecord == null ? null : existingRecord.lastStaffNotifyTime();
            Instant lastCancelBreakTime = existingRecord == null ? null : existingRecord.lastCancelBreakTime();
            boolean jailActionExecutedInCurrentWindow = !startNewWindow
                    && existingRecord.jailActionExecutedInCurrentWindow();
            Instant lastJailActionTime = existingRecord == null ? null : existingRecord.lastJailActionTime();
            EnumSet<ViolationAction> actions = EnumSet.noneOf(ViolationAction.class);

            if (shouldRunJailAction(
                    config.jail(),
                    blockedCount,
                    jailActionExecutedInCurrentWindow,
                    lastJailActionTime,
                    now
            )) {
                actions.add(ViolationAction.JAIL);
                jailActionExecutedInCurrentWindow = true;
                lastJailActionTime = now;
            }

            ViolationRecord newRecord = new ViolationRecord(
                    playerId,
                    count,
                    blockedCount,
                    windowStart,
                    lastViolationTime,
                    now,
                    worldName,
                    x,
                    y,
                    z,
                    material,
                    category,
                    lastWarningTime,
                    lastStaffNotifyTime,
                    lastCancelBreakTime,
                    jailActionExecutedInCurrentWindow,
                    lastJailActionTime
            );
            updatedRecord.set(newRecord);
            actionsToRun.set(actions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(actions)));
            return newRecord;
        });

        return new ViolationResult(updatedRecord.get().toSnapshot(), actionsToRun.get());
    }

    public Optional<ViolationSnapshot> getSnapshot(UUID playerId) {
        RuntimeConfig config = runtimeConfig;
        ViolationRecord record = records.get(playerId);
        if (record == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (isExpired(record, now, config)) {
            return Optional.empty();
        }

        return Optional.of(record.toSnapshot());
    }

    public int getWindowSeconds() {
        return runtimeConfig.windowSeconds();
    }

    public long getRemainingWindowSeconds(ViolationSnapshot snapshot) {
        RuntimeConfig config = runtimeConfig;
        long elapsedSeconds = Duration.between(snapshot.windowStart(), Instant.now()).toSeconds();
        return Math.max(0L, config.windowSeconds() - elapsedSeconds);
    }

    private boolean isExpired(ViolationRecord record, Instant now, RuntimeConfig config) {
        return Duration.between(record.windowStart(), now).toMillis() >= config.windowMillis();
    }

    private boolean shouldRunAction(ActionConfig config, int count, Instant lastRunTime, Instant now) {
        if (!config.enabled() || count < config.afterBlocks()) {
            return false;
        }

        long cooldownMillis = config.cooldownSeconds() * 1000L;
        return cooldownMillis <= 0L
                || lastRunTime == null
                || Duration.between(lastRunTime, now).toMillis() >= cooldownMillis;
    }

    private boolean shouldRunJailAction(
            JailActionConfig jailConfig,
            int blockedCount,
            boolean jailActionExecutedInCurrentWindow,
            Instant lastRunTime,
            Instant now
    ) {
        if (!jailConfig.enabled() || blockedCount < jailConfig.afterBlockedAttempts()) {
            return false;
        }

        if (jailConfig.executeOncePerWindow() && jailActionExecutedInCurrentWindow) {
            return false;
        }

        long cooldownMillis = jailConfig.cooldownMinutes() * 60_000L;
        return cooldownMillis <= 0L
                || lastRunTime == null
                || Duration.between(lastRunTime, now).toMillis() >= cooldownMillis;
    }

    private record ActionConfig(
            boolean enabled,
            int afterBlocks,
            int cooldownSeconds
    ) {
    }

    private record RuntimeConfig(
            int windowSeconds,
            long windowMillis,
            ActionConfig warning,
            ActionConfig staffNotify,
            ActionConfig cancelBreak,
            JailActionConfig jail
    ) {
    }

    private record JailActionConfig(
            boolean enabled,
            int afterBlockedAttempts,
            int cooldownMinutes,
            boolean executeOncePerWindow
    ) {
    }
}
