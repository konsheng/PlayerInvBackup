package org.playerinvbackup.backup.restore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.playerinvbackup.backup.codec.SnapshotCodec;
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

        ItemStack[] ender = new ItemStack[SnapshotCodec.ENDER_CHEST_SLOT_COUNT];
        for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT; i++) {
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

    private Set<String> toClaimedKeys(List<SlotClaim> claims) {
        Set<String> claimedKeys = new HashSet<>();
        for (SlotClaim claim : claims) {
            claimedKeys.add(claim.slotType().name() + ":" + claim.slotIndex());
        }
        return claimedKeys;
    }
}
