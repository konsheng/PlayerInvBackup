package org.playerinvbackup.backup.command.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.command.CommandContext;
import org.playerinvbackup.backup.command.SubcommandHandler;
import org.playerinvbackup.backup.command.support.CommandAsync;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.config.StorageType;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.util.BackupLocationFormatter;

/**
 * 查询类命令处理器
 *
 * <p>负责 list, info, status
 */
public final class QueryHandler implements SubcommandHandler {
    private static final int LIST_PAGE_SIZE = 10;

    private final PlayerInvBackupPlugin plugin;
    private final CommandGuards guards;
    private final CommandAsync async;
    private final TargetResolver targetResolver;
    private final CommandSuggestions suggestions;
    private final DateTimeFormatter timeFormatter;

    public QueryHandler(
            PlayerInvBackupPlugin plugin,
            CommandGuards guards,
            CommandAsync async,
            TargetResolver targetResolver,
            CommandSuggestions suggestions,
            DateTimeFormatter timeFormatter
    ) {
        this.plugin = plugin;
        this.guards = guards;
        this.async = async;
        this.targetResolver = targetResolver;
        this.suggestions = suggestions;
        this.timeFormatter = timeFormatter;
    }

    @Override
    public String name() {
        return "list";
    }

    @Override
    public List<String> aliases() {
        return List.of("info", "status");
    }

    @Override
    public boolean execute(CommandContext ctx) {
        return switch (ctx.subcommand().toLowerCase(Locale.ROOT)) {
            case "list" -> executeList(ctx);
            case "info" -> executeInfo(ctx);
            case "status" -> executeStatus(ctx);
            default -> false;
        };
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        String token = ctx.subcommand().toLowerCase(Locale.ROOT);
        if (ctx.argCount() == 1 && ("list".equals(token) || "info".equals(token))) {
            return suggestions.onlinePlayers(ctx.arg(0));
        }
        return List.of();
    }

