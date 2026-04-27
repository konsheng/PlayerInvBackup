package org.playerinvbackup.backup.command.handler;

import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.text.Chat;

/**
 * 恢复命令处理器
 *
 * <p>负责 restore
 */
public final class RestoreHandler implements SubcommandHandler {
    private final RestoreService restoreService;
    private final CommandGuards guards;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;

    public RestoreHandler(
            RestoreService restoreService,
            CommandGuards guards,
            TargetResolver targetResolver,
            CommandSuggestions suggestions
    ) {
        this.restoreService = restoreService;
        this.guards = guards;
        this.targetResolver = targetResolver;
        this.suggestions = suggestions;
    }

    @Override
    public String name() {
        return "restore";
    }

    @Override
    public boolean execute(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.RESTORE) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 2, "errors.usage-restore")) {
            return true;
        }

        var target = targetResolver.resolveOnlinePlayer(ctx.arg(0));
        if (target == null) {
            Chat.error(ctx.sender(), "errors.restore-target-offline");
            return true;
        }

        restoreService.restoreToPlayer(ctx.sender(), target, ctx.arg(1));
        return true;
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if (ctx.argCount() == 1) {
            return suggestions.onlinePlayers(ctx.arg(0));
        }
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        return Permissions.has(ctx.sender(), Permissions.RESTORE);
    }
}
