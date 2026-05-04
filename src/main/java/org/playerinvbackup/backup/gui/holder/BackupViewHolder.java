package org.playerinvbackup.backup.gui.holder;

import java.util.UUID;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.view.EnderChestPageMapper;
import org.playerinvbackup.backup.store.BackupQuery;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 备份预览界面的 holder
 *
 * <p>保存当前查看的备份编号, 视图类型 (背包/末影箱), 领取标记, 以及置顶/备注状态
 */
public final class BackupViewHolder implements InventoryHolder {
    private final UUID targetUuid;
    private final String targetName;
    private final String backupId;
    private final String serverId;
    private final int listPage;
    private final BackupQuery listQuery;
    private final BackupGuiMode guiMode;
    // 视图在 GUI 内可切换, 需要原地更新
    private GuiView view;
    private final SnapshotParts parts;
    private final boolean claimOnce;
    private final boolean[] claimedInv;
    private final boolean[] claimedEnder;
    private final boolean[] claimRecordInv;
    private final boolean[] claimRecordEnder;
    private final boolean[] incompatibleInv;
    private final boolean[] incompatibleEnder;
    private final boolean incompatibleClaimBlocksWholeBackup;
    private final String worldName;
    private final Double locationX;
    private final Double locationY;
    private final Double locationZ;
    private final String targetWorldName;
    private final Double targetLocationX;
    private final Double targetLocationY;
    private final Double targetLocationZ;
    private final UUID killerPlayerUuid;
    private final String killerPlayerName;
    private final boolean teleportButtonVisible;
    // GUI 内部状态需要原地更新, 所以这里不使用 final
    private boolean locked;
    private String note;
    private int enderPage;
    private Inventory inventory;

    public BackupViewHolder(
            UUID targetUuid,
            String targetName,
            String backupId,
            String serverId,
            int listPage,
            BackupQuery listQuery,
            BackupGuiMode guiMode,
            GuiView view,
            SnapshotParts parts,
            boolean claimOnce,
            boolean[] claimedInv,
            boolean[] claimedEnder,
            boolean[] claimRecordInv,
            boolean[] claimRecordEnder,
            boolean[] incompatibleInv,
            boolean[] incompatibleEnder,
            boolean incompatibleClaimBlocksWholeBackup,
            String worldName,
            Double locationX,
            Double locationY,
            Double locationZ,
            String targetWorldName,
            Double targetLocationX,
            Double targetLocationY,
            Double targetLocationZ,
            UUID killerPlayerUuid,
            String killerPlayerName,
            boolean locked,
            String note
    ) {
        this(
                targetUuid,
                targetName,
                backupId,
                serverId,
                listPage,
                listQuery,
                guiMode,
                view,
                parts,
                claimOnce,
                claimedInv,
                claimedEnder,
                claimRecordInv,
                claimRecordEnder,
                incompatibleInv,
                incompatibleEnder,
                incompatibleClaimBlocksWholeBackup,
                worldName,
                locationX,
                locationY,
                locationZ,
                targetWorldName,
                targetLocationX,
                targetLocationY,
                targetLocationZ,
                killerPlayerUuid,
                killerPlayerName,
                locked,
                note,
                false
        );
    }

