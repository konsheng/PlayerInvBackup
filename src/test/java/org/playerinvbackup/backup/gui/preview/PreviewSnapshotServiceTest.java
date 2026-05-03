package org.playerinvbackup.backup.gui.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.SnapshotParts;

/**
 * 该测试文件用于验证预览快照服务在不同领取模式下的已领取槽位表现
 * 覆盖无限拿模式忽略 claims 和一次性模式保留 claims 两种场景
 * 确保 GUI 层的 claimed 槽位状态会按照配置正确切换
 */
class PreviewSnapshotServiceTest {
    @Test
    // 验证无限拿模式下即使备份已有 claims 记录
    // 预览界面也不会把对应槽位标记为已领取
    void infiniteClaimModeIgnoresClaimRecordsForPreview() {
        PreviewSnapshotService service = new PreviewSnapshotService();
        SnapshotParts parts = emptySnapshot();
        List<SlotClaim> claims = List.of(
                new SlotClaim("b1", SlotType.INV, 2, UUID.randomUUID(), 1_000L),
                new SlotClaim("b1", SlotType.ENDER, 5, UUID.randomUUID(), 2_000L)
        );

        PreviewSnapshotData data = service.build(parts, claims, false, false);

        assertFalse(data.claimedInv()[2]);
        assertFalse(data.claimedEnder()[5]);
        assertTrue(data.claimRecordInv()[2]);
        assertTrue(data.claimRecordEnder()[5]);
    }

    @Test
    // 验证一次性领取模式下已有 claims 记录
    // 会继续用于标记预览界面的已领取槽位
    void claimOnceModeKeepsClaimedSlotMarkers() {
        PreviewSnapshotService service = new PreviewSnapshotService();
        SnapshotParts parts = emptySnapshot();
        List<SlotClaim> claims = List.of(
                new SlotClaim("b1", SlotType.INV, 2, UUID.randomUUID(), 1_000L),
                new SlotClaim("b1", SlotType.ENDER, 5, UUID.randomUUID(), 2_000L)
        );

        PreviewSnapshotData data = service.build(parts, claims, false, true);

        assertTrue(data.claimedInv()[2]);
        assertTrue(data.claimedEnder()[5]);
    }

    @Test
    void expandedEnderChestClaimMarkersUseSnapshotSlotCount() {
        PreviewSnapshotService service = new PreviewSnapshotService();
        SnapshotParts parts = emptySnapshot(54);
        List<SlotClaim> claims = List.of(
                new SlotClaim("b1", SlotType.ENDER, 53, UUID.randomUUID(), 2_000L)
        );

        PreviewSnapshotData data = service.build(parts, claims, false, true);

        assertEquals(54, data.claimedEnder().length);
        assertEquals(54, data.claimRecordEnder().length);
        assertTrue(data.claimedEnder()[53]);
        assertTrue(data.claimRecordEnder()[53]);
    }

    private static SnapshotParts emptySnapshot() {
        return emptySnapshot(SnapshotCodec.ENDER_CHEST_SLOT_COUNT);
    }

    private static SnapshotParts emptySnapshot(int enderSlotCount) {
        return new SnapshotParts(
                new byte[SnapshotCodec.INVENTORY_SLOT_COUNT][],
                new byte[enderSlotCount][],
                true,
                0,
                0.0f,
                0
        );
    }
}
