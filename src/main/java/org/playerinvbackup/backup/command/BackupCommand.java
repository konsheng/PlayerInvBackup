package org.playerinvbackup.backup.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /playerinvbackup 主命令实现
 *
 * <p>包含:
 * 1) 备份管理子命令 (open/backup/restore/pending/status/reload 等)
 * 2) 控制台友好命令 (list/info)
 * 3) 置顶显示与备注 (lock/unlock/note)
 * 4) Tab 补全
 */
public final class BackupCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DEFAULT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int LIST_PAGE_SIZE = 10;
    private static final int MAX_NOTE_LENGTH = 120;

    private final PlayerInvBackupPlugin plugin;
    private final GuiService guiService;
    private final RestoreService restoreService;
    private final DateTimeFormatter timeFormatter;

    public BackupCommand(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
        this.guiService = plugin.guiService();
        this.restoreService = plugin.restoreService();
        var config = plugin.pluginConfig();
        this.timeFormatter = config == null ? DEFAULT_TIME_FORMAT : config.backupTimeFormatter();
    }

    private enum Subcommand {
        OPEN("open", Permissions.OPEN, true, true),
        BACKUP("backup", null, false, true),
        BACKUPALL("backupall", Permissions.BACKUP_ALL, false, false),
        RESTORE("restore", Permissions.RESTORE, false, true),
        PENDING("pending", Permissions.PENDING, true, false),
        LIST("list", Permissions.LIST, false, true),
        INFO("info", Permissions.INFO, false, true),
        LOCK("lock", Permissions.LOCK, false, true),
        UNLOCK("unlock", Permissions.LOCK, false, true),
        NOTE("note", Permissions.LOCK, false, true),
        STATUS("status", Permissions.STATUS, false, false),
        RELOAD("reload", Permissions.RELOAD, false, false),
        HELP("help", null, false, false);

        private final List<String> tokens;
        private final String requiredPermission;
        private final boolean playerOnly;
        private final boolean suggestOnlinePlayersOnSecondArg;

        Subcommand(String name, String requiredPermission, boolean playerOnly, boolean suggestOnlinePlayersOnSecondArg, String... aliases) {
            this.requiredPermission = requiredPermission;
            this.playerOnly = playerOnly;
            this.suggestOnlinePlayersOnSecondArg = suggestOnlinePlayersOnSecondArg;

            List<String> tokens = new ArrayList<>(1 + aliases.length);
            tokens.add(name);
            tokens.addAll(List.of(aliases));
            this.tokens = List.copyOf(tokens);
        }

        boolean availableTo(CommandSender sender) {
            if (!playerOnly) {
                return true;
            }
            return sender instanceof Player;
        }

        boolean permittedFor(CommandSender sender) {
            return Permissions.has(sender, requiredPermission);
        }

        boolean suggestOnlinePlayersOnSecondArg() {
            return suggestOnlinePlayersOnSecondArg;
        }

        List<String> tokens() {
            return tokens;
        }

        static Subcommand resolve(String token) {
            if (token == null) {
                return null;
            }
            return BY_TOKEN.get(token.toLowerCase(Locale.ROOT));
        }

        static List<String> topLevelTokensFor(CommandSender sender) {
            List<String> out = new ArrayList<>();
            for (Subcommand subcommand : values()) {
                if (!subcommand.availableTo(sender)) {
                    continue;
                }
                if (!subcommand.permittedFor(sender)) {
                    continue;
                }
                out.addAll(subcommand.tokens());
            }
            return out;
        }

        private static final Map<String, Subcommand> BY_TOKEN;

        static {
            Map<String, Subcommand> map = new HashMap<>();

            for (Subcommand subcommand : values()) {
                for (String token : subcommand.tokens()) {
                    map.put(token.toLowerCase(Locale.ROOT), subcommand);
                }
            }

            BY_TOKEN = Map.copyOf(map);
        }
    }

    private record ResolvedTarget(UUID uuid, String name) {
    }
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!Permissions.has(sender, Permissions.ADMIN)) {
            Chat.error(sender, "errors.no-permission", Placeholder.unparsed("perm", Permissions.ADMIN));
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subToken = args[0];
        Subcommand sub = Subcommand.resolve(subToken);
        if (sub == null) {
            Chat.error(sender, "errors.unknown-subcommand", Placeholder.unparsed("sub", String.valueOf(subToken)));
            sendHelp(sender, label);
            return true;
        }

        switch (sub) {
            case HELP -> sendHelp(sender, label);
            case STATUS -> {
                if (ensurePermission(sender, Permissions.STATUS)) {
                    sendStatus(sender);
                }
            }
            case BACKUP -> {
                if (args.length >= 2) {
                    if (!ensurePermission(sender, Permissions.BACKUP) || !ensureStoreReady(sender, label)) {
                        break;
                    }
                    Player target = findOnlinePlayerByNameOrUuid(args[1]);
                    if (target == null) {
                        Chat.error(sender, "errors.player-not-online");
                        break;
                    }
                    queueManualBackup(sender, label, target, "MANUAL_BACKUP");
                    break;
                }

                if (!ensurePermission(sender, Permissions.SELF_BACKUP)) {
                    break;
                }
                if (!(sender instanceof Player player)) {
                    Chat.error(sender, "errors.usage-backup", Placeholder.unparsed("label", label));
                    break;
                }
                if (!ensureStoreReady(player, label)) {
                    break;
                }

                queueManualBackup(sender, label, player, "SELF_BACKUP");
            }
            case BACKUPALL -> {
                if (!ensurePermission(sender, Permissions.BACKUP_ALL) || !ensureStoreReady(sender, label)) {
                    break;
                }
                if (args.length >= 2) {
                    Chat.error(sender, "errors.usage-backupall", Placeholder.unparsed("label", label));
                    break;
                }
                queueManualBackupAll(sender, label);
            }
            case RELOAD -> {
                if (ensurePermission(sender, Permissions.RELOAD)) {
                    boolean reloaded = false;
                    try {
                        plugin.reload();
                        reloaded = true;
                    } catch (Exception e) {
                        // 避免异常继续向上传递导致 CommandException, 用语言提示告知重载失败即可
                        plugin.getLogger().log(Level.WARNING, plugin.lang().plain(
                                "console.command.reload-failed",
                                Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                        ), e);
                    }
                    if (!reloaded || !plugin.isEnabled() || !plugin.isStoreReady()) {
                        Chat.error(sender, "errors.reload-failed");
                        break;
                    }
                    Chat.success(sender, "success.reloaded");
                }
            }
	            case OPEN -> {
	                if (ensurePermission(sender, Permissions.OPEN)) {
	                    if (!(sender instanceof Player player)) {
	                        Chat.error(sender, "errors.console-no-gui");
                        break;
                    }
                    if (!ensureStoreReady(player, label)) {
                        break;
                    }
                    if (args.length < 2) {
                        guiService.openBackupList(player, player.getUniqueId(), player.getName(), 0);
                        break;
	                    }

	                    String token = args[1];
	                    ResolvedTarget target = resolveTarget(token);
	                    if (target == null) {
	                        Chat.error(player, "errors.open-offline-not-cached", Placeholder.unparsed("label", label));
	                        break;
	                    }
	                    guiService.openBackupList(player, target.uuid(), target.name(), 0);
	                }
	            }
            case RESTORE -> {
                if (ensurePermission(sender, Permissions.RESTORE) && ensureStoreReady(sender, label)) {
                    if (args.length < 3) {
                        Chat.error(sender, "errors.usage-restore", Placeholder.unparsed("label", label));
                        break;
                    }
                    String backupId = args[2];
                    Player target = findOnlinePlayerByNameOrUuid(args[1]);
                    if (target == null) {
                        Chat.error(sender, "errors.restore-target-offline");
                        break;
                    }
                    restoreService.restoreToPlayer(sender, target, backupId);
                }
            }
            case PENDING -> {
                if (ensurePermission(sender, Permissions.PENDING)) {
                    if (!(sender instanceof Player player)) {
                        Chat.error(sender, "errors.console-no-inventory");
                        break;
                    }
                    if (ensureStoreReady(player, label)) {
                        guiService.deliverPending(player);
                    }
                }
            }
            case LIST -> {
                if (ensurePermission(sender, Permissions.LIST) && ensureStoreReady(sender, label)) {
                    if (args.length < 2) {
                        Chat.error(sender, "errors.usage-list", Placeholder.unparsed("label", label));
                        break;
                    }
                    ResolvedTarget target = resolveTargetOrError(sender, args[1]);
                    if (target == null) {
                        break;
                    }

                    int page = parsePositiveIntOrDefault(args.length >= 3 ? args[2] : null);
                    int offset = (page - 1) * LIST_PAGE_SIZE;
                    @SuppressWarnings("resource")
                    var store = plugin.store();

                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        List<BackupMeta> backups;
                        try {
                            backups = store.listBackups(target.uuid(), offset, LIST_PAGE_SIZE);
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.lang().plain(
                                    "console.command.list-load-failed",
                                    Placeholder.unparsed("uuid", target.uuid().toString()),
                                    Placeholder.unparsed("page", String.valueOf(page)),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            ));
                            runOnSender(sender, () -> Chat.error(sender, "errors.load-failed"));
                            return;
                        }

                        runOnSender(sender, () -> {
                            Chat.plain(
                                    sender,
                                    "list.title",
                                    Placeholder.unparsed("target", target.name()),
                                    Placeholder.unparsed("page", String.valueOf(page))
                            );
                            if (backups.isEmpty()) {
                                Chat.plain(sender, "list.empty");
                                return;
                            }
                            for (BackupMeta meta : backups) {
                                String time = timeFormatter.format(Instant.ofEpochMilli(meta.createdAtMillis()));
                                String locked = plugin.lang().raw(meta.locked() ? "common.yes_text" : "common.no_text");
                                String note = meta.note() == null || meta.note().isBlank()
                                        ? plugin.lang().raw("common.none")
                                        : meta.note();
                                Chat.plain(
                                        sender,
                                        "list.line",
                                        Placeholder.unparsed("time", time),
                                        Placeholder.unparsed("id", meta.backupId()),
                                        Placeholder.unparsed("trigger", plugin.lang().raw(meta.trigger().langKey())),
                                        Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                                        Placeholder.unparsed("locked", locked),
                                        Placeholder.unparsed("note", note)
                                );
                            }
                        });
                    });
                }
            }
            case INFO -> {
                if (ensurePermission(sender, Permissions.INFO) && ensureStoreReady(sender, label)) {
                    if (args.length < 3) {
                        Chat.error(sender, "errors.usage-info", Placeholder.unparsed("label", label));
                        break;
                    }
                    ResolvedTarget target = resolveTargetOrError(sender, args[1]);
                    if (target == null) {
                        break;
                    }

                    String backupId = args[2];
                    @SuppressWarnings("resource")
                    var store = plugin.store();

                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        BackupRecord record;
                        List<SlotClaim> claims;
                        try {
                            record = store.loadBackup(target.uuid(), backupId).orElse(null);
                            if (record == null) {
                                runOnSender(sender, () -> Chat.error(sender, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                                return;
                            }
                            claims = store.listClaims(target.uuid(), backupId);
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.lang().plain(
                                    "console.command.info-load-failed",
                                    Placeholder.unparsed("uuid", target.uuid().toString()),
                                    Placeholder.unparsed("backup_id", backupId),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            ));
                            runOnSender(sender, () -> Chat.error(sender, "errors.read-failed"));
                            return;
                        }

                        BackupMeta meta = record.meta();
                        String time = timeFormatter.format(Instant.ofEpochMilli(meta.createdAtMillis()));
                        int claimedCount = claims == null ? 0 : claims.size();
                        String locked = plugin.lang().raw(meta.locked() ? "common.yes_text" : "common.no_text");
                        String note = meta.note() == null || meta.note().isBlank()
                                ? plugin.lang().raw("common.none")
                                : meta.note();
                        runOnSender(sender, () -> {
                            Chat.plain(
                                    sender,
                                    "info.title",
                                    Placeholder.unparsed("target", target.name()),
                                    Placeholder.unparsed("id", meta.backupId())
                            );
                            Chat.plainList(
                                    sender,
                                    "info.lines",
                                    Placeholder.unparsed("time", time),
                                    Placeholder.unparsed("trigger", plugin.lang().raw(meta.trigger().langKey())),
                                    Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                                    Placeholder.unparsed("sha256", meta.sha256Hex()),
                                    Placeholder.unparsed("schema", String.valueOf(meta.schemaVersion())),
                                    Placeholder.unparsed("claimed", String.valueOf(claimedCount)),
                                    Placeholder.unparsed("locked", locked),
                                    Placeholder.unparsed("note", note)
                            );
                        });
                    });
                }
            }
            case LOCK -> {
                if (ensurePermission(sender, Permissions.LOCK) && ensureStoreReady(sender, label)) {
                    if (args.length < 3) {
                        Chat.error(sender, "errors.usage-lock", Placeholder.unparsed("label", label));
                        break;
                    }
                    ResolvedTarget target = resolveTargetOrError(sender, args[1]);
                    if (target == null) {
                        break;
                    }

                    String backupId = args[2];
                    String note = args.length >= 4 ? joinArgs(args) : null;
                    if (note != null && note.length() > MAX_NOTE_LENGTH) {
                        Chat.error(sender, "errors.note-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NOTE_LENGTH)));
                        break;
                    }

                    @SuppressWarnings("resource")
                    var store = plugin.store();
                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        try {
                            boolean ok = store.setBackupLocked(target.uuid(), backupId, true);
                            if (!ok) {
                                runOnSender(sender, () -> Chat.error(sender, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                                return;
                            }
                            if (note != null) {
                                store.setBackupNote(target.uuid(), backupId, note);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.lang().plain(
                                    "console.command.lock-failed",
                                    Placeholder.unparsed("uuid", target.uuid().toString()),
                                    Placeholder.unparsed("backup_id", backupId),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            ));
                            runOnSender(sender, () -> Chat.error(sender, "errors.read-failed"));
                            return;
                        }

                        plugin.auditService().log("LOCK_BACKUP", sender, target.uuid(), target.name(), backupId, "note=" + (note == null ? "" : note));
                        runOnSender(sender, () -> Chat.success(sender, "success.backup-locked", Placeholder.unparsed("backup_id", backupId)));
                    });
                }
            }
            case UNLOCK -> {
                if (ensurePermission(sender, Permissions.LOCK) && ensureStoreReady(sender, label)) {
                    if (args.length < 3) {
                        Chat.error(sender, "errors.usage-unlock", Placeholder.unparsed("label", label));
                        break;
                    }
                    ResolvedTarget target = resolveTargetOrError(sender, args[1]);
                    if (target == null) {
                        break;
                    }

                    String backupId = args[2];
                    @SuppressWarnings("resource")
                    var store = plugin.store();

                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        boolean ok;
                        try {
                            ok = store.setBackupLocked(target.uuid(), backupId, false);
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.lang().plain(
                                    "console.command.unlock-failed",
                                    Placeholder.unparsed("uuid", target.uuid().toString()),
                                    Placeholder.unparsed("backup_id", backupId),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            ));
                            runOnSender(sender, () -> Chat.error(sender, "errors.read-failed"));
                            return;
                        }

                        if (!ok) {
                            runOnSender(sender, () -> Chat.error(sender, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                            return;
                        }
                        plugin.auditService().log("UNLOCK_BACKUP", sender, target.uuid(), target.name(), backupId, null);
                        runOnSender(sender, () -> Chat.success(sender, "success.backup-unlocked", Placeholder.unparsed("backup_id", backupId)));
                    });
                }
            }
            case NOTE -> {
                if (ensurePermission(sender, Permissions.LOCK) && ensureStoreReady(sender, label)) {
                    if (args.length < 3) {
                        Chat.error(sender, "errors.usage-note", Placeholder.unparsed("label", label));
                        break;
                    }
                    ResolvedTarget target = resolveTargetOrError(sender, args[1]);
                    if (target == null) {
                        break;
                    }

                    String backupId = args[2];
                    String note = args.length >= 4 ? joinArgs(args) : "";
                    if (note.length() > MAX_NOTE_LENGTH) {
                        Chat.error(sender, "errors.note-too-long", Placeholder.unparsed("max", String.valueOf(MAX_NOTE_LENGTH)));
                        break;
                    }
                    @SuppressWarnings("resource")
                    var store = plugin.store();

                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        boolean ok;
                        try {
                            ok = store.setBackupNote(target.uuid(), backupId, note);
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.lang().plain(
                                    "console.command.note-failed",
                                    Placeholder.unparsed("uuid", target.uuid().toString()),
                                    Placeholder.unparsed("backup_id", backupId),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            ));
                            runOnSender(sender, () -> Chat.error(sender, "errors.read-failed"));
                            return;
                        }

                        if (!ok) {
                            runOnSender(sender, () -> Chat.error(sender, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                            return;
                        }
                        plugin.auditService().log("NOTE_BACKUP", sender, target.uuid(), target.name(), backupId, "note=" + note);
                        runOnSender(sender, () -> Chat.success(sender, "success.backup-note-set", Placeholder.unparsed("backup_id", backupId)));
                    });
                }
            }
        }

        return true;
    }


    private void sendHelp(CommandSender sender, String label) {
        Chat.plain(sender, "help.header", Placeholder.unparsed("version", pluginVersion()));
        Chat.plainList(sender, "help.lines", Placeholder.unparsed("label", label));
        Chat.plainList(sender, "help.commands", Placeholder.unparsed("label", label));
        Chat.plain(sender, "help.example", Placeholder.unparsed("label", label));
    }

    private String storageName() {
        return switch (plugin.pluginConfig().storageType()) {
            case SQLITE -> plugin.lang().raw("storage.name.sqlite");
            case LOCAL -> plugin.lang().raw("storage.name.local");
            case MYSQL -> plugin.lang().raw("storage.name.mysql");
            case H2 -> plugin.lang().raw("storage.name.h2");
        };
    }

    private String storagePath() {
        var cfg = plugin.pluginConfig();
        return switch (cfg.storageType()) {
            case SQLITE -> plugin.getDataFolder().toPath().resolve(cfg.sqliteFile()).toString();
            case LOCAL -> plugin.getDataFolder().toPath().resolve(cfg.localBasePath()).toString();
            case MYSQL -> cfg.mysql().host() + ":" + cfg.mysql().port() + "/" + cfg.mysql().database();
            case H2 -> plugin.getDataFolder().toPath().resolve(cfg.h2().file()).toString();
        };
    }

    @SuppressWarnings("UnstableApiUsage")
    private String pluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!Permissions.has(sender, Permissions.ADMIN)) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixMatches(args[0], Subcommand.topLevelTokensFor(sender));
        }
        if (args.length == 2) {
            Subcommand sub = Subcommand.resolve(args[0]);
            if (sub != null && sub.availableTo(sender) && sub.permittedFor(sender) && sub.suggestOnlinePlayersOnSecondArg()) {
                return prefixMatches(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }
        }
        return List.of();
    }

    private void queueManualBackup(CommandSender sender, String label, Player target, String auditAction) {
        target.getScheduler().run(plugin, ignored -> {
            var backupService = plugin.backupService();
            if (!plugin.isStoreReady() || backupService == null) {
                runOnSender(sender, () -> Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label)));
                return;
            }
            boolean queued = backupService.requestBackup(target, TriggerType.MANUAL);
            if (queued) {
                runOnSender(sender, () -> Chat.success(sender, "success.backup-queued", Placeholder.unparsed("player", target.getName())));
                plugin.auditService().log(auditAction, sender, target.getUniqueId(), target.getName(), null, "queued=true");
            } else {
                runOnSender(sender, () -> Chat.error(sender, "errors.backup-queue-full"));
                plugin.auditService().log(auditAction, sender, target.getUniqueId(), target.getName(), null, "queued=false");
            }
        }, null);
    }

    private void queueManualBackupAll(CommandSender sender, String label) {
        List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) {
            Chat.error(sender, "errors.no-online-players");
            return;
        }

        var backupService = plugin.backupService();
        if (!plugin.isStoreReady() || backupService == null) {
            Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", label));
            return;
        }

        AtomicInteger queued = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(targets.size());

        for (Player target : targets) {
            target.getScheduler().run(
                    plugin,
                    ignored -> {
                        boolean queuedThisTime = target.isOnline() && plugin.isStoreReady() && plugin.backupService() != null
                                && plugin.backupService().requestBackup(target, TriggerType.MANUAL);
                        finishManualBackupAllOne(sender, target, queuedThisTime, queued, skipped, remaining);
                    },
                    () -> finishManualBackupAllOne(sender, target, false, queued, skipped, remaining)
            );
        }
    }

    private void finishManualBackupAllOne(
            CommandSender sender,
            Player target,
            boolean queuedThisTime,
            AtomicInteger queued,
            AtomicInteger skipped,
            AtomicInteger remaining
    ) {
        if (queuedThisTime) {
            queued.incrementAndGet();
        } else {
            skipped.incrementAndGet();
        }

        plugin.auditService().log(
                "MANUAL_BACKUP_ALL",
                sender,
                target.getUniqueId(),
                target.getName(),
                null,
                "queued=" + queuedThisTime
        );

        if (remaining.decrementAndGet() != 0) {
            return;
        }

        runOnSender(sender, () -> Chat.success(
                sender,
                "success.backupall-submitted",
                Placeholder.unparsed("queued", String.valueOf(queued.get())),
                Placeholder.unparsed("skipped", String.valueOf(skipped.get()))
        ));
    }

    private boolean ensurePermission(CommandSender sender, String requiredPermission) {
        if (Permissions.has(sender, requiredPermission)) {
            return true;
        }
        Chat.error(sender, "errors.no-permission", Placeholder.unparsed("perm", requiredPermission));
        return false;
    }

    private boolean ensureStoreReady(CommandSender sender, String label) {
        if (plugin.isStoreReady()) {
            return true;
        }
        String safeLabel = label == null || label.isBlank() ? "playerinvbackup" : label;
        Chat.error(sender, "errors.store-unavailable", Placeholder.unparsed("label", safeLabel));
        return false;
    }

    private ResolvedTarget resolveTargetOrError(CommandSender sender, String token) {
        ResolvedTarget target = resolveTarget(token);
        if (target == null) {
            Chat.error(sender, "errors.offline-not-cached");
        }
        return target;
    }

	    private ResolvedTarget resolveTarget(String token) {
	        if (token == null || token.isBlank()) {
	            return null;
        }

        UUID uuid = tryParseUuid(token);
        if (uuid != null) {
            Player online = Bukkit.getPlayer(uuid);
            String name = online != null ? online.getName() : uuid.toString();
            return new ResolvedTarget(uuid, name);
        }

	        Player online = findOnlinePlayerByName(token);
	        if (online != null) {
	            return new ResolvedTarget(online.getUniqueId(), online.getName());
	        }

	        // offline-mode 服务器没有正版档案查询, 可以直接按名字计算离线 UUID
	        if (!Bukkit.getOnlineMode()) {
	            UUID offlineUuid = offlineUuidForName(token);
	            return new ResolvedTarget(offlineUuid, token);
	        }
	
	        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(token);
	        if (cached == null) {
	            return null;
	        }
        UUID cachedUuid = cached.getUniqueId();
        String name = cached.getName() == null ? cachedUuid.toString() : cached.getName();
        return new ResolvedTarget(cachedUuid, name);
    }

    private static int parsePositiveIntOrDefault(String token) {
        int fallback = 1;
        if (token == null || token.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(token.trim());
            return v <= 0 ? fallback : v;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String joinArgs(String[] args) {
        int startIndex = 3;
        if (args == null || startIndex >= args.length) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            String part = args[i];
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part);
        }
        return out.toString();
    }

    private static List<String> prefixMatches(String token, List<String> candidates) {
        String lower = token == null ? "" : token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c == null) {
                continue;
            }
            if (lower.isEmpty() || c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c);
            }
        }
        return out;
    }

	    private static UUID tryParseUuid(String token) {
	        try {
	            return UUID.fromString(token);
	        } catch (IllegalArgumentException ignored) {
	            return null;
	        }
	    }

	    private static UUID offlineUuidForName(String name) {
	        String safeName = name == null ? "" : name.trim();
	        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + safeName).getBytes(StandardCharsets.UTF_8));
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

    private static Player findOnlinePlayerByNameOrUuid(String token) {
        UUID uuid = tryParseUuid(token);
        if (uuid != null) {
            return Bukkit.getPlayer(uuid);
        }
        return findOnlinePlayerByName(token);
    }

    private static Player findOnlinePlayerByName(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(token);
        if (exact != null) {
            return exact;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return player;
            }
        }
        return null;
    }

    private void sendStatus(CommandSender sender) {
        var cfg = plugin.pluginConfig();
        @SuppressWarnings("resource")
        var dispatcher = plugin.ioDispatcher();
        String none = plugin.lang().raw("common.none");

        String storageName = storageName();
        String storagePath = storagePath();
        String backupScope = plugin.lang().raw("status.scope-value");

        Chat.plain(sender, "status.title");
        Chat.plain(sender, "status.permission", Placeholder.unparsed("perm", Permissions.ADMIN));
        Chat.plain(
                sender,
                "status.config",
                Placeholder.unparsed("path", plugin.getDataFolder().toPath().resolve("config.yml").toString())
        );
        Chat.plain(sender, "status.scope", Placeholder.unparsed("scope", backupScope));
        Chat.plain(
                sender,
                "status.current-config",
                Placeholder.unparsed("interval", String.valueOf(cfg.backupInterval().toMinutes())),
                Placeholder.unparsed("jitter", String.valueOf(cfg.jitter().toSeconds())),
                Placeholder.unparsed("keep", String.valueOf(cfg.keepPerPlayer())),
                Placeholder.unparsed("keep_days", String.valueOf(cfg.keepDuration().toDays()))
        );
        Chat.plain(
                sender,
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
                sender,
                "status.gui-mode",
                Placeholder.unparsed("mode", configuredGuiMode),
                Placeholder.unparsed("active", activeGuiMode)
        );
        if (!plugin.isStoreReady()) {
            String reason = plugin.storeInitFailedReason();
            String reasonText = reason == null || reason.isBlank() ? none : reason;
            Chat.plain(sender, "status.store-unavailable", Placeholder.unparsed("reason", reasonText));
        }
        String yesText = plugin.lang().raw("common.yes_text");
        String noText = plugin.lang().raw("common.no_text");
        Chat.plain(sender, "status.audit-enabled", Placeholder.unparsed("enabled", cfg.auditEnabled() ? yesText : noText));
        Chat.plain(sender, "status.audit-console", Placeholder.unparsed("enabled", cfg.auditConsole() ? yesText : noText));
        Chat.plain(sender, "status.audit-keep-days", Placeholder.unparsed("days", String.valueOf(cfg.auditKeepDays())));
        Chat.plain(
                sender,
                "status.io-queue",
                Placeholder.unparsed("size", dispatcher == null ? none : String.valueOf(dispatcher.queueSize())),
                Placeholder.unparsed("limit", dispatcher == null ? none : String.valueOf(dispatcher.queueLimit())),
                Placeholder.unparsed("remaining", dispatcher == null ? none : String.valueOf(dispatcher.queueRemainingCapacity()))
        );

        switch (cfg.storageType()) {
            case SQLITE -> {
                Path db = plugin.getDataFolder().toPath().resolve(cfg.sqliteFile());
                if (sender instanceof Player player) {
                    Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                        long size = tryGetFileSize(db);
                        runOnSender(player, () -> {
                            if (size >= 0) {
                                Chat.plain(player, "status.sqlite-size", Placeholder.unparsed("size", formatFileSize(size)));
                            } else {
                                Chat.plain(player, "status.sqlite-size-unknown");
                            }
                        });
                    });
                } else {
                    long size = tryGetFileSize(db);
                    if (size >= 0) {
                        Chat.plain(sender, "status.sqlite-size", Placeholder.unparsed("size", formatFileSize(size)));
                    } else {
                        Chat.plain(sender, "status.sqlite-size-unknown");
                    }
                }
            }
            case LOCAL -> {
            }
            case MYSQL -> {
            }
            case H2 -> {
                Path base = plugin.getDataFolder().toPath().resolve(cfg.h2().file());
                Path db = Path.of(base + ".mv.db");

                long size = tryGetFileSize(db);
                if (size >= 0) {
                    Chat.plain(sender, "status.h2-size", Placeholder.unparsed("size", formatFileSize(size)));
                } else {
                    Chat.plain(sender, "status.h2-size-unknown");
                }
            }
        }
    }

    private void runOnSender(CommandSender sender, Runnable runnable) {
        if (sender == null) {
            return;
        }
        if (sender instanceof Player player) {
            if (!player.isOnline()) {
                return;
            }
            player.getScheduler().run(plugin, ignored -> runnable.run(), null);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }
}
