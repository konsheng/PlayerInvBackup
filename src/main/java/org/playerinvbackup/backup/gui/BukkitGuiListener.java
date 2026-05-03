package org.playerinvbackup.backup.gui;

import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.LoadingHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.holder.SearchModeHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Bukkit 原生 GUI 降级监听器
 *
 * <p>说明
 * 当未启用 ProtocolLib 或手动选择原生 GUI 时, GuiService 会使用 Bukkit Inventory GUI 打开界面
 * 该监听器负责拦截点击与拖拽, 阻止玩家移动 GUI 内的物品, 并把按钮点击转发给 GuiService 的处理逻辑
 */
public final class BukkitGuiListener implements Listener {
    private final GuiService guiService;

    public BukkitGuiListener(GuiService guiService) {
        this.guiService = guiService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryView view = event.getView();
        if (view == null) {
            return;
        }
        Inventory top = view.getTopInventory();
        if (top == null) {
            return;
        }

        Object holder = top.getHolder();
        if (!(holder instanceof BackupListHolder
                || holder instanceof BackupViewHolder
                || holder instanceof RestoreConfirmHolder
                || holder instanceof SearchModeHolder
                || holder instanceof LoadingHolder)) {
            return;
        }

        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }

        if (holder instanceof LoadingHolder) {
            return;
        }

        // 只接受普通 LEFT / RIGHT, 避免 DOUBLE_CLICK 等特殊点击额外触发一次按钮逻辑
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }

        GuiService service = guiService;
        if (service == null) {
            return;
        }

        if (holder instanceof BackupListHolder listHolder) {
            service.handleListClick(player, listHolder, rawSlot);
        } else if (holder instanceof BackupViewHolder viewHolder) {
            service.handleViewClick(player, viewHolder, rawSlot);
        } else if (holder instanceof RestoreConfirmHolder confirmHolder) {
            service.handleRestoreConfirmClick(player, confirmHolder, rawSlot);
        } else if (holder instanceof SearchModeHolder searchModeHolder) {
            service.handleSearchModeClick(player, searchModeHolder, rawSlot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        if (view == null) {
            return;
        }
        Inventory top = view.getTopInventory();
        if (top == null) {
            return;
        }

        Object holder = top.getHolder();
        if (!(holder instanceof BackupListHolder
                || holder instanceof BackupViewHolder
                || holder instanceof RestoreConfirmHolder
                || holder instanceof SearchModeHolder
                || holder instanceof LoadingHolder)) {
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < top.getSize()) {
                event.setCancelled(true);
                break;
            }
        }
    }
}
