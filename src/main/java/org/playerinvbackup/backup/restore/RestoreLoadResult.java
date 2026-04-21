package org.playerinvbackup.backup.restore;

import java.util.List;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SnapshotParts;

/**
 * 恢复记录加载结果
 *
 * <p>把备份记录, 领取记录, 快照解码结果和失败原因统一收口
 * 让编排层只根据结果决定后续流程, 不再直接处理底层读取细节
 */
record RestoreLoadResult(
        BackupRecord record,
        SnapshotParts parts,
        List<SlotClaim> claims,
        Failure failure,
        String expectedSha256,
        String actualSha256
) {
    enum Failure {
        BACKUP_NOT_FOUND,
        READ_FAILED,
        SNAPSHOT_HASH_MISMATCH,
        SNAPSHOT_INVALID,
        EXPERIENCE_UNAVAILABLE
    }

    static RestoreLoadResult success(BackupRecord record, SnapshotParts parts, List<SlotClaim> claims) {
        return new RestoreLoadResult(record, parts, claims, null, null, null);
    }

    static RestoreLoadResult failure(Failure failure) {
        return new RestoreLoadResult(null, null, List.of(), failure, null, null);
    }

    static RestoreLoadResult hashMismatch(String expectedSha256, String actualSha256) {
        return new RestoreLoadResult(
                null,
                null,
                List.of(),
                Failure.SNAPSHOT_HASH_MISMATCH,
                expectedSha256,
                actualSha256
        );
    }

    boolean isSuccess() {
        return failure == null;
    }
}
