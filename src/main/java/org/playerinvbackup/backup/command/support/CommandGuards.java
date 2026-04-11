package org.playerinvbackup.backup.command.support;

import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.text.Chat;

/**
 * 命令通用 guard
 *
 * <p>集中处理权限, 玩家态, store ready 和基础用法校验
 */
public final class CommandGuards {
    private final BooleanSupplier storeReady;

    public CommandGuards(BooleanSupplier storeReady) {
        this.storeReady = storeReady;
    }

    public boolean requirePermission(CommandContext ctx, String permission) {
        if (Permissions.has(ctx.sender(), permission)) {
            return true;
        }
        Chat.error(ctx.sender(), "errors.no-permission", Placeholder.unparsed("perm", permission));
        return false;
    }

    public boolean requireMinArgs(CommandContext ctx, int minArgs, String usageKey) {
        if (ctx.argCount() >= minArgs) {
            return true;
        }
        Chat.error(ctx.sender(), usageKey, Placeholder.unparsed("label", ctx.label()));
        return false;
    }

    public Player requirePlayer(CommandContext ctx, String errorKey) {
        Player player = ctx.senderAsPlayer();
        if (player != null) {
            return player;
        }
        Chat.error(ctx.sender(), errorKey);
        return null;
    }

    public boolean requireStoreReady(CommandContext ctx) {
        if (storeReady.getAsBoolean()) {
            return true;
        }
        String safeLabel = ctx.label() == null || ctx.label().isBlank() ? "playerinvbackup" : ctx.label();
        Chat.error(ctx.sender(), "errors.store-unavailable", Placeholder.unparsed("label", safeLabel));
        return false;
    }
}
