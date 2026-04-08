package org.playerinvbackup.backup.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/**
 * 聊天/提示发送工具
 *
 * <p>统一从语言文件读取文本并附加 prefix, 避免各处重复处理
 */
public final class Chat {
    private static volatile Lang lang;

    private Chat() {
    }

    public static void init(Lang newLang) {
        lang = newLang;
    }

    public static void plain(CommandSender sender, String key, TagResolver... placeholders) {
        send(sender, resolve().msg(key, placeholders));
    }

    public static void plainList(CommandSender sender, String key, TagResolver... placeholders) {
        for (Component line : resolve().msgList(key, placeholders)) {
            send(sender, line);
        }
    }

    public static void info(CommandSender sender, String key, TagResolver... placeholders) {
        send(sender, resolve().msg(key, placeholders));
    }

    public static void warn(CommandSender sender, String key, TagResolver... placeholders) {
        send(sender, resolve().msg(key, placeholders));
    }

    public static void success(CommandSender sender, String key, TagResolver... placeholders) {
        send(sender, resolve().msg(key, placeholders));
    }

    public static void error(CommandSender sender, String key, TagResolver... placeholders) {
        send(sender, resolve().msg(key, placeholders));
    }

    private static void send(CommandSender sender, Component component) {
        // 前缀由语言文件自行控制(使用 <prefix> 变量), 这里不再强制拼接
        sender.sendMessage(component);
    }

    private static Lang resolve() {
        Lang current = lang;
        if (current == null) {
            throw new IllegalStateException("Lang 尚未初始化, 请先调用 Chat.init(lang)");
        }
        return current;
    }
}
