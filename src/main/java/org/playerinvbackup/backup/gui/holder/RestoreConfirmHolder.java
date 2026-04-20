package org.playerinvbackup.backup.gui.holder;

import java.util.UUID;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.store.BackupQuery;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 恢复二次确认界面的 holder
 *
 * <p>保存恢复目标与备份编号, 并记录返回上一级预览界面所需的上下文
 */
public final class RestoreConfirmHolder implements InventoryHolder {
    public enum RestoreKind {
        ITEMS,
        EXPERIENCE
    }

    private final UUID targetUuid;
    private final String targetName;
    private final String backupId;
    private final int listPage;
    private final BackupQuery listQuery;
    private final GuiView returnView;
    private final RestoreKind kind;
    private final String worldName;
    private final Double locationX;
    private final Double locationY;
    private final Double locationZ;
    private final int experienceLevel;
    private final float experienceProgress;
    private final int totalExperience;
    private Inventory inventory;

    public RestoreConfirmHolder(
            UUID targetUuid,
            String targetName,
            String backupId,
            int listPage,
            BackupQuery listQuery,
            GuiView returnView,
            RestoreKind kind,
            String worldName,
            Double locationX,
            Double locationY,
            Double locationZ,
            int experienceLevel,
            float experienceProgress,
            int totalExperience
    ) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backupId = backupId;
        this.listPage = Math.max(0, listPage);
        this.listQuery = listQuery == null ? BackupQuery.all() : listQuery;
        this.returnView = returnView;
        this.kind = kind == null ? RestoreKind.ITEMS : kind;
        this.worldName = worldName;
        this.locationX = locationX;
        this.locationY = locationY;
        this.locationZ = locationZ;
        this.experienceLevel = experienceLevel;
        this.experienceProgress = experienceProgress;
        this.totalExperience = totalExperience;
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

    public GuiView returnView() {
        return returnView;
    }

    public RestoreKind kind() {
        return kind;
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

    public int experienceLevel() {
        return experienceLevel;
    }

    public float experienceProgress() {
        return experienceProgress;
    }

    public int totalExperience() {
        return totalExperience;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
