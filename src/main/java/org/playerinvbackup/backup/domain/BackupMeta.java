package org.playerinvbackup.backup.domain;

import java.util.UUID;

/**
 * 备份元数据
 *
 * <p>用于列表展示与快速查询, snapshot 本体由 {@link org.playerinvbackup.backup.domain.BackupRecord} 持有
 */
public record BackupMeta(
        String backupId,
        UUID playerUuid,
        long createdAtMillis,
        TriggerType trigger,
        int schemaVersion,
        String sha256Hex,
        int snapshotSizeBytes,
        boolean locked,
        String note
) {
}
