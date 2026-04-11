package org.playerinvbackup.backup.command.handler;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;

/**
 * 管理类命令处理器
 *
 * <p>当前负责 help 和 reload
 */
public final class AdminHandler implements SubcommandHandler {
    @FunctionalInterface
    public interface ReloadAction {
        void reload() throws Exception;
    }

    private final Supplier<String> versionSupplier;
    private final ReloadAction reloadAction;
    private final BooleanSupplier pluginEnabled;
    private final BooleanSupplier storeReady;
    private final CommandGuards guards;
    private final CommandSuggestions suggestions;
    private final Logger logger;
    private final Lang lang;

    public AdminHandler(
            Supplier<String> versionSupplier,
            ReloadAction reloadAction,
            BooleanSupplier pluginEnabled,
            BooleanSupplier storeReady,
            CommandGuards guards,
            CommandSuggestions suggestions,
            Logger logger,
            Lang lang
    ) {
        this.versionSupplier = versionSupplier;
        this.reloadAction = reloadAction;
        this.pluginEnabled = pluginEnabled;
        this.storeReady = storeReady;
        this.guards = guards;
        this.suggestions = suggestions;
        this.logger = logger;
        this.lang = lang;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public List<String> aliases() {
        return List.of("tips", "reload");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(java.util.Locale.ROOT)) {
            case "help" -> executeHelp(ctx);
            case "tips" -> executeTips(ctx);
            case "reload" -> executeReload(ctx);
            default -> false;
        };
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        String permission = "reload".equalsIgnoreCase(token) ? Permissions.RELOAD : Permissions.ADMIN;
        return org.playerinvbackup.backup.Permissions.has(ctx.sender(), permission);
    }

    private boolean executeHelp(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.ADMIN)) {
            return true;
        }
        sendHelpContents(ctx);
        return true;
    }

    public boolean sendHelpDirect(CommandContext ctx) {
        sendHelpContents(ctx);
        return true;
    }

    private void sendHelpContents(CommandContext ctx) {
        Chat.plain(ctx.sender(), "help.header", Placeholder.unparsed("version", versionSupplier.get()));
        Chat.plainList(ctx.sender(), "help.lines", Placeholder.unparsed("label", ctx.label()));
        Chat.plainList(ctx.sender(), "help.commands", Placeholder.unparsed("label", ctx.label()));
        Chat.plain(ctx.sender(), "help.example", Placeholder.unparsed("label", ctx.label()));
    }

    private boolean executeTips(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.ADMIN)) {
            return true;
        }
        Chat.plain(ctx.sender(), "tips.header");
        Chat.plainList(ctx.sender(), "tips.lines", Placeholder.unparsed("label", ctx.label()));
        return true;
    }

    private boolean executeReload(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.RELOAD)) {
            return true;
        }

        boolean reloaded = false;
        try {
            reloadAction.reload();
            reloaded = true;
        } catch (Exception e) {
            logger.log(
                    Level.WARNING,
                    lang.plain(
                            "console.command.reload-failed",
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
        }

        if (!reloaded || !pluginEnabled.getAsBoolean() || !storeReady.getAsBoolean()) {
            Chat.error(ctx.sender(), "errors.reload-failed");
            return true;
        }
        Chat.success(ctx.sender(), "success.reloaded");
        return true;
    }
}
