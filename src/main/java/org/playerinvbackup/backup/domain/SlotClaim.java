package org.playerinvbackup.backup.domain;

import java.util.UUID;

/**
 * 领取记录
 *
 * <p>用于标记某个备份中的某个槽位已被管理员领取(整组领取), 以便恢复时扣除已领取格子
 */
public record SlotClaim(
        String backupId,
        SlotType slotType,
        int slotIndex,
        UUID actorUuid,
        long claimedAtMillis
) {
}