    public BackupViewHolder(
            UUID targetUuid,
            String targetName,
            String backupId,
            String serverId,
            int listPage,
            BackupQuery listQuery,
            BackupGuiMode guiMode,
            GuiView view,
            SnapshotParts parts,
            boolean claimOnce,
            boolean[] claimedInv,
            boolean[] claimedEnder,
            boolean[] claimRecordInv,
            boolean[] claimRecordEnder,
            boolean[] incompatibleInv,
            boolean[] incompatibleEnder,
            boolean incompatibleClaimBlocksWholeBackup,
            String worldName,
            Double locationX,
            Double locationY,
            Double locationZ,
            String targetWorldName,
            Double targetLocationX,
            Double targetLocationY,
            Double targetLocationZ,
            UUID killerPlayerUuid,
            String killerPlayerName,
            boolean locked,
            String note,
            boolean teleportButtonVisible
    ) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backupId = backupId;
        this.serverId = serverId;
        this.listPage = Math.max(0, listPage);
        this.listQuery = listQuery == null ? BackupQuery.all() : listQuery;
        this.guiMode = guiMode == null ? BackupGuiMode.MANAGE : guiMode;
        this.view = view == null ? GuiView.INVENTORY : view;
        this.parts = parts;
        this.claimOnce = claimOnce;
        this.claimedInv = claimedInv;
        this.claimedEnder = claimedEnder;
        this.claimRecordInv = claimRecordInv;
        this.claimRecordEnder = claimRecordEnder;
        this.incompatibleInv = incompatibleInv;
        this.incompatibleEnder = incompatibleEnder;
        this.incompatibleClaimBlocksWholeBackup = incompatibleClaimBlocksWholeBackup;
        this.worldName = worldName;
        this.locationX = locationX;
        this.locationY = locationY;
        this.locationZ = locationZ;
        this.targetWorldName = targetWorldName;
        this.targetLocationX = targetLocationX;
        this.targetLocationY = targetLocationY;
        this.targetLocationZ = targetLocationZ;
        this.killerPlayerUuid = killerPlayerUuid;
        this.killerPlayerName = killerPlayerName;
        this.teleportButtonVisible = teleportButtonVisible;
        this.locked = locked;
        this.note = note == null ? "" : note;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public String backupId() {
        return backupId;
    }

    public String serverId() {
        return serverId;
    }

    public int listPage() {
        return listPage;
    }

    public BackupQuery listQuery() {
        return listQuery;
    }

    public BackupGuiMode guiMode() {
        return guiMode;
    }

    public boolean viewOnly() {
        return guiMode.viewOnly();
    }

    public GuiView view() {
        return view;
    }

    public void setView(GuiView view) {
        this.view = view == null ? GuiView.INVENTORY : view;
        if (this.view == GuiView.ENDER_CHEST) {
            this.enderPage = EnderChestPageMapper.clampPage(enderPage, enderSlotCount());
        }
    }

    public SnapshotParts parts() {
        return parts;
    }

    public boolean claimOnce() {
        return claimOnce;
    }

    public boolean[] claimedInv() {
        return claimedInv;
    }

    public boolean[] claimedEnder() {
        return claimedEnder;
    }

    public boolean[] claimRecordInv() {
        return claimRecordInv;
    }

    public boolean[] claimRecordEnder() {
        return claimRecordEnder;
    }

    public boolean[] incompatibleInv() {
        return incompatibleInv;
    }

    public boolean[] incompatibleEnder() {
        return incompatibleEnder;
    }

    public boolean incompatibleClaimBlocksWholeBackup() {
        return incompatibleClaimBlocksWholeBackup;
    }

    public int enderPage() {
        return enderPage;
    }

    public void setEnderPage(int enderPage) {
        this.enderPage = EnderChestPageMapper.clampPage(enderPage, enderSlotCount());
    }

    public int enderMaxPage() {
        return EnderChestPageMapper.maxPage(enderSlotCount());
    }

    public boolean hasMultipleEnderPages() {
        return EnderChestPageMapper.hasMultiplePages(enderSlotCount());
    }

    public int enderSlotCount() {
        return parts == null || parts.enderChestSlotBytes() == null ? 0 : parts.enderChestSlotBytes().length;
    }

    public int enderPageStartSlot() {
        return enderPage * EnderChestPageMapper.PAGE_SIZE;
    }

    public int enderDisplaySlotToRealSlot(int displaySlot) {
        return EnderChestPageMapper.displaySlotToRealSlot(enderPage, displaySlot, enderSlotCount());
    }

    public int enderRealSlotToDisplaySlot(int realSlot) {
        return EnderChestPageMapper.realSlotToDisplaySlot(enderPage, realSlot, enderSlotCount());
    }

    public String worldName() {
        return worldName;
    }

    public Double locationX() {
        return locationX;
    }

    public Double locationY() {
        return locationY;
    }

    public Double locationZ() {
        return locationZ;
    }

    public String targetWorldName() {
        return targetWorldName;
    }

    public Double targetLocationX() {
        return targetLocationX;
    }

    public Double targetLocationY() {
        return targetLocationY;
    }

    public Double targetLocationZ() {
        return targetLocationZ;
    }

    public UUID killerPlayerUuid() {
        return killerPlayerUuid;
    }

    public String killerPlayerName() {
        return killerPlayerName;
    }

    public boolean teleportButtonVisible() {
        return teleportButtonVisible;
    }

    public boolean locked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public String note() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
