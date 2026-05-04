package org.playerinvbackup.backup.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 备份位置传送命令配置
 */
public record BackupLocationTeleportSettings(boolean enabled, String command) {
    private static final String DEFAULT_COMMAND = "tp <player> <x> <y> <z>";

    public BackupLocationTeleportSettings {
        command = command == null ? "" : command.trim();
    }

    public static BackupLocationTeleportSettings fromConfig(FileConfiguration config) {
        if (config == null) {
            return new BackupLocationTeleportSettings(true, DEFAULT_COMMAND);
        }
        boolean enabled = config.getBoolean("teleport.enabled", true);
        String command = config.getString("teleport.command", DEFAULT_COMMAND);
        return new BackupLocationTeleportSettings(enabled, command);
    }

    public boolean hasCommand() {
        return command != null && !command.isBlank();
    }
}
