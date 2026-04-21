package org.playerinvbackup.backup.restore;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.util.Hashing;

/**
 * 负责恢复前的备份记录读取和快照校验
 *
 * <p>这里统一处理备份记录读取, 领取记录读取, sha256 校验和快照解码
 * RestoreService 只根据结果决定后续流程, 不再直接承担读取和校验细节
 */
final class RestoreRecordLoader {
    private final PlayerInvBackupPlugin plugin;

    RestoreRecordLoader(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    RestoreLoadResult loadInventoryRestore(
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId
    ) {
        BackupRecord record;
        List<SlotClaim> claims;
        try {
            record = plugin.store().loadBackup(targetUuid, backupId).orElse(null);
            if (record == null) {
                return RestoreLoadResult.failure(RestoreLoadResult.Failure.BACKUP_NOT_FOUND);
            }
            claims = plugin.store().listClaims(targetUuid, backupId);
        } catch (Exception e) {
            logReadFailed(actorDetails, targetUuid, targetName, backupId, e);
            return RestoreLoadResult.failure(RestoreLoadResult.Failure.READ_FAILED);
        }

        RestoreLoadResult validated = validateAndDecode(actorDetails, targetUuid, targetName, backupId, record);
        if (!validated.isSuccess()) {
            return validated;
        }
        return RestoreLoadResult.success(validated.parts(), claims);
    }

    RestoreLoadResult loadExperienceRestore(
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId
    ) {
        BackupRecord record;
        try {
            record = plugin.store().loadBackup(targetUuid, backupId).orElse(null);
            if (record == null) {
                return RestoreLoadResult.failure(RestoreLoadResult.Failure.BACKUP_NOT_FOUND);
            }
        } catch (Exception e) {
            logReadFailed(actorDetails, targetUuid, targetName, backupId, e);
            return RestoreLoadResult.failure(RestoreLoadResult.Failure.READ_FAILED);
        }

        RestoreLoadResult validated = validateAndDecode(actorDetails, targetUuid, targetName, backupId, record);
        if (!validated.isSuccess()) {
            return validated;
        }
        if (!validated.parts().hasExperienceData()) {
            return RestoreLoadResult.failure(RestoreLoadResult.Failure.EXPERIENCE_UNAVAILABLE);
        }
        return validated;
    }

    private RestoreLoadResult validateAndDecode(
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId,
            BackupRecord record
    ) {
        String expectedSha256 = record.meta().sha256Hex();
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actualSha256 = Hashing.sha256Hex(record.snapshotBytes());
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                plugin.getLogger().severe(plugin.lang().plain(
                        "console.restore.sha-mismatch",
                        Placeholder.unparsed("actor", actorDetails),
                        Placeholder.unparsed("target", targetName),
                        Placeholder.unparsed("target_uuid", targetUuid.toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("expected", expectedSha256),
                        Placeholder.unparsed("actual", actualSha256)
                ));
                return RestoreLoadResult.hashMismatch(expectedSha256, actualSha256);
            }
        }

        try {
            return RestoreLoadResult.success(SnapshotCodec.decodeGzip(record.snapshotBytes()), List.of());
        } catch (IOException e) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    plugin.lang().plain(
                            "console.restore.snapshot-invalid",
                            Placeholder.unparsed("actor", actorDetails),
                            Placeholder.unparsed("target", targetName),
                            Placeholder.unparsed("target_uuid", targetUuid.toString()),
                            Placeholder.unparsed("backup_id", backupId)
                    ),
                    e
            );
            return RestoreLoadResult.failure(RestoreLoadResult.Failure.SNAPSHOT_INVALID);
        }
    }

    private void logReadFailed(
            String actorDetails,
            UUID targetUuid,
            String targetName,
            String backupId,
            Exception e
    ) {
        plugin.getLogger().log(
                Level.SEVERE,
                plugin.lang().plain(
                        "console.restore.read-failed",
                        Placeholder.unparsed("actor", actorDetails),
                        Placeholder.unparsed("target", targetName),
                        Placeholder.unparsed("target_uuid", targetUuid.toString()),
                        Placeholder.unparsed("backup_id", backupId)
                ),
                e
        );
    }
}
