package org.playerinvbackup.backup.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 插件配置快照
 *
 * <p>从 {@code config.yml} 解析为不可变对象, 便于在运行时安全读取
 */
public record PluginConfig(
        Duration backupInterval,
        Duration jitter,
        int keepPerPlayer,
        Duration keepDuration,
        boolean auditEnabled,
        boolean auditConsole,
        int auditKeepDays,
        StorageType storageType,
        Path localBasePath,
        Path sqliteFile,
        MysqlConfig mysql,
        H2Config h2,
        int queueLimit,
        double maxWritesPerSecond,
        int guiListPageSize,
        List<GuiTimeFilterOption> guiTimeFilters,
        GuiMode guiMode,
        String languageFile,
        DateTimeFormatter backupTimeFormatter,
        boolean backupOnJoin,
        boolean backupOnQuit,
        boolean backupOnDeath,
        boolean backupOnWorldChange,
        Set<String> excludedWorlds,
        boolean guiSoundsEnabled,
        SoundEffect guiClickSound,
        GuiButtonSounds guiButtonSounds
) {
    public static PluginConfig from(Plugin plugin, Lang lang, FileConfiguration config) {
        var intervalMinutes = Math.max(0, config.getLong("backup.interval-minutes", 30));
        var jitterSeconds = Math.max(0, config.getLong("backup.jitter-seconds", 300));
        var keepPerPlayer = Math.max(0, config.getInt("backup.keep-per-player", 50));
        var keepDays = Math.max(0, config.getLong("backup.keep-days", 0));
        var keepDuration = Duration.ofDays(keepDays);

        boolean auditEnabled = config.getBoolean("audit.enabled", true);
        boolean auditConsole = config.getBoolean("audit.console", true);
        int auditKeepDays = Math.max(0, config.getInt("audit.keep-days", 30));

        String languageFile = config.getString("language", "zh_CN.yml");
        if (languageFile == null || languageFile.isBlank()) {
            languageFile = "zh_CN.yml";
        } else {
            languageFile = languageFile.trim();
        }

        String backupTimeFormatRaw = config.getString("display.backup-time-format", "yyyy-MM-dd HH:mm:ss");
        if (backupTimeFormatRaw == null || backupTimeFormatRaw.isBlank()) {
            backupTimeFormatRaw = "yyyy-MM-dd HH:mm:ss";
        } else {
            backupTimeFormatRaw = backupTimeFormatRaw.trim();
        }
        DateTimeFormatter backupTimeFormatter;
        try {
            backupTimeFormatter = DateTimeFormatter.ofPattern(backupTimeFormatRaw).withZone(ZoneId.systemDefault());
        } catch (IllegalArgumentException e) {
            backupTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
            plugin.getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.config.backup-time-format-invalid",
                            Placeholder.unparsed("value", backupTimeFormatRaw)
                    )
            );
        }

        var storageType = StorageType.fromConfigValue(config.getString("storage.type", "sqlite"));
        var localBasePath = Path.of(config.getString("storage.local.base-path", "data"));
        var sqliteFile = Path.of(config.getString("storage.sqlite.file", "data/backups.db"));
        MysqlConfig mysql = MysqlConfig.from(config);
        H2Config h2 = H2Config.from(config);

        var queueLimit = Math.max(1, config.getInt("performance.queue-limit", 500));
        var maxWritesPerSecond = Math.max(0.1, config.getDouble("performance.max-writes-per-second", 20));

        var guiListPageSize = Math.min(45, Math.max(9, config.getInt("gui.list-page-size", 45)));
        List<GuiTimeFilterOption> guiTimeFilters = GuiTimeFilterOption.fromConfig(
                plugin,
                lang,
                config,
                "gui.backup-list.time-filters"
        );

        String guiModeRaw = config.getString("gui.mode", "auto");
        if (guiModeRaw != null) {
            guiModeRaw = guiModeRaw.trim();
        }
        GuiMode guiMode = GuiMode.parseOrNull(guiModeRaw);
        if (guiMode == null) {
            guiMode = GuiMode.AUTO;
            if (guiModeRaw != null && !guiModeRaw.isBlank()) {
                plugin.getLogger().log(
                        Level.WARNING,
                        lang.plain(
                                "console.config.gui-mode-invalid",
                                Placeholder.unparsed("value", guiModeRaw)
                        )
                );
            }
        }

        boolean backupOnJoin = config.getBoolean("backup.triggers.join", true);
        boolean backupOnQuit = config.getBoolean("backup.triggers.quit", true);
        boolean backupOnDeath = config.getBoolean("backup.triggers.death", true);
        boolean backupOnWorldChange = config.getBoolean("backup.triggers.world-change", true);

        Set<String> excludedWorlds = new HashSet<>();
        for (String world : config.getStringList("backup.excluded-worlds")) {
            if (world == null) {
                continue;
            }
            String trimmed = world.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            excludedWorlds.add(trimmed.toLowerCase(Locale.ROOT));
        }
        excludedWorlds = Set.copyOf(excludedWorlds);

        boolean guiSoundsEnabled = config.getBoolean("sounds.gui.enabled", true);
        SoundEffect guiClickSound = SoundEffect.fromConfig(
                plugin,
                lang,
                config,
                "sounds.gui.click",
                "UI_BUTTON_CLICK",
                1.0f,
                1.0f
        );
        GuiButtonSounds guiButtonSounds = GuiButtonSounds.from(plugin, lang, config, guiClickSound);

        return new PluginConfig(
                Duration.ofMinutes(intervalMinutes),
                Duration.ofSeconds(jitterSeconds),
                keepPerPlayer,
                keepDuration,
                auditEnabled,
                auditConsole,
                auditKeepDays,
                storageType,
                localBasePath,
                sqliteFile,
                mysql,
                h2,
                queueLimit,
                maxWritesPerSecond,
                guiListPageSize,
                guiTimeFilters,
                guiMode,
                languageFile,
                backupTimeFormatter,
                backupOnJoin,
                backupOnQuit,
                backupOnDeath,
                backupOnWorldChange,
                excludedWorlds,
                guiSoundsEnabled,
                guiClickSound,
                guiButtonSounds
        );
    }
}
