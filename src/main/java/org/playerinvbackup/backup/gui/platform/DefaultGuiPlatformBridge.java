package org.playerinvbackup.backup.gui.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.LoadingHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * 默认 GUI 平台桥, 优先走 Packet GUI, 否则退回 Bukkit 原生 Inventory GUI
 */
public final class DefaultGuiPlatformBridge implements GuiPlatformBridge {
    private static final LegacyComponentSerializer TITLE_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final PlayerInvBackupPlugin plugin;
    private PacketGuiManager packetGuiManager;

    public DefaultGuiPlatformBridge(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void setPacketGuiManager(PacketGuiManager packetGuiManager) {
        this.packetGuiManager = packetGuiManager;
    }

    @Override
    public void openMenu(Player player, Inventory inventory, Component title) {
        if (player == null || inventory == null || title == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.openMenu(player, inventory, title);
            return;
        }

        try {
            player.openInventory(inventory);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void closeMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.closeMenu(player);
            return;
        }

        try {
            InventoryView view = player.getOpenInventory();
            Inventory top = view == null ? null : view.getTopInventory();
            if (top == null || !isGuiHolder(top.getHolder())) {
                return;
            }
            player.closeInventory();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null || !player.isOnline()) {
            return false;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            return manager.isViewing(player, inventory);
        }

        try {
            InventoryView view = player.getOpenInventory();
            return view != null && view.getTopInventory() == inventory;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public Component currentTitle(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            return manager.currentTitle(player);
        }

        try {
            InventoryView view = player.getOpenInventory();
            return view == null ? null : view.title();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public Inventory currentTop(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            return manager.currentTop(player);
        }

        try {
            InventoryView view = player.getOpenInventory();
            return view == null ? null : view.getTopInventory();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void retitleIfViewing(Player player, Inventory inventory, Component title) {
        if (player == null || inventory == null || title == null || !player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.retitleIfViewing(player, inventory, title);
            return;
        }

        if (!isViewing(player, inventory)) {
            return;
        }

        try {
            InventoryView view = player.getOpenInventory();
            if (view != null && view.getTopInventory() == inventory) {
                view.setTitle(TITLE_SERIALIZER.serialize(title));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void syncIfViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null || !player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.syncIfViewing(player, inventory);
            return;
        }

        if (!isViewing(player, inventory)) {
            return;
        }
        try {
            player.updateInventory();
        } catch (Exception ignored) {
        }
    }

    private static boolean isGuiHolder(Object holder) {
        return holder instanceof BackupListHolder
                || holder instanceof BackupViewHolder
                || holder instanceof RestoreConfirmHolder
                || holder instanceof LoadingHolder;
    }
}
