package org.playerinvbackup.backup.gui.holder;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 加载中界面的 holder
 *
 * <p>用于在异步加载备份列表/详情时展示占位界面
 */
public final class LoadingHolder implements InventoryHolder {
    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
