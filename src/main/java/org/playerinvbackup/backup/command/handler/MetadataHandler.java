package org.playerinvbackup.backup.command.handler;

import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.audit.AuditService;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandAsync;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;

/**
 * 备份元数据命令处理器
 *
 * <p>负责 lock, unlock, note
 */
public final class MetadataHandler implements SubcommandHandler {
    private static final int MAX_NOTE_LENGTH = 120;

    private final BackupStore store;
    private final AuditService auditService;
    private final CommandGuards guards;
    private final CommandAsync async;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;

    public MetadataHandler(
            BackupStore store,
            AuditService auditService,
            CommandGuards guards,
            CommandAsync async,
            TargetResolver targetResolver,
            CommandSuggestions suggestions
    ) {
        this.store = store;
        this.auditService = auditService;
        this.guards = guards;
        this.async = async;
        this.targetResolver = targetResolver;
        this.suggestions = suggestions;
    }

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public List<String> aliases() {
        return List.of("unlock", "note");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(java.util.Locale.ROOT)) {
            case "lock" -> executeLock(ctx);
            case "unlock" -> executeUnlock(ctx);
            case "note" -> executeNote(ctx);
            default -> false;
        };
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
        return Permissions.has(ctx.sender(), Permissions.LOCK);
    }

    private boolean executeLock(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.LOCK) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 2, "errors.usage-lock")) {
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTargetOrNotify(ctx, ctx.arg(0), "errors.offline-not-cached");
        if (target == null) {
            return true;
        }

        String backupId = ctx.arg(1);
        String note = ctx.argCount() >= 3 ? ctx.joinArgs(2) : null;
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            Chat.error(ctx.sender(), "errors.note-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NOTE_LENGTH)));
            return true;
        }

        async.supply(
                ctx,
                CommandAsync.LogSpec.of(
                        "console.command.lock-failed",
                        Placeholder.unparsed("uuid", target.uuid().toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", "-")
                ),
                () -> {
                    boolean updated = store.setBackupLocked(target.uuid(), backupId, true);
                    if (!updated) {
                        return false;
                    }
                    if (note != null) {
                        store.setBackupNote(target.uuid(), backupId, note);
                    }
                    return true;
                },
                updated -> {
                    if (!updated) {
                        Chat.error(ctx.sender(), "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        return;
                    }
                    auditService.log("LOCK_BACKUP", ctx.sender(), target.uuid(), target.name(), backupId, "note=" + (note == null ? "" : note));
                    Chat.success(ctx.sender(), "success.backup-locked", Placeholder.unparsed("backup_id", backupId));
                },
                error -> Chat.error(ctx.sender(), "errors.read-failed")
        );

        return true;
    }

    private boolean executeUnlock(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.LOCK) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 2, "errors.usage-unlock")) {
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTargetOrNotify(ctx, ctx.arg(0), "errors.offline-not-cached");
        if (target == null) {
            return true;
        }

        String backupId = ctx.arg(1);

        async.supply(
                ctx,
                CommandAsync.LogSpec.of(
                        "console.command.unlock-failed",
                        Placeholder.unparsed("uuid", target.uuid().toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", "-")
                ),
                () -> store.setBackupLocked(target.uuid(), backupId, false),
                updated -> {
                    if (!updated) {
                        Chat.error(ctx.sender(), "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        return;
                    }
                    auditService.log("UNLOCK_BACKUP", ctx.sender(), target.uuid(), target.name(), backupId, null);
                    Chat.success(ctx.sender(), "success.backup-unlocked", Placeholder.unparsed("backup_id", backupId));
                },
                error -> Chat.error(ctx.sender(), "errors.read-failed")
        );

        return true;
    }

    private boolean executeNote(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.LOCK) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 2, "errors.usage-note")) {
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTargetOrNotify(ctx, ctx.arg(0), "errors.offline-not-cached");
        if (target == null) {
            return true;
        }

        String backupId = ctx.arg(1);
        String note = ctx.argCount() >= 3 ? ctx.joinArgs(2) : "";
        if (note.length() > MAX_NOTE_LENGTH) {
            Chat.error(ctx.sender(), "errors.note-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NOTE_LENGTH)));
            return true;
        }

        async.supply(
                ctx,
                CommandAsync.LogSpec.of(
                        "console.command.note-failed",
                        Placeholder.unparsed("uuid", target.uuid().toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", "-")
                ),
                () -> store.setBackupNote(target.uuid(), backupId, note),
                updated -> {
                    if (!updated) {
                        Chat.error(ctx.sender(), "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        return;
                    }
                    auditService.log("NOTE_BACKUP", ctx.sender(), target.uuid(), target.name(), backupId, "note=" + note);
                    Chat.success(ctx.sender(), "success.backup-note-set", Placeholder.unparsed("backup_id", backupId));
                },
                error -> Chat.error(ctx.sender(), "errors.read-failed")
        );

        return true;
    }
}
