package org.playerinvbackup.backup.config;

import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

public record UpdateCheckerSettings(
        boolean enabled,
        Duration checkInterval,
        boolean notifyAdmins
) {
    private static final long DEFAULT_CHECK_INTERVAL_HOURS = 24L;

    public static UpdateCheckerSettings fromConfig(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        long checkIntervalHours = Math.max(
                1L,
                config.getLong("update-checker.check-interval-hours", DEFAULT_CHECK_INTERVAL_HOURS)
        );
        return new UpdateCheckerSettings(
                config.getBoolean("update-checker.enabled", true),
                Duration.ofHours(checkIntervalHours),
                config.getBoolean("update-checker.notify-admins", true)
        );
    }

    public static UpdateCheckerSettings defaults() {
        return new UpdateCheckerSettings(true, Duration.ofHours(DEFAULT_CHECK_INTERVAL_HOURS), true);
    }
}
