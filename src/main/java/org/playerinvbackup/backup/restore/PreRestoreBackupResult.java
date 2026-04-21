package org.playerinvbackup.backup.restore;

/**
 * 恢复前保护性备份结果
 *
 * <p>把成功的 preRestoreBackupId 和各种失败原因显式化
 * 让编排层可以继续保持原有消息和日志语义, 但不再直接处理 requestBackup 的细节分支
 */
record PreRestoreBackupResult(
        String preRestoreBackupId,
        Failure failure,
        Throwable cause
) {
    enum Failure {
        STORE_UNAVAILABLE,
        TARGET_OFFLINE,
        QUEUE_FULL,
        BACKUP_TASK_FAILED,
        REQUEST_THREW
    }

    static PreRestoreBackupResult success(String preRestoreBackupId) {
        return new PreRestoreBackupResult(preRestoreBackupId, null, null);
    }

    static PreRestoreBackupResult failure(Failure failure) {
        return new PreRestoreBackupResult("-", failure, null);
    }

    static PreRestoreBackupResult backupTaskFailed(String preRestoreBackupId) {
        return new PreRestoreBackupResult(preRestoreBackupId, Failure.BACKUP_TASK_FAILED, null);
    }

    static PreRestoreBackupResult requestThrew(Throwable cause) {
        return new PreRestoreBackupResult("-", Failure.REQUEST_THREW, cause);
    }

    boolean isSuccess() {
        return failure == null;
    }

    String logBackupId() {
        return preRestoreBackupId == null || preRestoreBackupId.isBlank() ? "-" : preRestoreBackupId;
    }
}
