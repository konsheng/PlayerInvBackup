package org.playerinvbackup.backup.command;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.command.handler.AdminHandler;
import org.playerinvbackup.backup.command.handler.BackupActionHandler;
import org.playerinvbackup.backup.command.handler.BrowseHandler;
import org.playerinvbackup.backup.command.handler.MetadataHandler;
import org.playerinvbackup.backup.command.handler.QueryHandler;
import org.playerinvbackup.backup.command.handler.RestoreHandler;
import org.playerinvbackup.backup.command.support.CommandAsync;
import org.playerinvbackup.backup.command.support.CommandGuards;
import org.playerinvbackup.backup.command.support.CommandSuggestions;
import org.playerinvbackup.backup.command.support.TargetResolver;
import org.playerinvbackup.backup.config.SoundEffect;

/**
 * Bukkit 命令入口
 *
 * <p>只负责装配 dispatcher, 不再承载具体子命令逻辑
 */
public final class BackupCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DEFAULT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PlayerInvBackupPlugin plugin;
    private final BackupCommandDispatcher dispatcher;
    private final BackupActionHandler backupActionHandler;

    public BackupCommand(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;

        CommandSuggestions suggestions = new CommandSuggestions();
        CommandGuards guards = new CommandGuards(plugin::isStoreReady);
        CommandAsync async = new CommandAsync(plugin, plugin.getLogger(), plugin.lang());
        TargetResolver targetResolver = new TargetResolver();
        DateTimeFormatter timeFormatter = plugin.pluginConfig() == null
                ? DEFAULT_TIME_FORMAT
                : plugin.pluginConfig().backupTimeFormatter();

        this.backupActionHandler = new BackupActionHandler(plugin, guards, async, targetResolver, suggestions);

        List<SubcommandHandler> handlers = List.of(
                new AdminHandler(
                        plugin::getName,
                        () -> plugin.getPluginMeta().getVersion(),
                        () -> {
                            var config = plugin.pluginConfig();
                            return config == null ? SoundEffect.disabled() : config.helpCommandClickSound();
                        },
                        plugin::reload,
                        plugin::isEnabled,
                        plugin::isStoreReady,
                        plugin::lastReloadCancelledBackupTargets,
                        plugin::lastReloadDiscardedIoTasks,
                        guards,
                        suggestions,
                        plugin.getLogger(),
                        plugin.lang()
                ),
                new MetadataHandler(plugin.store(), plugin.auditService(), guards, async, targetResolver, suggestions),
                new QueryHandler(plugin, guards, async, targetResolver, suggestions, timeFormatter),
                backupActionHandler,
                new BrowseHandler(plugin.guiService(), guards, targetResolver, suggestions),
                new RestoreHandler(plugin.restoreService(), guards, targetResolver, suggestions)
        );

        this.dispatcher = new BackupCommandDispatcher(handlers, suggestions);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return dispatcher.onCommand(new CommandContext(plugin, sender, command, label, args));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        return dispatcher.onTabComplete(new CommandContext(plugin, sender, command, alias, args));
    }

    public int cancelActiveOperationsForReload() {
        return backupActionHandler.cancelActiveBackupAllForReload();
    }
}
