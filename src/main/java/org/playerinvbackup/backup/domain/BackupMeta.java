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
        String note,
        String worldName,
        Double locationX,
        Double locationY,
        Double locationZ,
        String targetWorldName,
        Double targetLocationX,
        Double targetLocationY,
        Double targetLocationZ
) {
    public BackupMeta(
            String backupId,
            UUID playerUuid,
            long createdAtMillis,
            TriggerType trigger,
            int schemaVersion,
            String sha256Hex,
            int snapshotSizeBytes,
            boolean locked,
            String note,
            String worldName,
            Double locationX,
            Double locationY,
            Double locationZ
    ) {
        this(
                backupId,
                playerUuid,
                createdAtMillis,
                trigger,
                schemaVersion,
                sha256Hex,
                snapshotSizeBytes,
                locked,
                note,
                worldName,
                locationX,
                locationY,
                locationZ,
                null,
                null,
                null,
                null
        );
    }

    public BackupMeta(
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
        this(
                backupId,
                playerUuid,
                createdAtMillis,
                trigger,
                schemaVersion,
                sha256Hex,
                snapshotSizeBytes,
                locked,
                note,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public boolean hasLocation() {
        return worldName != null
                && !worldName.isBlank()
                && locationX != null
                && locationY != null
                && locationZ != null;
    }

    public boolean hasTargetLocation() {
        return targetWorldName != null
                && !targetWorldName.isBlank()
                && targetLocationX != null
                && targetLocationY != null
                && targetLocationZ != null;
    }
}
