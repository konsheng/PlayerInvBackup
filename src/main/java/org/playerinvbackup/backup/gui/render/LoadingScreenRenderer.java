package org.playerinvbackup.backup.gui.render;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;

/**
 * loading 页渲染器
 */
public final class LoadingScreenRenderer {
    private final GuiItemFactory itemFactory;

    public LoadingScreenRenderer(GuiItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    public void render(Inventory inventory, Component label) {
        if (inventory == null) {
            return;
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, null);
        }

        int slot = Math.min(22, inventory.getSize() - 1);
        if (slot < 0) {
            return;
        }
        inventory.setItem(slot, itemFactory.loadingItem(label));
    }
}
