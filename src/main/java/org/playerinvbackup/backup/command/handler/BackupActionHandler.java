package org.playerinvbackup.backup.command.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final PlayerInvBackupPlugin plugin;
    private final CommandGuards guards;
    private final CommandAsync async;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;
    private final Map<UUID, Long> selfBackupCooldownUntilMillis = new ConcurrentHashMap<>();

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
                async.runOnSender(sender, () -> Chat.success(
                        sender,
                        "success.self-backup-finished",
                        Placeholder.unparsed("backup_id", backupId)
                ));
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

                async.runOnSender(sender, () -> Chat.success(sender, "success.backup-queued", Placeholder.unparsed("player", player.getName())));
                plugin.auditService().log("SELF_BACKUP", sender, player.getUniqueId(), player.getName(), null, "queued=true");
            } else {
                async.runOnSender(sender, () -> Chat.error(sender, "errors.backup-queue-full"));
                plugin.auditService().log("SELF_BACKUP", sender, player.getUniqueId(), player.getName(), null, "queued=false");
            }
        }, null);
    }

    private void queueManualBackupAll(CommandSender sender, String label) {
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) {
            Chat.error(sender, "errors.no-online-players");
            return;
        }

        BackupService backupService = plugin.backupService();
        if (!plugin.isStoreReady() || backupService == null) {
            Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label));
            return;
        }

        AtomicInteger queued = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(targets.size());

        for (Player target : targets) {
            target.getScheduler().run(
                    plugin,
                    ignored -> {
                        boolean queuedThisTime = target.isOnline()
                                && plugin.isStoreReady()
                                && plugin.backupService() != null
                                && plugin.backupService().requestBackup(target, TriggerType.MANUAL);
                        finishManualBackupAllOne(sender, target, queuedThisTime, queued, skipped, remaining);
                    },
                    () -> finishManualBackupAllOne(sender, target, false, queued, skipped, remaining)
            );
        }
    }

    private void finishManualBackupAllOne(
            CommandSender sender,
            Player target,
            boolean queuedThisTime,
            AtomicInteger queued,
            AtomicInteger skipped,
            AtomicInteger remaining
    ) {
        if (queuedThisTime) {
            queued.incrementAndGet();
        } else {
            skipped.incrementAndGet();
        }

        plugin.auditService().log(
                "MANUAL_BACKUP_ALL",
                sender,
                target.getUniqueId(),
                target.getName(),
                null,
                "queued=" + queuedThisTime
        );

        if (remaining.decrementAndGet() != 0) {
            return;
        }

        async.runOnSender(sender, () -> Chat.success(
                sender,
                "success.backupall-submitted",
                Placeholder.unparsed("queued", String.valueOf(queued.get())),
                Placeholder.unparsed("skipped", String.valueOf(skipped.get()))
        ));
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
}
