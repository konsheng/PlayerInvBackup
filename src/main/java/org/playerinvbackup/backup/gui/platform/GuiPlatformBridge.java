package org.playerinvbackup.backup.gui.platform;

import net.kyori.adventure.text.Component;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * GUI 平台桥, 统一适配 Bukkit Inventory GUI 和 Packet GUI
 */
public interface GuiPlatformBridge {
    void setPacketGuiManager(PacketGuiManager packetGuiManager);

    void openMenu(Player player, Inventory inventory, Component title);

    void closeMenu(Player player);

    boolean isViewing(Player player, Inventory inventory);

    Component currentTitle(Player player);

    Inventory currentTop(Player player);

    void retitleIfViewing(Player player, Inventory inventory, Component title);

    void syncIfViewing(Player player, Inventory inventory);
}
