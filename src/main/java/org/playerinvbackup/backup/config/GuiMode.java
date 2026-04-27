package org.playerinvbackup.backup.config;

import java.util.Locale;

/**
 * GUI 渲染模式
 *
 * <p>用于解析 {@code config.yml} 中的 {@code gui.mode} 配置, 决定插件应使用
 * 1) 自动选择模式
 * 2) 原生 Bukkit Inventory GUI
 * 3) 基于 ProtocolLib 的 Packet GUI
 */
public enum GuiMode {
    AUTO("auto"),
    BUKKIT("bukkit"),
    PACKET("packet");

    private final String configValue;

    GuiMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /**
     * 从配置字符串解析 GUI 模式
     *
     * <p>为了兼容旧写法和更直观的别名, 这里同时接受
     * {@code native}/{@code inventory} -> {@link #BUKKIT}
     * {@code protocollib} -> {@link #PACKET}
     */
    public static GuiMode parseOrNull(String value) {
        if (value == null) {
            return null;
        }

        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }

        return switch (v) {
            case "auto" -> AUTO;
            case "bukkit", "native", "inventory" -> BUKKIT;
            case "packet", "protocollib" -> PACKET;
            default -> null;
        };
    }
}

