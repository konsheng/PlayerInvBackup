package org.playerinvbackup.backup.util;

import java.util.Locale;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;

/**
 * 备份位置显示格式化工具
 */
public final class BackupLocationFormatter {
    private static final String ROUTE_SEPARATOR = " \u2192 ";

    private BackupLocationFormatter() {
    }

    public static String displayWorld(
            PlayerInvBackupPlugin plugin,
            String worldName,
            String targetWorldName
    ) {
        String source = displaySingleWorld(plugin, worldName);
        if (targetWorldName == null || targetWorldName.isBlank()) {
            return source;
        }
        return source + ROUTE_SEPARATOR + displaySingleWorld(plugin, targetWorldName);
    }

    public static String displayPosition(
            PlayerInvBackupPlugin plugin,
            Double x,
            Double y,
            Double z,
            Double targetX,
            Double targetY,
            Double targetZ
    ) {
        String source = displaySinglePosition(plugin, x, y, z);
        if (!hasPosition(targetX, targetY, targetZ)) {
            return source;
        }
        return source + ROUTE_SEPARATOR + displaySinglePosition(plugin, targetX, targetY, targetZ);
    }

    private static String displaySingleWorld(PlayerInvBackupPlugin plugin, String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return plugin.lang().raw("common.none");
        }
        var config = plugin.pluginConfig();
        return config == null ? worldName : config.displayWorldName(worldName);
    }

    private static String displaySinglePosition(PlayerInvBackupPlugin plugin, Double x, Double y, Double z) {
        if (!hasPosition(x, y, z)) {
            return plugin.lang().raw("common.none");
        }
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", x, y, z);
    }

    private static boolean hasPosition(Double x, Double y, Double z) {
        return x != null && y != null && z != null;
    }
}
