package org.baymc.backup.gui.holder;

import java.util.UUID;
import org.baymc.backup.domain.SnapshotParts;
import org.baymc.backup.gui.GuiView;
import org.baymc.backup.store.BackupQuery;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 备份预览界面的 holder
 *
 * <p>保存当前查看的备份编号, 视图类型(背包/末影箱), 领取标记, 以及置顶/备注状态
 */
public final class BackupViewHolder implements InventoryHolder {
    private final UUID targetUuid;
    private final String targetName;
    private final String backupId;
    private final int listPage;
    private final BackupQuery listQuery;
    // 视图在 GUI 内可切换, 需要原地更新
    private GuiView view;
    private final SnapshotParts parts;
    private final boolean[] claimedInv;
    private final boolean[] claimedEnder;
    // GUI 内部状态需要原地更新, 所以这里不使用 final
    private boolean locked;
    private String note;
    private Inventory inventory;

    public BackupViewHolder(
            UUID targetUuid,
            String targetName,
            String backupId,
            int listPage,
            BackupQuery listQuery,
            GuiView view,
            SnapshotParts parts,
            boolean[] claimedInv,
            boolean[] claimedEnder,
            boolean locked,
            String note
    ) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backupId = backupId;
        this.listPage = Math.max(0, listPage);
        this.listQuery = listQuery == null ? BackupQuery.all() : listQuery;
        this.view = view == null ? GuiView.INVENTORY : view;
        this.parts = parts;
        this.claimedInv = claimedInv;
        this.claimedEnder = claimedEnder;
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

    public int listPage() {
        return listPage;
    }

    public BackupQuery listQuery() {
        return listQuery;
    }

    public GuiView view() {
        return view;
    }

    public void setView(GuiView view) {
        this.view = view == null ? GuiView.INVENTORY : view;
    }

    public SnapshotParts parts() {
        return parts;
    }

    public boolean[] claimedInv() {
        return claimedInv;
    }

    public boolean[] claimedEnder() {
        return claimedEnder;
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
