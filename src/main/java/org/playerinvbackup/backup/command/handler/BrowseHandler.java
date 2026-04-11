package org.playerinvbackup.backup.command.handler;

import java.util.List;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.text.Chat;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * 浏览类命令处理器
 *
 * <p>负责 open, pending
 */
public final class BrowseHandler implements SubcommandHandler {
    private final GuiService guiService;
    private final CommandGuards guards;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;

    public BrowseHandler(
            GuiService guiService,
            CommandGuards guards,
            TargetResolver targetResolver,
            CommandSuggestions suggestions
    ) {
        this.guiService = guiService;
        this.guards = guards;
        this.targetResolver = targetResolver;
        this.suggestions = suggestions;
    }

    @Override
    public String name() {
        return "open";
    }

    @Override
    public List<String> aliases() {
        return List.of("pending");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(java.util.Locale.ROOT)) {
            case "open" -> executeOpen(ctx);
            case "pending" -> executePending(ctx);
            default -> false;
        };
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if ("open".equalsIgnoreCase(ctx.subcommand()) && ctx.argCount() == 1) {
            return suggestions.onlinePlayers(ctx.arg(0));
        }
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        return switch (token.toLowerCase(java.util.Locale.ROOT)) {
            case "open" -> Permissions.has(ctx.sender(), Permissions.OPEN);
            case "pending" -> Permissions.has(ctx.sender(), Permissions.PENDING);
            default -> false;
        };
    }

    private boolean executeOpen(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.OPEN)) {
            return true;
        }

        var player = guards.requirePlayer(ctx, "errors.console-no-gui");
        if (player == null) {
            return true;
        }
        if (!guards.requireStoreReady(ctx)) {
            return true;
        }

        if (ctx.argCount() == 0) {
            guiService.openBackupList(player, player.getUniqueId(), player.getName(), 0);
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTarget(ctx.arg(0));
        if (target == null) {
            Chat.error(player, "errors.open-offline-not-cached", Placeholder.unparsed("label", ctx.label()));
            return true;
        }

        guiService.openBackupList(player, target.uuid(), target.name(), 0);
        return true;
    }

    private boolean executePending(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.PENDING)) {
            return true;
        }

        var player = guards.requirePlayer(ctx, "errors.console-no-inventory");
        if (player == null) {
            return true;
        }
        if (!guards.requireStoreReady(ctx)) {
            return true;
        }

        guiService.deliverPending(player);
        return true;
    }
}
