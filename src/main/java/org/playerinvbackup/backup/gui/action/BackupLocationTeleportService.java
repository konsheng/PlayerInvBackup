package org.playerinvbackup.backup.gui.action;

import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.BackupLocationTeleportSettings;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.text.Chat;

/**
 * 执行备份位置传送命令
 */
public final class BackupLocationTeleportService {
    private final PlayerInvBackupPlugin plugin;

    public BackupLocationTeleportService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    public void teleport(Player admin, BackupViewHolder holder) {
        if (admin == null || holder == null || !admin.isOnline()) {
            return;
        }
        if (holder.viewOnly()) {
            Chat.error(admin, "errors.teleport-view-only");
            return;
        }
        if (!Permissions.has(admin, Permissions.TELEPORT)) {
            Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.TELEPORT));
            return;
        }

        BackupLocationTeleportSettings settings = teleportSettings();
        if (settings == null || !settings.enabled()) {
            Chat.error(admin, "errors.teleport-disabled");
            return;
        }
        if (!settings.hasCommand()) {
            Chat.error(admin, "errors.teleport-command-empty");
            return;
        }
        if (!hasLocation(holder)) {
            Chat.error(admin, "errors.teleport-location-unavailable");
            return;
        }

        String command = buildCommand(settings.command(), admin, holder);
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> executeCommand(admin, holder, command));
    }

    private void executeCommand(Player admin, BackupViewHolder holder, String command) {
        boolean dispatched;
        try {
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException e) {
            logCommandFailure(admin, holder, command, String.valueOf(e.getMessage()), e);
            runOnPlayer(admin, () -> Chat.error(admin, "errors.teleport-command-failed"));
            return;
        }

        if (!dispatched) {
            logCommandFailure(admin, holder, command, "dispatch_returned_false", null);
            runOnPlayer(admin, () -> Chat.error(admin, "errors.teleport-command-failed"));
            return;
        }

        audit(admin, holder, command);
        runOnPlayer(admin, () -> Chat.success(admin, "success.teleport-command-executed"));
    }

    private void logCommandFailure(
            Player admin,
            BackupViewHolder holder,
            String command,
            String reason,
            Throwable throwable
    ) {
        String message = plugin.lang().plain(
                "console.gui.teleport-command-failed",
                Placeholder.unparsed("actor", admin.getName()),
                Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                Placeholder.unparsed("backup_id", holder.backupId()),
                Placeholder.unparsed("command", command),
                Placeholder.unparsed("reason", reason)
        );
        if (throwable == null) {
            plugin.getLogger().warning(message);
            return;
        }
        plugin.getLogger().log(Level.SEVERE, message, throwable);
    }

    private String buildCommand(String template, Player admin, BackupViewHolder holder) {
        String server = safeServer(holder.serverId());
        String serverDisplay = displayServerName(server);
        String target = holder.targetName() == null || holder.targetName().isBlank()
                ? holder.targetUuid().toString()
                : holder.targetName();

        return template
                .replace("<player>", admin.getName())
                .replace("<player_uuid>", admin.getUniqueId().toString())
                .replace("<target>", target)
                .replace("<target_uuid>", holder.targetUuid().toString())
                .replace("<server>", server)
                .replace("<server_display>", serverDisplay)
                .replace("<world>", holder.worldName().trim())
                .replace("<x>", coordinate(holder.locationX()))
                .replace("<y>", coordinate(holder.locationY()))
                .replace("<z>", coordinate(holder.locationZ()))
                .replace("<backup_id>", holder.backupId());
    }

    private void audit(Player admin, BackupViewHolder holder, String command) {
        String server = safeServer(holder.serverId());
        String serverDisplay = displayServerName(server);
        plugin.auditService().log(
                "TELEPORT_TO_BACKUP_LOCATION",
                admin,
                holder.targetUuid(),
                holder.targetName(),
                holder.backupId(),
                "mode=" + holder.guiMode().name()
                        + " server=" + server
                        + " server_display=" + serverDisplay
                        + " world=" + holder.worldName()
                        + " x=" + coordinate(holder.locationX())
                        + " y=" + coordinate(holder.locationY())
                        + " z=" + coordinate(holder.locationZ())
                        + " command=" + command
        );
    }

    private BackupLocationTeleportSettings teleportSettings() {
        var config = plugin.pluginConfig();
        return config == null ? null : config.teleport();
    }

    private String displayServerName(String serverId) {
        var config = plugin.pluginConfig();
        if (config == null) {
            return serverId;
        }
        return config.displayServerName(serverId);
    }

    private static String safeServer(String serverId) {
        return serverId == null || serverId.isBlank() ? "default" : serverId.trim();
    }

    private static boolean hasLocation(BackupViewHolder holder) {
        return holder.worldName() != null
                && !holder.worldName().isBlank()
                && isFinite(holder.locationX())
                && isFinite(holder.locationY())
                && isFinite(holder.locationZ());
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static String coordinate(Double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }
}
