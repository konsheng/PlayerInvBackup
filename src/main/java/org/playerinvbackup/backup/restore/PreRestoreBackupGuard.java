package org.playerinvbackup.backup.restore;

import java.util.function.Consumer;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.domain.TriggerType;
import org.bukkit.entity.Player;

/**
 * 封装恢复前的保护性备份流程
 *
 * <p>这里只有一个职责, 在真正恢复前先尝试给目标玩家做一次新的手动备份
 * 并把 queue full, store unavailable, target offline, callback failed 这些情况转成显式结果
 */
final class PreRestoreBackupGuard {
    private final PlayerInvBackupPlugin plugin;

    PreRestoreBackupGuard(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    void request(Player target, Consumer<PreRestoreBackupResult> completion) {
        if (target == null || !target.isOnline()) {
            completion.accept(PreRestoreBackupResult.failure(PreRestoreBackupResult.Failure.TARGET_OFFLINE));
            return;
        }

        BackupService backupService = plugin.backupService();
        if (backupService == null || !plugin.isStoreReady()) {
            completion.accept(PreRestoreBackupResult.failure(PreRestoreBackupResult.Failure.STORE_UNAVAILABLE));
            return;
        }

        final boolean queued;
        try {
            queued = backupService.requestBackup(target, TriggerType.MANUAL, (success, preRestoreBackupId) -> {
                if (!success) {
                    completion.accept(PreRestoreBackupResult.backupTaskFailed(preRestoreBackupId));
                    return;
                }
                completion.accept(PreRestoreBackupResult.success(preRestoreBackupId));
            });
        } catch (RuntimeException e) {
            completion.accept(PreRestoreBackupResult.requestThrew(e));
            return;
        }

        if (!queued) {
            completion.accept(PreRestoreBackupResult.failure(PreRestoreBackupResult.Failure.QUEUE_FULL));
        }
    }
}
