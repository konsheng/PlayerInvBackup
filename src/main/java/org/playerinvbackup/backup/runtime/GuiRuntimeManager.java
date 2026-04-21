package org.playerinvbackup.backup.runtime;

import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiMode;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;

/**
 * 负责应用 GUI 模式, 并管理可选的 Packet GUI 运行时实例
 *
 * <p>这里集中处理 auto, bukkit, packet 三种模式, 以及 ProtocolLib 存在与否带来的降级路径
 * 主类不再直接关心 packet GUI 如何创建, 如何关闭, 如何记录降级日志
 */
public final class GuiRuntimeManager {
    private final PlayerInvBackupPlugin plugin;

    public GuiRuntimeManager(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 根据当前配置应用 GUI 模式
     *
     * <p>当模式要求 packet GUI 且依赖可用时, 这里负责创建并注册 PacketGuiManager
     * 当依赖缺失或模式要求原生 GUI 时, 这里负责清理 packet GUI 并保留现有降级日志语义
     */
    public PacketGuiManager applyGuiMode(PluginConfig config, Lang lang, GuiService guiService) {
        GuiMode mode = config == null ? GuiMode.AUTO : config.guiMode();
        boolean protocolLibEnabled = isProtocolLibEnabled();

        boolean wantPacket = mode == GuiMode.PACKET || (mode == GuiMode.AUTO && protocolLibEnabled);
        if (!wantPacket) {
            shutdownPacketGui(guiService, null);
            return null;
        }

        if (!protocolLibEnabled) {
            shutdownPacketGui(guiService, null);
            if (mode == GuiMode.PACKET) {
                plugin.getLogger().warning(lang.plain("console.dependency.protocollib-missing-forced"));
            } else {
                plugin.getLogger().info(lang.plain("console.dependency.protocollib-missing"));
            }
            return null;
        }

        PacketGuiManager manager = null;
        try {
            manager = new PacketGuiManager(plugin);
            manager.setGuiService(guiService);
            manager.register();
            guiService.setPacketGuiManager(manager);
            return manager;
        } catch (NoClassDefFoundError | Exception e) {
            shutdownPacketGui(guiService, manager);
            plugin.getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.gui.init-failed",
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
            return null;
        }
    }

    /**
     * 安全关闭 packet GUI, 并把 GuiService 上的引用清空
     *
     * <p>这个方法会同时覆盖正常切模式, reload, 初始化失败这些路径
     * 上层只需要调用它, 不需要重复写空指针保护和清理顺序
     */
    public void shutdownPacketGui(GuiService guiService, PacketGuiManager packetGuiManager) {
        if (packetGuiManager != null) {
            try {
                packetGuiManager.shutdown();
            } catch (Exception ignored) {
            }
        }
        if (guiService != null) {
            guiService.setPacketGuiManager(null);
        }
    }

    private static boolean isProtocolLibEnabled() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
        } catch (Exception ignored) {
            return false;
        }
    }
}
