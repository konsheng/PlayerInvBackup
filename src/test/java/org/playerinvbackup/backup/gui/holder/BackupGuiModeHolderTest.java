package org.playerinvbackup.backup.gui.holder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.store.BackupQuery;

class BackupGuiModeHolderTest {
    @Test
    void listHolderDefaultsToManageMode() {
        BackupListHolder holder = new BackupListHolder(
                UUID.randomUUID(),
                "Steve",
                0,
                BackupQuery.all(),
                List.of()
        );

        assertEquals(BackupGuiMode.MANAGE, holder.guiMode());
        assertFalse(holder.viewOnly());
    }

    @Test
    void listHolderCanSwitchToViewOnlyMode() {
        BackupListHolder holder = new BackupListHolder(
                UUID.randomUUID(),
                "Steve",
                0,
                BackupQuery.all(),
                List.of()
        );

        holder.setGuiMode(BackupGuiMode.VIEW_ONLY);

        assertEquals(BackupGuiMode.VIEW_ONLY, holder.guiMode());
        assertTrue(holder.viewOnly());
    }

    @Test
    void viewHolderKeepsViewOnlyMode() {
        BackupViewHolder holder = new BackupViewHolder(
                UUID.randomUUID(),
                "Steve",
                "backup-1",
                "default",
                0,
                BackupQuery.all(),
                BackupGuiMode.VIEW_ONLY,
                GuiView.INVENTORY,
                emptySnapshot(),
                false,
                new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT],
                new boolean[SnapshotCodec.ENDER_CHEST_SLOT_COUNT],
                new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT],
                new boolean[SnapshotCodec.ENDER_CHEST_SLOT_COUNT],
                new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT],
                new boolean[SnapshotCodec.ENDER_CHEST_SLOT_COUNT],
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                ""
        );

        assertEquals(BackupGuiMode.VIEW_ONLY, holder.guiMode());
        assertTrue(holder.viewOnly());
    }

    private static SnapshotParts emptySnapshot() {
        return new SnapshotParts(
                new byte[SnapshotCodec.INVENTORY_SLOT_COUNT][],
                new byte[SnapshotCodec.ENDER_CHEST_SLOT_COUNT][],
                true,
                0,
                0.0f,
                0
        );
    }
}
