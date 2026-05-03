package org.playerinvbackup.backup.gui.holder;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.store.BackupQuery;

/**
 * 备份列表搜索方式选择界面 holder
 */
public final class SearchModeHolder implements InventoryHolder {
    private final UUID targetUuid;
    private final String targetName;
    private final int page;
    private final BackupQuery query;
    private final BackupGuiMode guiMode;
    private Inventory inventory;

    public SearchModeHolder(UUID targetUuid, String targetName, int page, BackupQuery query, BackupGuiMode guiMode) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.page = Math.max(0, page);
        this.query = query == null ? BackupQuery.all() : query;
        this.guiMode = guiMode == null ? BackupGuiMode.MANAGE : guiMode;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public int page() {
        return page;
    }

    public BackupQuery query() {
        return query;
    }

    public BackupGuiMode guiMode() {
        return guiMode;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
