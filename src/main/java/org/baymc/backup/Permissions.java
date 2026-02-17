package org.baymc.backup;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

/**
 * 权限节点常量与校验工具
 *
 * <p>约定:
 * 1) 控制台默认拥有全部权限
 * 2) 拥有 {@link #ADMIN} 的玩家视为拥有全部子权限
 */
public final class Permissions {
    public static final String ADMIN = "baymcbackup.admin";

    public static final String OPEN = "baymcbackup.open";
    public static final String NOW = "baymcbackup.now";
    public static final String NOWALL = "baymcbackup.nowall";
    public static final String SELF_BACKUP = "baymcbackup.self";
    public static final String RESTORE = "baymcbackup.restore";
    public static final String PENDING = "baymcbackup.pending";
    public static final String STATUS = "baymcbackup.status";
    public static final String RELOAD = "baymcbackup.reload";

    public static final String LIST = "baymcbackup.list";
    public static final String INFO = "baymcbackup.info";

    public static final String LOCK = "baymcbackup.lock";

    // 玩家用于跳过自动备份的权限 (定时/事件触发)
    public static final String BACKUP_EXEMPT = "baymcbackup.backup.exempt";

    private Permissions() {
    }

    public static boolean has(CommandSender sender, String permission) {
        if (sender == null) {
            return false;
        }
        if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            return true;
        }
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return sender.hasPermission(permission);
    }
}
