package org.playerinvbackup.backup.restore;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 恢复流程编排器
 *
 * <p>这个类只保留 restore 域的对外入口, 主流程编排, 线程切换, 最终消息通知和审计日志
 * 读取, 校验, 恢复前自动备份, 物品恢复, 经验恢复这些实现细节都委派给同包下的协作者
 */
public final class RestoreService {
    private final PlayerInvBackupPlugin plugin;
    private final RestoreRecordLoader recordLoader;
    private final PreRestoreBackupGuard preRestoreBackupGuard;
    private final InventoryRestoreApplier inventoryRestoreApplier;
    private final ExperienceRestoreApplier experienceRestoreApplier;

    public RestoreService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
        this.recordLoader = new RestoreRecordLoader(plugin);
        this.preRestoreBackupGuard = new PreRestoreBackupGuard(plugin);
        this.inventoryRestoreApplier = new InventoryRestoreApplier();
        this.experienceRestoreApplier = new ExperienceRestoreApplier();
    }

    public void restoreToPlayer(CommandSender actor, Player target, String backupId) {
        if (!plugin.isStoreReady()) {
            runOnActor(actor, () -> Chat.error(actor, "errors.store-unavailable", Placeholder.unparsed("label", "pib")));
            return;
        }

        String actorDetails = actorDetails(actor);
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();

        runOnActor(actor, () -> Chat.info(actor, "info.restoring-loading"));

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            RestoreLoadResult loadResult = recordLoader.loadInventoryRestore(actorDetails, targetUuid, targetName, backupId);
            if (!handleLoadFailure(actor, backupId, loadResult)) {
                return;
            }

            runOnPlayer(
                    target,
                    () -> beginInventoryRestore(
                            actor,
                            actorDetails,
                            target,
                            targetUuid,
                            targetName,
                            backupId,
                            loadResult.parts(),
                            loadResult.claims()
                    ),
                    () -> runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"))
            );
        });
    }

    public void restoreExperienceToPlayer(CommandSender actor, Player target, String backupId) {
        if (!plugin.isStoreReady()) {
            runOnActor(actor, () -> Chat.error(actor, "errors.store-unavailable", Placeholder.unparsed("label", "pib")));
            return;
        }

        String actorDetails = actorDetails(actor);
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();

        runOnActor(actor, () -> Chat.info(actor, "info.restoring-experience-loading"));

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            RestoreLoadResult loadResult = recordLoader.loadExperienceRestore(actorDetails, targetUuid, targetName, backupId);
            if (!handleLoadFailure(actor, backupId, loadResult)) {
                return;
            }

            runOnPlayer(
                    target,
                    () -> beginExperienceRestore(
                            actor,
                            actorDetails,
                            target,
                            targetUuid,
                            targetName,
                            backupId,
                            loadResult.parts()
                    ),
                    () -> runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"))
            );
        });
    }

    private boolean handleLoadFailure(CommandSender actor, String backupId, RestoreLoadResult loadResult) {
        if (loadResult.isSuccess()) {
            return true;
        }

        switch (loadResult.failure()) {
            case BACKUP_NOT_FOUND -> runOnActor(
                    actor,
                    () -> Chat.error(actor, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId))
            );
            case READ_FAILED -> runOnActor(actor, () -> Chat.error(actor, "errors.read-failed"));
            case SNAPSHOT_HASH_MISMATCH -> runOnActor(
                    actor,
                    () -> Chat.error(
                            actor,
                            "errors.snapshot-hash-mismatch",
                            Placeholder.unparsed("backup_id", backupId),
                            Placeholder.unparsed("expected", loadResult.expectedSha256()),
                            Placeholder.unparsed("actual", loadResult.actualSha256())
                    )
            );
            case SNAPSHOT_INVALID -> runOnActor(actor, () -> Chat.error(actor, "errors.snapshot-invalid"));
            case EXPERIENCE_UNAVAILABLE -> runOnActor(actor, () -> Chat.error(actor, "errors.backup-experience-unavailable"));
        }
        return false;
    }

    private void beginInventoryRestore(
            CommandSender actor,
            String actorDetails,
            Player target,
            UUID targetUuid,
            String targetName,
            String backupId,
            SnapshotParts parts,
            List<SlotClaim> claims
    ) {
        if (!target.isOnline()) {
            runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"));
            return;
        }

        preRestoreBackupGuard.request(
                target,
                result -> handleInventoryPreRestoreBackupResult(
                        actor,
                        actorDetails,
                        targetUuid,
                        targetName,
                        backupId,
                        parts,
                        claims,
                        result
                )
        );
    }

    private void beginExperienceRestore(
            CommandSender actor,
            String actorDetails,
            Player target,
            UUID targetUuid,
            String targetName,
            String backupId,
            SnapshotParts parts
    ) {
        if (!target.isOnline()) {
            runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"));
            return;
        }

        preRestoreBackupGuard.request(
                target,
                result -> handleExperiencePreRestoreBackupResult(
                        actor,
                        actorDetails,
                        targetUuid,
                        targetName,
                        backupId,
                        parts,
                        result
                )
        );
    }

    private void handleInventoryPreRestoreBackupResult(
            CommandSender actor,
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId,
            SnapshotParts parts,
            List<SlotClaim> claims,
            PreRestoreBackupResult result
    ) {
        if (!result.isSuccess()) {
            handlePreRestoreBackupFailure(
                    actor,
                    actorDetails,
                    targetUuid,
                    targetName,
                    backupId,
                    result,
                    "errors.restore-pre-backup-failed"
            );
            return;
        }

        String preRestoreBackupId = result.preRestoreBackupId();
        plugin.getLogger().info(plugin.lang().plain(
                "console.restore.pre-backup-succeeded",
                Placeholder.unparsed("actor", actorDetails),
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                Placeholder.unparsed("backup_id", backupId),
                Placeholder.unparsed("pre_restore_backup_id", preRestoreBackupId)
        ));

        runOnOnlineTarget(targetUuid, currentTarget -> {
            runOnActor(actor, () -> {
                Chat.info(
                        actor,
                        "info.restore-pre-backup-success",
                        Placeholder.component("backup_id", createCopyableBackupId(preRestoreBackupId))
                );
                Chat.info(actor, "info.restore-running");
            });

            try {
                inventoryRestoreApplier.apply(currentTarget, parts, claims);
            } catch (Exception e) {
                logApplyFailed(actorDetails, targetName, targetUuid, backupId, e);
                runOnActor(actor, () -> Chat.error(actor, "errors.restore-failed"));
                return;
            }

            runOnActor(actor, () -> Chat.success(actor, "success.restore-success"));
            Chat.warn(currentTarget, "warn.restored-notify-target");
            plugin.auditService().log(
                    "RESTORE",
                    actor,
                    targetUuid,
                    targetName,
                    backupId,
                    "claimedSlots=" + claims.size() + ",preRestoreBackupId=" + preRestoreBackupId
            );
        }, () -> runOnActor(actor, () -> Chat.error(actor, "errors.target-offline")));
    }

    private void handleExperiencePreRestoreBackupResult(
            CommandSender actor,
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId,
            SnapshotParts parts,
            PreRestoreBackupResult result
    ) {
        if (!result.isSuccess()) {
            handlePreRestoreBackupFailure(
                    actor,
                    actorDetails,
                    targetUuid,
                    targetName,
                    backupId,
                    result,
                    "errors.restore-experience-pre-backup-failed"
            );
            return;
        }

        String preRestoreBackupId = result.preRestoreBackupId();
        plugin.getLogger().info(plugin.lang().plain(
                "console.restore.pre-backup-succeeded",
                Placeholder.unparsed("actor", actorDetails),
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                Placeholder.unparsed("backup_id", backupId),
                Placeholder.unparsed("pre_restore_backup_id", preRestoreBackupId)
        ));

        runOnOnlineTarget(targetUuid, currentTarget -> {
            runOnActor(actor, () -> {
                Chat.info(
                        actor,
                        "info.restore-pre-backup-success",
                        Placeholder.component("backup_id", createCopyableBackupId(preRestoreBackupId))
                );
                Chat.info(actor, "info.restore-experience-running");
            });

            try {
                experienceRestoreApplier.apply(currentTarget, parts);
            } catch (Exception e) {
                logApplyFailed(actorDetails, targetName, targetUuid, backupId, e);
                runOnActor(actor, () -> Chat.error(actor, "errors.restore-failed"));
                return;
            }

            runOnActor(actor, () -> Chat.success(actor, "success.restore-experience-success"));
            Chat.warn(currentTarget, "warn.restored-experience-notify-target");
            plugin.auditService().log(
                    "RESTORE_EXPERIENCE",
                    actor,
                    targetUuid,
                    targetName,
                    backupId,
                    "level=" + parts.experienceLevel()
                            + ",progress=" + parts.experienceProgress()
                            + ",totalExperience=" + parts.totalExperience()
                            + ",preRestoreBackupId=" + preRestoreBackupId
            );
        }, () -> runOnActor(actor, () -> Chat.error(actor, "errors.target-offline")));
    }

    private void handlePreRestoreBackupFailure(
            CommandSender actor,
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId,
            PreRestoreBackupResult result,
            String errorKey
    ) {
        switch (result.failure()) {
            case STORE_UNAVAILABLE -> runOnActor(
                    actor,
                    () -> Chat.error(actor, "errors.store-unavailable", Placeholder.unparsed("label", "pib"))
            );
            case TARGET_OFFLINE -> runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"));
            case QUEUE_FULL, BACKUP_TASK_FAILED, REQUEST_THREW -> {
                String reason = switch (result.failure()) {
                    case QUEUE_FULL -> "queue_full";
                    case BACKUP_TASK_FAILED -> "backup_task_failed";
                    case REQUEST_THREW -> "request_threw:" + String.valueOf(result.cause() == null ? null : result.cause().getMessage());
                    case STORE_UNAVAILABLE -> "store_unavailable";
                    case TARGET_OFFLINE -> "target_offline";
                };

                plugin.getLogger().log(
                        Level.WARNING,
                        plugin.lang().plain(
                                "console.restore.pre-backup-failed",
                                Placeholder.unparsed("actor", actorDetails),
                                Placeholder.unparsed("target", targetName),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("backup_id", backupId),
                                Placeholder.unparsed("pre_restore_backup_id", result.logBackupId()),
                                Placeholder.unparsed("reason", reason)
                        ),
                        result.failure() == PreRestoreBackupResult.Failure.REQUEST_THREW ? result.cause() : null
                );
                runOnActor(actor, () -> Chat.error(actor, errorKey));
            }
        }
    }

    private void logApplyFailed(
            String actorDetails,
            String targetName,
            UUID targetUuid,
            String backupId,
            Exception e
    ) {
        plugin.getLogger().log(
                Level.SEVERE,
                plugin.lang().plain(
                        "console.restore.apply-failed",
                        Placeholder.unparsed("actor", actorDetails),
                        Placeholder.unparsed("target", targetName),
                        Placeholder.unparsed("target_uuid", targetUuid.toString()),
                        Placeholder.unparsed("backup_id", backupId)
                ),
                e
        );
    }

    private void runOnOnlineTarget(UUID targetUuid, Consumer<Player> consumer, Runnable ifOffline) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Player currentTarget = Bukkit.getPlayer(targetUuid);
            if (currentTarget == null || !currentTarget.isOnline()) {
                if (ifOffline != null) {
                    ifOffline.run();
                }
                return;
            }
            currentTarget.getScheduler().run(plugin, ignored -> consumer.accept(currentTarget), null);
        });
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        runOnPlayer(player, runnable, null);
    }

    private void runOnPlayer(Player player, Runnable runnable, Runnable ifOffline) {
        if (player == null || !player.isOnline()) {
            if (ifOffline != null) {
                ifOffline.run();
            }
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }

    private void runOnActor(CommandSender actor, Runnable runnable) {
        if (actor == null) {
            return;
        }
        if (actor instanceof Player player) {
            runOnPlayer(player, runnable);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    private Component createCopyableBackupId(String backupId) {
        return Component.text(backupId)
                .clickEvent(ClickEvent.copyToClipboard(backupId))
                .hoverEvent(HoverEvent.showText(plugin.lang().msg("success.restore-pre-backup-copy-hover")));
    }

    private String actorDetails(CommandSender actor) {
        if (actor == null) {
            return "-";
        }
        return actor instanceof Player player
                ? actor.getName() + "(" + player.getUniqueId() + ")"
                : actor.getName();
    }
}
