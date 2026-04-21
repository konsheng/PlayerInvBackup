package org.playerinvbackup.backup.restore;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 恢复流程编排器
 *
 * <p>这个类保留 restore 域对外公开入口, 负责把记录加载, 恢复前保护性备份, 物品恢复, 经验恢复
 * 通知和审计这些协作者按既定顺序组织起来
 * 底层读取, 应用和消息细节都委派给同包下的协作者类
 */
public final class RestoreService {
    private final PlayerInvBackupPlugin plugin;
    private final RestoreRecordLoader recordLoader;
    private final PreRestoreBackupGuard preRestoreBackupGuard;
    private final InventoryRestoreApplier inventoryRestoreApplier;
    private final ExperienceRestoreApplier experienceRestoreApplier;
    private final RestoreNotifier notifier;

    public RestoreService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
        this.recordLoader = new RestoreRecordLoader(plugin);
        this.preRestoreBackupGuard = new PreRestoreBackupGuard(plugin);
        this.inventoryRestoreApplier = new InventoryRestoreApplier();
        this.experienceRestoreApplier = new ExperienceRestoreApplier();
        this.notifier = new RestoreNotifier(plugin);
    }

    public void restoreToPlayer(CommandSender actor, Player target, String backupId) {
        if (!plugin.isStoreReady()) {
            runOnActor(actor, () -> notifier.showStoreUnavailable(actor));
            return;
        }

        RestoreRequest request = RestoreRequest.of(actor, target, backupId);
        runOnActor(actor, () -> notifier.showRestoreLoading(actor));

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            RestoreLoadResult loadResult = recordLoader.loadInventoryRestore(request);
            if (!handleLoadFailure(request, loadResult)) {
                return;
            }

            runOnPlayer(target, () -> beginInventoryRestore(request, target, loadResult.parts(), loadResult.claims()));
        });
    }

    public void restoreExperienceToPlayer(CommandSender actor, Player target, String backupId) {
        if (!plugin.isStoreReady()) {
            runOnActor(actor, () -> notifier.showStoreUnavailable(actor));
            return;
        }

        RestoreRequest request = RestoreRequest.of(actor, target, backupId);
        runOnActor(actor, () -> notifier.showExperienceRestoreLoading(actor));

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            RestoreLoadResult loadResult = recordLoader.loadExperienceRestore(request);
            if (!handleLoadFailure(request, loadResult)) {
                return;
            }

            runOnPlayer(target, () -> beginExperienceRestore(request, target, loadResult.parts()));
        });
    }

    private boolean handleLoadFailure(RestoreRequest request, RestoreLoadResult loadResult) {
        if (loadResult.isSuccess()) {
            return true;
        }

        switch (loadResult.failure()) {
            case BACKUP_NOT_FOUND -> runOnActor(
                    request.actor(),
                    () -> notifier.showBackupNotFound(request.actor(), request.backupId())
            );
            case READ_FAILED -> runOnActor(request.actor(), () -> notifier.showReadFailed(request.actor()));
            case SNAPSHOT_HASH_MISMATCH -> runOnActor(
                    request.actor(),
                    () -> notifier.showSnapshotHashMismatch(
                            request.actor(),
                            request.backupId(),
                            loadResult.expectedSha256(),
                            loadResult.actualSha256()
                    )
            );
            case SNAPSHOT_INVALID -> runOnActor(request.actor(), () -> notifier.showSnapshotInvalid(request.actor()));
            case EXPERIENCE_UNAVAILABLE -> runOnActor(
                    request.actor(),
                    () -> notifier.showBackupExperienceUnavailable(request.actor())
            );
        }
        return false;
    }

    private void beginInventoryRestore(
            RestoreRequest request,
            Player target,
            SnapshotParts parts,
            List<SlotClaim> claims
    ) {
        if (!target.isOnline()) {
            runOnActor(request.actor(), () -> notifier.showTargetOffline(request.actor()));
            return;
        }

        preRestoreBackupGuard.request(target, result -> handleInventoryPreRestoreBackupResult(request, parts, claims, result));
    }

    private void beginExperienceRestore(RestoreRequest request, Player target, SnapshotParts parts) {
        if (!target.isOnline()) {
            runOnActor(request.actor(), () -> notifier.showTargetOffline(request.actor()));
            return;
        }

        preRestoreBackupGuard.request(target, result -> handleExperiencePreRestoreBackupResult(request, parts, result));
    }

    private void handleInventoryPreRestoreBackupResult(
            RestoreRequest request,
            SnapshotParts parts,
            List<SlotClaim> claims,
            PreRestoreBackupResult result
    ) {
        if (!result.isSuccess()) {
            handlePreRestoreBackupFailure(request, result, "errors.restore-pre-backup-failed");
            return;
        }

        plugin.getLogger().info(plugin.lang().plain(
                "console.restore.pre-backup-succeeded",
                Placeholder.unparsed("actor", request.actorDetails()),
                Placeholder.unparsed("target", request.targetName()),
                Placeholder.unparsed("target_uuid", request.targetUuid().toString()),
                Placeholder.unparsed("backup_id", request.backupId()),
                Placeholder.unparsed("pre_restore_backup_id", result.preRestoreBackupId())
        ));

        runOnOnlineTarget(request.targetUuid(), currentTarget -> {
            runOnActor(request.actor(), () -> {
                notifier.showPreRestoreBackupReady(request.actor(), result.preRestoreBackupId());
                notifier.showRestoreRunning(request.actor());
            });

            try {
                inventoryRestoreApplier.apply(currentTarget, parts, claims);
            } catch (Exception e) {
                logApplyFailed(request, e);
                runOnActor(request.actor(), () -> notifier.showPreRestoreBackupFailed(request.actor(), "errors.restore-failed"));
                return;
            }

            runOnActor(request.actor(), () -> notifier.showRestoreSuccess(request.actor()));
            notifier.showTargetRestoreNotice(currentTarget);
            plugin.auditService().log(
                    "RESTORE",
                    request.actor(),
                    request.targetUuid(),
                    request.targetName(),
                    request.backupId(),
                    "claimedSlots=" + claims.size() + ",preRestoreBackupId=" + result.preRestoreBackupId()
            );
        }, () -> runOnActor(request.actor(), () -> notifier.showTargetOffline(request.actor())));
    }

    private void handleExperiencePreRestoreBackupResult(
            RestoreRequest request,
            SnapshotParts parts,
            PreRestoreBackupResult result
    ) {
        if (!result.isSuccess()) {
            handlePreRestoreBackupFailure(request, result, "errors.restore-experience-pre-backup-failed");
            return;
        }

        plugin.getLogger().info(plugin.lang().plain(
                "console.restore.pre-backup-succeeded",
                Placeholder.unparsed("actor", request.actorDetails()),
                Placeholder.unparsed("target", request.targetName()),
                Placeholder.unparsed("target_uuid", request.targetUuid().toString()),
                Placeholder.unparsed("backup_id", request.backupId()),
                Placeholder.unparsed("pre_restore_backup_id", result.preRestoreBackupId())
        ));

        runOnOnlineTarget(request.targetUuid(), currentTarget -> {
            runOnActor(request.actor(), () -> {
                notifier.showPreRestoreBackupReady(request.actor(), result.preRestoreBackupId());
                notifier.showExperienceRestoreRunning(request.actor());
            });

            try {
                experienceRestoreApplier.apply(currentTarget, parts);
            } catch (Exception e) {
                logApplyFailed(request, e);
                runOnActor(request.actor(), () -> notifier.showPreRestoreBackupFailed(request.actor(), "errors.restore-failed"));
                return;
            }

            runOnActor(request.actor(), () -> notifier.showExperienceRestoreSuccess(request.actor()));
            notifier.showTargetExperienceRestoreNotice(currentTarget);
            plugin.auditService().log(
                    "RESTORE_EXPERIENCE",
                    request.actor(),
                    request.targetUuid(),
                    request.targetName(),
                    request.backupId(),
                    "level=" + parts.experienceLevel()
                            + ",progress=" + parts.experienceProgress()
                            + ",totalExperience=" + parts.totalExperience()
                            + ",preRestoreBackupId=" + result.preRestoreBackupId()
            );
        }, () -> runOnActor(request.actor(), () -> notifier.showTargetOffline(request.actor())));
    }

    private void handlePreRestoreBackupFailure(
            RestoreRequest request,
            PreRestoreBackupResult result,
            String errorKey
    ) {
        switch (result.failure()) {
            case STORE_UNAVAILABLE -> runOnActor(request.actor(), () -> notifier.showStoreUnavailable(request.actor()));
            case TARGET_OFFLINE -> runOnActor(request.actor(), () -> notifier.showTargetOffline(request.actor()));
            case QUEUE_FULL, BACKUP_TASK_FAILED, REQUEST_THREW -> {
                logPreRestoreBackupFailure(request, result);
                runOnActor(request.actor(), () -> notifier.showPreRestoreBackupFailed(request.actor(), errorKey));
            }
        }
    }

    private void logPreRestoreBackupFailure(RestoreRequest request, PreRestoreBackupResult result) {
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
                        Placeholder.unparsed("actor", request.actorDetails()),
                        Placeholder.unparsed("target", request.targetName()),
                        Placeholder.unparsed("target_uuid", request.targetUuid().toString()),
                        Placeholder.unparsed("backup_id", request.backupId()),
                        Placeholder.unparsed("pre_restore_backup_id", result.logBackupId()),
                        Placeholder.unparsed("reason", reason)
                ),
                result.failure() == PreRestoreBackupResult.Failure.REQUEST_THREW ? result.cause() : null
        );
    }

    private void logApplyFailed(RestoreRequest request, Exception e) {
        plugin.getLogger().log(
                Level.SEVERE,
                plugin.lang().plain(
                        "console.restore.apply-failed",
                        Placeholder.unparsed("actor", request.actorDetails()),
                        Placeholder.unparsed("target", request.targetName()),
                        Placeholder.unparsed("target_uuid", request.targetUuid().toString()),
                        Placeholder.unparsed("backup_id", request.backupId())
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
        if (player == null || !player.isOnline()) {
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
}
