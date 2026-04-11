package org.playerinvbackup.backup.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.command.handler.AdminHandler;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.text.Chat;

/**
 * 子命令分发器
 *
 * <p>负责首参数路由, 未知命令回退 help, 以及 tab 补全转发
 */
public final class BackupCommandDispatcher {
    private final CommandSuggestions suggestions;
    private final SubcommandHandler helpHandler;
    private final Map<String, SubcommandHandler> handlersByToken;

    public BackupCommandDispatcher(List<SubcommandHandler> handlers, CommandSuggestions suggestions) {
        this.suggestions = suggestions;
        this.handlersByToken = new LinkedHashMap<>();

        SubcommandHandler resolvedHelpHandler = null;
        for (SubcommandHandler handler : handlers) {
            for (String token : handler.commandTokens()) {
                handlersByToken.put(token.toLowerCase(Locale.ROOT), handler);
            }
            if ("help".equalsIgnoreCase(handler.name())) {
                resolvedHelpHandler = handler;
            }
        }
        this.helpHandler = resolvedHelpHandler;
    }

    public boolean onCommand(CommandContext ctx) {
        if (ctx.rawArgCount() == 0) {
            return fallbackToHelp(ctx, false);
        }

        String token = ctx.subcommand().toLowerCase(Locale.ROOT);
        SubcommandHandler handler = handlersByToken.get(token);
        if (handler == null) {
            Chat.error(ctx.sender(), "errors.unknown-subcommand", Placeholder.unparsed("sub", ctx.subcommand()));
            return fallbackToHelp(ctx, true);
        }
        return handler.execute(ctx);
    }

    public List<String> onTabComplete(CommandContext ctx) {
        if (ctx.rawArgCount() <= 1) {
            String token = ctx.rawArgCount() == 0 ? "" : ctx.rawArg(0);
            LinkedHashSet<String> visibleTokens = new LinkedHashSet<>();
            for (Map.Entry<String, SubcommandHandler> entry : handlersByToken.entrySet()) {
                if (entry.getValue().isVisible(ctx, entry.getKey())) {
                    visibleTokens.add(entry.getKey());
                }
            }
            return suggestions.filterPrefix(token, visibleTokens);
        }

        SubcommandHandler handler = handlersByToken.get(ctx.subcommand().toLowerCase(Locale.ROOT));
        if (handler == null) {
            return List.of();
        }
        return handler.complete(ctx);
    }

    private boolean fallbackToHelp(CommandContext ctx, boolean bypassPermission) {
        if (helpHandler == null) {
            return true;
        }
        CommandContext helpContext = ctx.withRawArgs("help");
        if (bypassPermission && helpHandler instanceof AdminHandler adminHandler) {
            return adminHandler.sendHelpDirect(helpContext);
        }
        return helpHandler.execute(helpContext);
    }
}
