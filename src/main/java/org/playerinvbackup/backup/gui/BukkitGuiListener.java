package org.playerinvbackup.backup.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.LoadingHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Bukkit 原生 GUI 降级监听器
 *
 * <p>当未启用 ProtocolLib 或手动选择原生 GUI 时, GuiService 会使用 Bukkit Inventory GUI 打开界面
 *
 * <p>该监听器负责拦截点击与拖拽, 阻止玩家移动 GUI 内的物品, 并把按钮点击转发给 GuiService 的处理逻辑
 */
public final class BukkitGuiListener implements Listener {
    private static final long CLICK_DEBOUNCE_MILLIS = 150;

    private final GuiService guiService;
    private final Map<UUID, LastClick> lastClicks = new ConcurrentHashMap<>();

    private record LastClick(int rawSlot, long millis) {
    }

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

        if (isDebounced(player.getUniqueId(), rawSlot)) {
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

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            lastClicks.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClicks.remove(event.getPlayer().getUniqueId());
    }

    private boolean isDebounced(UUID playerUuid, int rawSlot) {
        if (playerUuid == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        LastClick last = lastClicks.get(playerUuid);
        if (last != null && last.rawSlot() == rawSlot && now - last.millis() < CLICK_DEBOUNCE_MILLIS) {
            return true;
        }
        lastClicks.put(playerUuid, new LastClick(rawSlot, now));
        return false;
    }
}
