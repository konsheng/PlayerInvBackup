package org.playerinvbackup.backup.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 子命令处理器接口
 *
 * <p>每个 handler 负责一组命令, dispatcher 按首参数路由
 */
public interface SubcommandHandler {
    String name();

    default List<String> aliases() {
        return List.of();
    }

    boolean execute(CommandContext ctx);

    List<String> complete(CommandContext ctx);

    default boolean handles(String token) {
        if (token == null) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (name().equalsIgnoreCase(lower)) {
            return true;
        }
        for (String alias : aliases()) {
            if (alias.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }

    default boolean isVisible(CommandContext ctx, String token) {
        return true;
    }

    default List<String> commandTokens() {
        List<String> tokens = new ArrayList<>();
        tokens.add(name());
        tokens.addAll(aliases());
        return List.copyOf(tokens);
    }
}