    @Override
    public boolean isVisible(CommandContext ctx, String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "list" -> Permissions.has(ctx.sender(), Permissions.LIST);
            case "info" -> Permissions.has(ctx.sender(), Permissions.INFO);
            case "status" -> Permissions.has(ctx.sender(), Permissions.STATUS);
            default -> false;
        };
    }

    private boolean executeList(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.LIST) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 1, "errors.usage-list")) {
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTargetOrNotify(ctx, ctx.arg(0), "errors.offline-not-cached");
        if (target == null) {
            return true;
        }

        int page = parsePositiveIntOrDefault(ctx.arg(1));
        int offset = (page - 1) * LIST_PAGE_SIZE;
        BackupStore store = plugin.store();

        async.supply(
                ctx,
                CommandAsync.LogSpec.of(
                        "console.command.list-load-failed",
                        Placeholder.unparsed("uuid", target.uuid().toString()),
                        Placeholder.unparsed("page", String.valueOf(page)),
                        Placeholder.unparsed("reason", "-")
                ),
                () -> store.listBackups(target.uuid(), BackupQuery.all(), offset, LIST_PAGE_SIZE),
                backups -> {
                    Chat.plain(
                            ctx.sender(),
                            "list.title",
                            Placeholder.unparsed("target", target.name()),
                            Placeholder.unparsed("page", String.valueOf(page))
                    );
                    if (backups.isEmpty()) {
                        Chat.plain(ctx.sender(), "list.empty");
                        return;
                    }
                    for (BackupMeta meta : backups) {
                        String time = timeFormatter.format(Instant.ofEpochMilli(meta.createdAtMillis()));
                        String locked = plugin.lang().raw(meta.locked() ? "common.yes_text" : "common.no_text");
                        String note = meta.note() == null || meta.note().isBlank()
                                ? plugin.lang().raw("common.none")
                                : meta.note();
                        String server = displayServerName(meta.serverId());
                        Chat.plain(
                                ctx.sender(),
                                "list.line",
                                Placeholder.unparsed("time", time),
                                Placeholder.unparsed("id", meta.backupId()),
                                Placeholder.unparsed("trigger", plugin.lang().raw(meta.trigger().langKey())),
                                Placeholder.unparsed("server", server),
                                Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                                Placeholder.unparsed("locked", locked),
                                Placeholder.unparsed("note", note)
                        );
                    }
                },
                error -> Chat.error(ctx.sender(), "errors.load-failed")
        );

        return true;
    }

    private boolean executeInfo(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.INFO) || !guards.requireStoreReady(ctx)) {
            return true;
        }
        if (!guards.requireMinArgs(ctx, 2, "errors.usage-info")) {
            return true;
        }

        TargetResolver.ResolvedTarget target = targetResolver.resolveStoredTargetOrNotify(ctx, ctx.arg(0), "errors.offline-not-cached");
        if (target == null) {
            return true;
        }

        String backupId = ctx.arg(1);
        BackupStore store = plugin.store();

        async.supply(
                ctx,
                CommandAsync.LogSpec.of(
                        "console.command.info-load-failed",
                        Placeholder.unparsed("uuid", target.uuid().toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", "-")
                ),
                () -> {
                    BackupRecord record = store.loadBackup(target.uuid(), backupId).orElse(null);
                    if (record == null) {
                        return null;
                    }
                    List<SlotClaim> claims = store.listClaims(target.uuid(), backupId);
                    return new InfoResult(record, claims);
                },
                result -> {
                    if (result == null) {
                        Chat.error(ctx.sender(), "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        return;
                    }

                    BackupMeta meta = result.record().meta();
                    String time = timeFormatter.format(Instant.ofEpochMilli(meta.createdAtMillis()));
                    int claimedCount = result.claims() == null ? 0 : result.claims().size();
                    String locked = plugin.lang().raw(meta.locked() ? "common.yes_text" : "common.no_text");
                    String note = meta.note() == null || meta.note().isBlank()
                            ? plugin.lang().raw("common.none")
                            : meta.note();
                    String server = displayServerName(meta.serverId());

                    Chat.plain(
                            ctx.sender(),
                            "info.title",
                            Placeholder.unparsed("target", target.name()),
                            Placeholder.unparsed("id", meta.backupId())
                    );
                    Chat.plainList(
                            ctx.sender(),
                            "info.lines",
                            Placeholder.unparsed("time", time),
                            Placeholder.unparsed("trigger", plugin.lang().raw(meta.trigger().langKey())),
                            Placeholder.unparsed("server", server),
                            Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                            Placeholder.unparsed("world", BackupLocationFormatter.displayWorld(plugin, meta.worldName(), meta.targetWorldName())),
                            Placeholder.unparsed("position", BackupLocationFormatter.displayPosition(
                                    plugin,
                                    meta.locationX(),
                                    meta.locationY(),
                                    meta.locationZ(),
                                    meta.targetLocationX(),
                                    meta.targetLocationY(),
                                    meta.targetLocationZ()
                            )),
                            Placeholder.unparsed("sha256", meta.sha256Hex()),
                            Placeholder.unparsed("claimed", String.valueOf(claimedCount)),
                            Placeholder.unparsed("locked", locked),
                            Placeholder.unparsed("note", note)
                    );
                },
                error -> Chat.error(ctx.sender(), "errors.read-failed")
        );

        return true;
    }

    private boolean executeStatus(CommandContext ctx) {
        if (!guards.requirePermission(ctx, Permissions.STATUS)) {
            return true;
        }

        var cfg = plugin.pluginConfig();
        var dispatcher = plugin.ioDispatcher();
        String none = plugin.lang().raw("common.none");

        String storageName = storageName();
        String storagePath = storagePath();
        String backupScope = plugin.lang().raw("status.scope-value");
        String serverVersion = serverVersion();
        String pluginVersion = plugin.getPluginMeta().getVersion();
        String statusPluginVersion = plugin.statusPluginVersion();

        Chat.plain(ctx.sender(), "status.title", Placeholder.unparsed("version", pluginVersion));
        Chat.plain(ctx.sender(), "status.server-version", Placeholder.unparsed("version", serverVersion));
        Chat.plain(ctx.sender(), "status.plugin-version", Placeholder.unparsed("version", statusPluginVersion));
        Chat.plain(
                ctx.sender(),
                "status.config",
                Placeholder.unparsed("path", plugin.getDataFolder().toPath().resolve("config.yml").toString())
        );
        Chat.plain(ctx.sender(), "status.scope", Placeholder.unparsed("scope", backupScope));
        Chat.plain(
                ctx.sender(),
                "status.current-config",
                Placeholder.unparsed("interval", String.valueOf(cfg.backupInterval().toMinutes())),
                Placeholder.unparsed("jitter", String.valueOf(cfg.jitter().toSeconds())),
                Placeholder.unparsed("keep", String.valueOf(cfg.keepPerPlayer())),
                Placeholder.unparsed("keep_days", String.valueOf(cfg.keepDuration().toDays()))
        );
        Chat.plain(
                ctx.sender(),
                "status.storage",
                Placeholder.unparsed("storage_name", storageName),
                Placeholder.unparsed("storage_path", storagePath)
        );

        String guiModeKey = "common.gui_mode." + (cfg.guiMode() == null ? "auto" : cfg.guiMode().configValue());
        String configuredGuiMode = plugin.lang().raw(guiModeKey);
        String activeGuiMode = plugin.isPacketGuiEnabled()
                ? plugin.lang().raw("common.gui_mode.packet")
                : plugin.lang().raw("common.gui_mode.bukkit");
        Chat.plain(
                ctx.sender(),
                "status.gui-mode",
                Placeholder.unparsed("mode", configuredGuiMode),
                Placeholder.unparsed("active", activeGuiMode)
        );

        if (!plugin.isStoreReady()) {
            String reason = plugin.storeInitFailedReason();
            String reasonText = reason == null || reason.isBlank() ? none : reason;
            Chat.plain(ctx.sender(), "status.store-unavailable", Placeholder.unparsed("reason", reasonText));
        }

        String yesText = plugin.lang().raw("common.yes_text");
        String noText = plugin.lang().raw("common.no_text");
        Chat.plain(ctx.sender(), "status.audit-enabled", Placeholder.unparsed("enabled", cfg.auditEnabled() ? yesText : noText));
        Chat.plain(ctx.sender(), "status.audit-console", Placeholder.unparsed("enabled", cfg.auditConsole() ? yesText : noText));
        Chat.plain(ctx.sender(), "status.audit-keep-days", Placeholder.unparsed("days", String.valueOf(cfg.auditKeepDays())));
        Chat.plain(
                ctx.sender(),
                "status.io-queue",
                Placeholder.unparsed("size", dispatcher == null ? none : String.valueOf(dispatcher.queueSize())),
                Placeholder.unparsed("limit", dispatcher == null ? none : String.valueOf(dispatcher.queueLimit())),
                Placeholder.unparsed("remaining", dispatcher == null ? none : String.valueOf(dispatcher.queueRemainingCapacity()))
        );

        sendStorageSize(ctx, cfg.storageType());
        return true;
    }

    private void sendStorageSize(CommandContext ctx, StorageType storageType) {
        switch (storageType) {
            case SQLITE -> sendSqliteSize(ctx);
            case H2 -> sendH2Size(ctx);
            case LOCAL, MYSQL, POSTGRESQL -> {
            }
        }
    }

    private String displayServerName(String serverId) {
        var config = plugin.pluginConfig();
        if (config == null) {
            return serverId == null || serverId.isBlank() ? "default" : serverId;
        }
        return config.displayServerName(serverId);
    }

    private void sendSqliteSize(CommandContext ctx) {
        Path db = plugin.getDataFolder().toPath().resolve(plugin.pluginConfig().sqliteFile());
        if (ctx.sender() instanceof Player player) {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                long size = tryGetFileSize(db);
                async.runOnSender(player, () -> sendSqliteSizeMessage(player, size));
            });
            return;
        }

        long size = tryGetFileSize(db);
        sendSqliteSizeMessage(ctx.sender(), size);
    }

    private void sendSqliteSizeMessage(CommandSender sender, long size) {
        if (size >= 0) {
            Chat.plain(sender, "status.sqlite-size", Placeholder.unparsed("size", formatFileSize(size)));
        } else {
            Chat.plain(sender, "status.sqlite-size-unknown");
        }
    }

    private void sendH2Size(CommandContext ctx) {
        Path base = plugin.getDataFolder().toPath().resolve(plugin.pluginConfig().h2().file());
        Path db = Path.of(base + ".mv.db");
        long size = tryGetFileSize(db);
        if (size >= 0) {
            Chat.plain(ctx.sender(), "status.h2-size", Placeholder.unparsed("size", formatFileSize(size)));
        } else {
            Chat.plain(ctx.sender(), "status.h2-size-unknown");
        }
    }

    private String storageName() {
        return switch (plugin.pluginConfig().storageType()) {
            case SQLITE -> plugin.lang().raw("storage.name.sqlite");
            case LOCAL -> plugin.lang().raw("storage.name.local");
            case MYSQL -> plugin.lang().raw("storage.name.mysql");
            case POSTGRESQL -> plugin.lang().raw("storage.name.postgresql");
            case H2 -> plugin.lang().raw("storage.name.h2");
        };
    }

    private String storagePath() {
        var cfg = plugin.pluginConfig();
        return switch (cfg.storageType()) {
            case SQLITE -> plugin.getDataFolder().toPath().resolve(cfg.sqliteFile()).toString();
            case LOCAL -> plugin.getDataFolder().toPath().resolve(cfg.localBasePath()).toString();
            case MYSQL -> cfg.mysql().host() + ":" + cfg.mysql().port() + "/" + cfg.mysql().database();
            case POSTGRESQL -> cfg.postgresql().host() + ":" + cfg.postgresql().port() + "/" + cfg.postgresql().database();
            case H2 -> plugin.getDataFolder().toPath().resolve(cfg.h2().file()).toString();
        };
    }

    private static int parsePositiveIntOrDefault(String token) {
        int fallback = 1;
        if (token == null || token.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(token.trim());
            return value <= 0 ? fallback : value;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String serverVersion() {
        String version = Bukkit.getVersionMessage();
        String prefix = "This server is running ";
        if (version.startsWith(prefix)) {
            return version.substring(prefix.length());
        }
        return version;
    }

    private static long tryGetFileSize(Path file) {
        if (file == null) {
            return -1;
        }
        try {
            if (!Files.exists(file)) {
                return -1;
            }
            return Files.size(file);
        } catch (IOException | SecurityException ignored) {
            return -1;
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.ROOT, "%d %s", bytes, units[unitIndex]);
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private record InfoResult(BackupRecord record, List<SlotClaim> claims) {
    }
}
