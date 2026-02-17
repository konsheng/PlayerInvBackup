package org.baymc.backup.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.baymc.backup.text.Lang;
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
        StorageType storageType,
        Path localBasePath,
        Path sqliteFile,
        MysqlConfig mysql,
        H2Config h2,
        int queueLimit,
        double maxWritesPerSecond,
        int guiListPageSize,
        String languageFile,
        boolean backupOnJoin,
        boolean backupOnQuit,
        boolean backupOnDeath,
        boolean backupOnWorldChange,
        Set<String> excludedWorlds,
        boolean guiSoundsEnabled,
        SoundEffect guiClickSound
) {
    public static PluginConfig from(Plugin plugin, Lang lang, FileConfiguration config) {
        var intervalMinutes = Math.max(0, config.getLong("backup.interval-minutes", 30));
        var jitterSeconds = Math.max(0, config.getLong("backup.jitter-seconds", 300));
        var keepPerPlayer = Math.max(0, config.getInt("backup.keep-per-player", 50));

        String languageFile = config.getString("language", "zh_CN.yml");
        if (languageFile == null || languageFile.isBlank()) {
            languageFile = "zh_CN.yml";
        } else {
            languageFile = languageFile.trim();
        }

        var storageType = StorageType.fromConfigValue(config.getString("storage.type", "sqlite"));
        var localBasePath = Path.of(config.getString("storage.local.base-path", "data"));
        var sqliteFile = Path.of(config.getString("storage.sqlite.file", "data/backups.db"));
        MysqlConfig mysql = MysqlConfig.from(config);
        H2Config h2 = H2Config.from(config);

        var queueLimit = Math.max(1, config.getInt("performance.queue-limit", 500));
        var maxWritesPerSecond = Math.max(0.1, config.getDouble("performance.max-writes-per-second", 20));

        var guiListPageSize = Math.min(45, Math.max(9, config.getInt("gui.list-page-size", 45)));

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

        return new PluginConfig(
                Duration.ofMinutes(intervalMinutes),
                Duration.ofSeconds(jitterSeconds),
                keepPerPlayer,
                storageType,
                localBasePath,
                sqliteFile,
                mysql,
                h2,
                queueLimit,
                maxWritesPerSecond,
                guiListPageSize,
                languageFile,
                backupOnJoin,
                backupOnQuit,
                backupOnDeath,
                backupOnWorldChange,
                excludedWorlds,
                guiSoundsEnabled,
                guiClickSound
        );
    }
}
