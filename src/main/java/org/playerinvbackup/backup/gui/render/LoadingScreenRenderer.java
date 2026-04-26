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

        // 异步加载期间保留当前 GUI 内容, 不额外渲染 loading 图标
        // LIST_LOADING / VIEW_LOADING 状态仍会在其他位置照常切换
        // 因此在真实数据渲染完成前, 重复点击依然会被拦截
    }
}
