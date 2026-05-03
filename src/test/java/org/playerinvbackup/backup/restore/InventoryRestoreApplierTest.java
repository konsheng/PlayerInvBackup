package org.playerinvbackup.backup.restore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.SnapshotParts;

class InventoryRestoreApplierTest {
    @Test
    void targetCapacityAllowsEqualAndLargerEnderChest() {
        SnapshotParts parts = emptySnapshotParts(27);

        assertDoesNotThrow(() -> InventoryRestoreApplier.assertTargetEnderCapacity(parts, 27));
        assertDoesNotThrow(() -> InventoryRestoreApplier.assertTargetEnderCapacity(parts, 54));
    }

    @Test
    void targetCapacityRejectsSmallerEnderChest() {
        SnapshotParts parts = emptySnapshotParts(54);

        InventoryRestoreApplier.EnderChestCapacityException exception = assertThrows(
                InventoryRestoreApplier.EnderChestCapacityException.class,
                () -> InventoryRestoreApplier.assertTargetEnderCapacity(parts, 45)
        );

        assertEquals(54, exception.snapshotSlots());
        assertEquals(45, exception.targetSlots());
    }

    private static SnapshotParts emptySnapshotParts(int enderSlotCount) {
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
