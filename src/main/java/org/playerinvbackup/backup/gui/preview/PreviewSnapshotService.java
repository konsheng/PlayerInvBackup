package org.playerinvbackup.backup.gui.preview;

import java.util.List;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.bukkit.inventory.ItemStack;

/**
 * 详情页预览数据构建服务, 只负责快照到预览纯数据的转换
 */
public final class PreviewSnapshotService {
    public PreviewSnapshotData build(
            SnapshotParts parts,
            List<SlotClaim> claims,
            boolean blockWholeBackupOnIncompatible,
            boolean claimOnce
    ) {
        boolean[] claimRecordInv = new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT];
        int enderSlotCount = parts.enderChestSlotBytes().length;
        boolean[] claimRecordEnder = new boolean[enderSlotCount];
        if (claims != null) {
            for (SlotClaim claim : claims) {
                if (claim.slotType() == SlotType.INV
                        && claim.slotIndex() >= 0
                        && claim.slotIndex() < claimRecordInv.length) {
                    claimRecordInv[claim.slotIndex()] = true;
                } else if (claim.slotType() == SlotType.ENDER
                        && claim.slotIndex() >= 0
                        && claim.slotIndex() < claimRecordEnder.length) {
                    claimRecordEnder[claim.slotIndex()] = true;
                }
            }
        }
        boolean[] claimedInv = claimOnce ? claimRecordInv.clone() : new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT];
        boolean[] claimedEnder = claimOnce ? claimRecordEnder.clone() : new boolean[enderSlotCount];

        boolean[] incompatibleInv = detectIncompatibleSlots(parts.inventorySlotBytes());
        boolean[] incompatibleEnder = detectIncompatibleSlots(parts.enderChestSlotBytes());
        boolean incompatibleClaimBlocksWholeBackup = blockWholeBackupOnIncompatible
                && (hasIncompatibleSlots(incompatibleInv) || hasIncompatibleSlots(incompatibleEnder));

        return new PreviewSnapshotData(
                claimedInv,
                claimedEnder,
                claimRecordInv,
                claimRecordEnder,
                incompatibleInv,
                incompatibleEnder,
                incompatibleClaimBlocksWholeBackup
        );
    }

    public boolean[] detectIncompatibleSlots(byte[][] slots) {
        boolean[] incompatible = new boolean[slots.length];
        for (int i = 0; i < slots.length; i++) {
            incompatible[i] = isIncompatibleItemBytes(slots[i]);
        }
        return incompatible;
    }

    public boolean isIncompatibleItemBytes(byte[] itemBytes) {
        if (itemBytes == null || itemBytes.length == 0) {
            return false;
        }
        try {
            ItemStack.deserializeBytes(itemBytes);
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    public boolean hasIncompatibleSlots(boolean[] slots) {
        for (boolean slot : slots) {
            if (slot) {
                return true;
            }
        }
        return false;
    }

    public ItemStack toPreviewItem(byte[] itemBytes) {
        if (itemBytes == null) {
            return null;
        }

        try {
            return ItemStack.deserializeBytes(itemBytes);
        } catch (Exception ignored) {
            return null;
        }
    }
}
