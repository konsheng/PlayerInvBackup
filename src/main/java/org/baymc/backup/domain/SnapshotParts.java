package org.baymc.backup.domain;

/**
 * 快照数据拆分结构
 *
 * <p>保存为槽位维度的二进制数组, 便于编解码与按槽位恢复/扣除已领取格子
 */
public record SnapshotParts(
        byte[][] inventorySlotBytes,
        byte[][] enderChestSlotBytes
) {
}
