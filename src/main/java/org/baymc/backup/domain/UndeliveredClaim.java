package org.baymc.backup.domain;

import java.util.UUID;

/**
 * 待投递物品记录
 *
 * <p>当管理员领取整组物品时, 如果其背包满了, 物品会进入待投递队列
 * 玩家可通过 /pib pending 领取
 */
public record UndeliveredClaim(
        UUID playerUuid,
        String backupId,
        SlotType slotType,
        int slotIndex,
        UUID actorUuid,
        String actorName,
        long claimedAtMillis,
        byte[] itemBytes
) {
}
