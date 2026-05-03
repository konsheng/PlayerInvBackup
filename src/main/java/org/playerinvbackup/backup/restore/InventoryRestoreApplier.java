package org.playerinvbackup.backup.restore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 只负责物品类恢复
 *
 * <p>这里处理主背包, 盔甲, 副手, 末影箱的槽位过滤和最终应用
 * 不读取存储, 不做消息发送, 不关心恢复前备份
 */
final class InventoryRestoreApplier {
    void assertCompatible(SnapshotParts parts, List<SlotClaim> claims) {
        Set<String> claimedKeys = toClaimedKeys(claims);
        ensureCompatible(parts.inventorySlotBytes(), SlotType.INV, claimedKeys);
        ensureCompatible(parts.enderChestSlotBytes(), SlotType.ENDER, claimedKeys);
    }

    void apply(Player target, SnapshotParts parts, List<SlotClaim> claims) {
        Set<String> claimedKeys = toClaimedKeys(claims);

        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            if (claimedKeys.contains(SlotType.INV.name() + ":" + i)) {
                storage[i] = null;
                continue;
            }
            byte[] bytes = parts.inventorySlotBytes()[i];
            storage[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            int idx = 36 + i;
            if (claimedKeys.contains(SlotType.INV.name() + ":" + idx)) {
                armor[i] = null;
                continue;
            }
            byte[] bytes = parts.inventorySlotBytes()[idx];
            armor[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        ItemStack offhand;
        if (claimedKeys.contains(SlotType.INV.name() + ":" + 40)) {
            offhand = null;
        } else {
            byte[] bytes = parts.inventorySlotBytes()[40];
            offhand = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        int targetEnderSlotCount = targetEnderSlotCount(target);
        assertTargetEnderCapacity(parts, targetEnderSlotCount);
        ItemStack[] ender = new ItemStack[targetEnderSlotCount];
        for (int i = 0; i < targetEnderSlotCount; i++) {
            if (i >= parts.enderChestSlotBytes().length) {
                ender[i] = null;
                continue;
            }
            if (claimedKeys.contains(SlotType.ENDER.name() + ":" + i)) {
                ender[i] = null;
                continue;
            }
            byte[] bytes = parts.enderChestSlotBytes()[i];
            ender[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        target.closeInventory();
        target.getInventory().setStorageContents(storage);
        target.getInventory().setArmorContents(armor);
        target.getInventory().setItemInOffHand(offhand);
        target.getEnderChest().setContents(ender);
        target.updateInventory();
    }

    void assertTargetEnderCapacity(Player target, SnapshotParts parts) {
        assertTargetEnderCapacity(parts, targetEnderSlotCount(target));
    }

    static void assertTargetEnderCapacity(SnapshotParts parts, int targetEnderSlotCount) {
        int snapshotEnderSlotCount = parts == null || parts.enderChestSlotBytes() == null
                ? 0
                : parts.enderChestSlotBytes().length;
        if (snapshotEnderSlotCount > targetEnderSlotCount) {
            throw new EnderChestCapacityException(snapshotEnderSlotCount, targetEnderSlotCount);
        }
    }

    private Set<String> toClaimedKeys(List<SlotClaim> claims) {
        Set<String> claimedKeys = new HashSet<>();
        for (SlotClaim claim : claims) {
            claimedKeys.add(claim.slotType().name() + ":" + claim.slotIndex());
        }
        return claimedKeys;
    }

    private void ensureCompatible(byte[][] slots, SlotType slotType, Set<String> claimedKeys) {
        if (slots == null) {
            return;
        }
        for (int i = 0; i < slots.length; i++) {
            if (claimedKeys.contains(slotType.name() + ":" + i)) {
                continue;
            }
            byte[] bytes = slots[i];
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            try {
                ItemStack.deserializeBytes(bytes);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "slot=" + slotType.name() + ":" + i + ", reason=" + String.valueOf(e.getMessage()),
                        e
                );
            }
        }
    }

    private static int targetEnderSlotCount(Player target) {
        return target.getEnderChest().getSize();
    }

    static final class EnderChestCapacityException extends IllegalArgumentException {
        private final int snapshotSlots;
        private final int targetSlots;

        private EnderChestCapacityException(int snapshotSlots, int targetSlots) {
            super("snapshotEnderSlots=" + snapshotSlots + ", targetEnderSlots=" + targetSlots);
            this.snapshotSlots = snapshotSlots;
            this.targetSlots = targetSlots;
        }

        int snapshotSlots() {
            return snapshotSlots;
        }

        int targetSlots() {
            return targetSlots;
        }
    }
}
