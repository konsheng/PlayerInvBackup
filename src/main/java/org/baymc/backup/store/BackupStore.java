package org.baymc.backup.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SlotClaim;
import org.baymc.backup.domain.SlotType;
import org.baymc.backup.domain.UndeliveredClaim;

/**
 * 备份存储接口
 *
 * <p>用于屏蔽不同存储后端实现差异(local/sqlite), 并提供:
 * 备份读写, 列表查询, 领取记录与待投递队列等操作
 */
public interface BackupStore extends AutoCloseable {
    void init() throws Exception;

    void saveBackup(BackupRecord record) throws Exception;

    default List<BackupMeta> listBackups(UUID playerUuid, int offset, int limit) throws Exception {
        return listBackups(playerUuid, BackupQuery.all(), offset, limit);
    }

    List<BackupMeta> listBackups(UUID playerUuid, BackupQuery query, int offset, int limit) throws Exception;

    Optional<BackupRecord> loadBackup(UUID playerUuid, String backupId) throws Exception;

    List<SlotClaim> listClaims(UUID playerUuid, String backupId) throws Exception;

    boolean tryClaimSlot(
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            UUID actorUuid,
            String actorName,
            long claimedAtMillis,
            byte[] itemBytes
    ) throws Exception;

    List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) throws Exception;

    boolean markDelivered(
            UUID actorUuid,
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            long deliveredAtMillis
    ) throws Exception;

    boolean setBackupLocked(UUID playerUuid, String backupId, boolean locked) throws Exception;

    boolean setBackupNote(UUID playerUuid, String backupId, String note) throws Exception;

    void purgeBackups(UUID playerUuid, int keepPerPlayer, long keepAfterMillis) throws Exception;

    @Override
    void close() throws Exception;
}
