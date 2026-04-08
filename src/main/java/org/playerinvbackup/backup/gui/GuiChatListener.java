package org.playerinvbackup.backup.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * GUI 聊天输入监听
 *
 * <p>用于实现 GUI 内的搜索/过滤等需要玩家输入的场景, 例如按备份编号搜索
 */
public final class GuiChatListener implements Listener {
    private final GuiService guiService;

    public GuiChatListener(GuiService guiService) {
        this.guiService = guiService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (guiService.handleBackupIdSearchChat(player, message)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guiService.cancelBackupIdSearch(event.getPlayer().getUniqueId());
    }
}
