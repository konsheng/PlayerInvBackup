package org.playerinvbackup.backup.command.handler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandAsync;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.text.Chat;

/**
 * 备份动作命令处理器
 *
 * <p>负责 backup, backupall
 */
public final class BackupActionHandler implements SubcommandHandler {
    private static final long BACKUP_ALL_RETRY_DELAY_TICKS = 20L;

    private final PlayerInvBackupPlugin plugin;
    private final CommandGuards guards;
    private final CommandAsync async;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;
    private final Map<UUID, Long> selfBackupCooldownUntilMillis = new ConcurrentHashMap<>();
    private final Object backupAllLock = new Object();
    private BackupAllQueue runningBackupAllQueue;

    public BackupActionHandler(
            PlayerInvBackupPlugin plugin,
            CommandGuards guards,
            CommandAsync async,
            TargetResolver targetResolver,
            CommandSuggestions suggestions
    ) {
        this.plugin = plugin;
        this.guards = guards;
        this.async = async;
        this.targetResolver = targetResolver;
        this.suggestions = suggestions;
    }

    @Override
    public String name() {
        return "backup";
    }

    @Override
    public List<String> aliases() {
        return List.of("backupall");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(java.util.Locale.ROOT)) {
            case "backup" -> executeBackup(ctx);
            case "backupall" -> executeBackupAll(ctx);
            default -> false;
        };
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if ("backup".equalsIgnoreCase(ctx.subcommand())
                && ctx.argCount() == 1
                && Permissions.has(ctx.sender(), Permissions.BACKUP)) {
            return suggestions.onlinePlayers(ctx.arg(0));
        }
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        return switch (token.toLowerCase(java.util.Locale.ROOT)) {
            case "backup" -> canUseBackupCommand(ctx.sender());
            case "backupall" -> Permissions.has(ctx.sender(), Permissions.BACKUP_ALL);
            default -> false;
        };
    }

    private boolean executeBackup(CommandContext ctx) {
        if (ctx.argCount() >= 1) {
            if (!guards.requirePermission(ctx, Permissions.BACKUP) || !guards.requireStoreReady(ctx)) {
                return true;
            }

            Player target = targetResolver.resolveOnlinePlayer(ctx.arg(0));
            if (target == null) {
                Chat.error(ctx.sender(), "errors.player-not-online");
                return true;
            }

            queueManualBackup(ctx.sender(), ctx.label(), target, "MANUAL_BACKUP");
            return true;
        }

        if (!guards.requirePermission(ctx, Permissions.SELF_BACKUP)) {
            return true;
        }

        Player player = ctx.senderAsPlayer();
        if (player == null) {
            Chat.error(ctx.sender(), "errors.usage-backup", Placeholder.unparsed("label", ctx.label()));
            return true;
        }
        if (!guards.requireStoreReady(ctx)) {
            return true;
        }

        queueSelfManualBackup(ctx.sender(), ctx.label(), player);
        return true;
    }

    private boolean executeBackupAll(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.BACKUP_ALL) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (ctx.argCount() >= 1) {
            Chat.error(ctx.sender(), "errors.usage-backupall", Placeholder.unparsed("label", ctx.label()));
            return true;
        }

        queueManualBackupAll(ctx.sender(), ctx.label());
        return true;
    }

    private void queueManualBackup(CommandSender sender, String label, Player target, String auditAction) {
        target.getScheduler().run(plugin, ignored -> {
            BackupService backupService = plugin.backupService();
            if (!plugin.isStoreReady() || backupService == null) {
                async.runOnSender(sender, () -> Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label)));
                return;
            }

            boolean queued = backupService.requestBackup(target, TriggerType.MANUAL);
            if (queued) {
                async.runOnSender(sender, () -> Chat.success(sender, "success.backup-queued", Placeholder.unparsed("player", target.getName())));
                plugin.auditService().log(auditAction, sender, target.getUniqueId(), target.getName(), null, "queued=true");
            } else {
                async.runOnSender(sender, () -> Chat.error(sender, "errors.backup-queue-full"));
                plugin.auditService().log(auditAction, sender, target.getUniqueId(), target.getName(), null, "queued=false");
            }
        }, null);
    }

    private void queueSelfManualBackup(CommandSender sender, String label, Player player) {
        player.getScheduler().run(plugin, ignored -> {
            BackupService backupService = plugin.backupService();
            if (!plugin.isStoreReady() || backupService == null) {
                async.runOnSender(sender, () -> Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label)));
                return;
            }

            long now = System.currentTimeMillis();
            boolean bypassCooldown = Permissions.has(sender, Permissions.SELF_BACKUP_BYPASS);
            if (!bypassCooldown) {
                long remainingMillis = remainingSelfBackupCooldown(player.getUniqueId(), now);
                if (remainingMillis > 0L) {
                    long remainingSeconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
                    async.runOnSender(sender, () -> Chat.error(
                            sender,
                            "errors.backup-cooldown",
                            Placeholder.unparsed("seconds", String.valueOf(remainingSeconds))
                    ));
                    return;
                }
            }

            boolean queued = backupService.requestBackup(player, TriggerType.MANUAL, (success, backupId) -> {
                if (!success) {
                    return;
                }
                async.runOnSender(sender, () -> sender.sendMessage(plugin.lang().msg(
                        "success.self-backup-finished",
                        Placeholder.component("backup_id", createCopyableBackupId(backupId))
                )));
            });

            if (queued) {
                if (bypassCooldown) {
                    selfBackupCooldownUntilMillis.remove(player.getUniqueId());
                } else {
                    long cooldownMillis = plugin.pluginConfig() == null
                            ? 0L
                            : Math.max(0L, plugin.pluginConfig().manualSelfBackupCooldown().toMillis());
                    if (cooldownMillis > 0L) {
                        selfBackupCooldownUntilMillis.put(player.getUniqueId(), now + cooldownMillis);
                    } else {
                        selfBackupCooldownUntilMillis.remove(player.getUniqueId());
                    }
                }

                async.runOnSender(sender, () -> Chat.success(sender, "success.self-backup-queued"));
                plugin.auditService().log("SELF_BACKUP", sender, player.getUniqueId(), player.getName(), null, "queued=true");
            } else {
                async.runOnSender(sender, () -> Chat.error(sender, "errors.backup-queue-full"));
                plugin.auditService().log("SELF_BACKUP", sender, player.getUniqueId(), player.getName(), null, "queued=false");
            }
        }, null);
    }

    private void queueManualBackupAll(CommandSender sender, String label) {
        List<BackupAllTarget> targets = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            targets.add(new BackupAllTarget(player.getUniqueId(), player.getName()));
        }
        if (targets.isEmpty()) {
            Chat.error(sender, "errors.no-online-players");
            return;
        }

        BackupService backupService = plugin.backupService();
        if (!plugin.isStoreReady() || backupService == null) {
            Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label));
            return;
        }

        synchronized (backupAllLock) {
            if (runningBackupAllQueue != null) {
                Chat.error(sender, "errors.backupall-running");
                return;
            }
            runningBackupAllQueue = new BackupAllQueue(sender, targets);
        }

        Chat.success(
                sender,
                "success.backupall-enqueued",
                Placeholder.unparsed("total", String.valueOf(targets.size()))
        );

        runningBackupAllQueue.start();
    }

    private void onBackupAllQueueFinished(BackupAllQueue queue) {
        synchronized (backupAllLock) {
            if (runningBackupAllQueue == queue) {
                runningBackupAllQueue = null;
            }
        }
    }

    public int cancelActiveBackupAllForReload() {
        BackupAllQueue queueToCancel;
        synchronized (backupAllLock) {
            queueToCancel = runningBackupAllQueue;
        }
        if (queueToCancel != null) {
            return queueToCancel.cancelForReload();
        }
        return 0;
    }

    private boolean canUseBackupCommand(CommandSender sender) {
        return Permissions.has(sender, Permissions.BACKUP) || Permissions.has(sender, Permissions.SELF_BACKUP);
    }

    private long remainingSelfBackupCooldown(UUID playerUuid, long now) {
        long until = selfBackupCooldownUntilMillis.getOrDefault(playerUuid, 0L);
        if (until <= now) {
            selfBackupCooldownUntilMillis.remove(playerUuid, until);
            return 0L;
        }
        return until - now;
    }

    private Component createCopyableBackupId(String backupId) {
        return Component.text(backupId)
                .clickEvent(ClickEvent.copyToClipboard(backupId))
                .hoverEvent(HoverEvent.showText(plugin.lang().msg("success.self-backup-copy-hover")));
    }

    private static String formatElapsed(long elapsedMillis) {
        long safeMillis = Math.max(0L, elapsedMillis);
        long totalSeconds = safeMillis / 1000L;
        long millisPart = safeMillis % 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format("%dm %ds", minutes, seconds);
        }
        if (seconds > 0L) {
            return String.format("%d.%03ds", seconds, millisPart);
        }
        return safeMillis + "ms";
    }

    private final class BackupAllQueue {
        private final CommandSender sender;
        private final Deque<BackupAllTarget> pendingTargets;
        private final int totalTargets;
        private final long startedAtMillis;

        private int succeeded;
        private int skipped;
        private int failed;
        private int cancelled;
        private int inFlight;
        private boolean finished;
        private boolean cancelledForReload;
        private boolean silentFinish;
        private boolean pumpScheduled;
        private boolean progressScheduled;

        private BackupAllQueue(CommandSender sender, List<BackupAllTarget> targets) {
            this.sender = sender;
            this.pendingTargets = new ArrayDeque<>(targets);
            this.totalTargets = targets.size();
            this.startedAtMillis = System.currentTimeMillis();
        }

        private void start() {
            schedulePump(0L);
            scheduleProgressReminder();
        }

        private void schedulePump(long delayTicks) {
            synchronized (this) {
                if (finished || cancelledForReload || pumpScheduled) {
                    return;
                }
                pumpScheduled = true;
            }

            if (delayTicks <= 0L) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, this::runPump);
                return;
            }
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> runPump(), delayTicks);
        }

        private void runPump() {
            synchronized (this) {
                pumpScheduled = false;
                if (finished) {
                    return;
                }
            }
            pump();
        }

        private void scheduleProgressReminder() {
            long intervalTicks = progressIntervalTicks();
            if (intervalTicks <= 0L) {
                return;
            }

            synchronized (this) {
                if (finished || progressScheduled) {
                    return;
                }
                progressScheduled = true;
            }

            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> runProgressReminder(), intervalTicks);
        }

        private void runProgressReminder() {
            int completed;
            int successCount;
            int skippedCount;
            int failedCount;
            int remainingCount;
            synchronized (this) {
                progressScheduled = false;
                if (finished || cancelledForReload) {
                    return;
                }
                successCount = succeeded;
                skippedCount = skipped;
                failedCount = failed;
                completed = successCount + skippedCount + failedCount;
                remainingCount = Math.max(0, totalTargets - completed);
            }

            async.runOnSender(sender, () -> Chat.info(
                    sender,
                    "info.backupall-progress",
                    Placeholder.unparsed("completed", String.valueOf(completed)),
                    Placeholder.unparsed("total", String.valueOf(totalTargets)),
                    Placeholder.unparsed("success", String.valueOf(successCount)),
                    Placeholder.unparsed("skipped", String.valueOf(skippedCount)),
                    Placeholder.unparsed("failed", String.valueOf(failedCount)),
                    Placeholder.unparsed("remaining", String.valueOf(remainingCount))
            ));
            scheduleProgressReminder();
        }

        private void pump() {
            synchronized (this) {
                if (cancelledForReload) {
                    finishIfComplete();
                    return;
                }
            }
            BackupService backupService = plugin.backupService();
            if (!plugin.isStoreReady() || backupService == null) {
                failRemainingPending("store-unavailable");
                return;
            }

            int permits = plugin.ioDispatcher().queueRemainingCapacity();
            if (permits <= 0) {
                schedulePump(BACKUP_ALL_RETRY_DELAY_TICKS);
                return;
            }

            List<BackupAllTarget> batch = new ArrayList<>();
            synchronized (this) {
                while (!finished && permits > 0 && !pendingTargets.isEmpty()) {
                    batch.add(pendingTargets.pollFirst());
                    permits--;
                }
            }

            if (batch.isEmpty()) {
                finishIfComplete();
                return;
            }

            for (BackupAllTarget target : batch) {
                dispatchTarget(target);
            }

            synchronized (this) {
                if (!finished && !pendingTargets.isEmpty()) {
                    schedulePump(BACKUP_ALL_RETRY_DELAY_TICKS);
                }
            }
            finishIfComplete();
        }

        private void dispatchTarget(BackupAllTarget target) {
            Player player = Bukkit.getPlayer(target.playerUuid());
            if (player == null || !player.isOnline()) {
                onTargetSkipped(target, "offline");
                return;
            }

            synchronized (this) {
                if (finished) {
                    failed++;
                    return;
                }
                inFlight++;
            }
            player.getScheduler().run(
                    plugin,
                    ignored -> attemptTargetBackup(player, target),
                    () -> onTargetSkipped(target, "retired")
            );
        }

        private void attemptTargetBackup(Player player, BackupAllTarget target) {
            try {
                synchronized (this) {
                    if (cancelledForReload) {
                        onTargetCancelled(target, "reload-cancelled");
                        return;
                    }
                }
                BackupService backupService = plugin.backupService();
                if (!player.isOnline()) {
                    onTargetSkipped(target, "offline");
                    return;
                }
                if (!plugin.isStoreReady() || backupService == null) {
                    onTargetFailed(target, "store-unavailable");
                    return;
                }

                boolean queued = backupService.requestBackup(player, TriggerType.MANUAL, (success, backupId) -> {
                    if (success) {
                        onTargetSucceeded(target, backupId);
                        return;
                    }
                    onTargetFailed(target, "save-failed");
                });
                if (queued) {
                    return;
                }

                synchronized (this) {
                    if (cancelledForReload) {
                        onTargetCancelled(target, "reload-cancelled");
                        return;
                    }
                }
                requeueTargetAfterQueueFull(target);
                schedulePump(BACKUP_ALL_RETRY_DELAY_TICKS);
            } catch (RuntimeException e) {
                onTargetFailed(target, "dispatch-exception");
                throw e;
            }
        }

        private void onTargetSucceeded(BackupAllTarget target, String backupId) {
            synchronized (this) {
                succeeded++;
                inFlight--;
            }
            plugin.auditService().log(
                    "MANUAL_BACKUP_ALL",
                    sender,
                    target.playerUuid(),
                    target.playerName(),
                    backupId,
                    "success=true"
            );
            finishIfComplete();
        }

        private void onTargetCancelled(BackupAllTarget target, String reason) {
            synchronized (this) {
                cancelled++;
                if (inFlight > 0) {
                    inFlight--;
                }
            }
            plugin.auditService().log(
                    "MANUAL_BACKUP_ALL",
                    sender,
                    target.playerUuid(),
                    target.playerName(),
                    null,
                    "cancelled=true, reason=" + reason
            );
            finishIfComplete();
        }

        private void onTargetSkipped(BackupAllTarget target, String reason) {
            synchronized (this) {
                skipped++;
                if (inFlight > 0) {
                    inFlight--;
                }
            }
            plugin.auditService().log(
                    "MANUAL_BACKUP_ALL",
                    sender,
                    target.playerUuid(),
                    target.playerName(),
                    null,
                    "skipped=true, reason=" + reason
            );
            finishIfComplete();
        }

        private void onTargetFailed(BackupAllTarget target, String reason) {
            synchronized (this) {
                failed++;
                if (inFlight > 0) {
                    inFlight--;
                }
            }
            plugin.auditService().log(
                    "MANUAL_BACKUP_ALL",
                    sender,
                    target.playerUuid(),
                    target.playerName(),
                    null,
                    "failed=true, reason=" + reason
            );
            finishIfComplete();
        }

        private void requeueTarget(BackupAllTarget target) {
            synchronized (this) {
                if (finished) {
                    failed++;
                    return;
                }
                pendingTargets.addFirst(target);
            }
        }

        private void requeueTargetAfterQueueFull(BackupAllTarget target) {
            synchronized (this) {
                if (inFlight > 0) {
                    inFlight--;
                }
            }
            requeueTarget(target);
        }

        private void failRemainingPending(String reason) {
            List<BackupAllTarget> remaining = new ArrayList<>();
            synchronized (this) {
                while (!pendingTargets.isEmpty()) {
                    remaining.add(pendingTargets.pollFirst());
                    failed++;
                }
            }
            for (BackupAllTarget target : remaining) {
                plugin.auditService().log(
                        "MANUAL_BACKUP_ALL",
                        sender,
                        target.playerUuid(),
                        target.playerName(),
                        null,
                        "failed=true, reason=" + reason
                );
            }
            finishIfComplete();
        }

        private int cancelForReload() {
            int dropped;
            synchronized (this) {
                if (finished || cancelledForReload) {
                    return 0;
                }
                cancelledForReload = true;
                silentFinish = true;
                dropped = pendingTargets.size();
                cancelled += dropped;
                pendingTargets.clear();
                pumpScheduled = false;
                progressScheduled = false;
            }
            finishIfComplete();
            return dropped;
        }

        private void finishIfComplete() {
            int successCount;
            int skippedCount;
            int failedCount;
            String elapsedText;
            boolean suppressMessage;
            synchronized (this) {
                if (finished || !pendingTargets.isEmpty() || inFlight > 0) {
                    return;
                }
                finished = true;
                successCount = succeeded;
                skippedCount = skipped;
                failedCount = failed;
                elapsedText = formatElapsed(System.currentTimeMillis() - startedAtMillis);
                suppressMessage = silentFinish;
            }

            if (!suppressMessage) {
                async.runOnSender(sender, () -> Chat.plainList(
                        sender,
                        "success.backupall-submitted",
                        Placeholder.unparsed("elapsed", elapsedText),
                        Placeholder.unparsed("success", String.valueOf(successCount)),
                        Placeholder.unparsed("skipped", String.valueOf(skippedCount)),
                        Placeholder.unparsed("failed", String.valueOf(failedCount))
                ));
            }
            onBackupAllQueueFinished(this);
        }

        private long progressIntervalTicks() {
            if (plugin.pluginConfig() == null || plugin.pluginConfig().backupAllProgressInterval() == null) {
                return 60L;
            }
            long seconds = plugin.pluginConfig().backupAllProgressInterval().toSeconds();
            if (seconds <= 0L) {
                return 0L;
            }
            return Math.max(1L, seconds * 20L);
        }
    }

    private record BackupAllTarget(UUID playerUuid, String playerName) {
    }
}
