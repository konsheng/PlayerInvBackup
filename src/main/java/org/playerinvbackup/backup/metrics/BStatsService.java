package org.playerinvbackup.backup.metrics;

import java.util.Objects;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.PluginConfig;
import org.bstats.bukkit.Metrics;

/**
 * bStats 统计服务
 *
 * <p>bStats 自带全局开关 (plugins/bStats/config.yml), 插件侧只负责初始化
 */
public final class BStatsService {
    private static final int PLUGIN_ID = 30660;

    private final PlayerInvBackupPlugin plugin;
    private Metrics metrics;

    public BStatsService(PlayerInvBackupPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void reload(PluginConfig config) {
        start();
    }

    public void start() {
        if (metrics != null) {
            return;
        }
        try {
            this.metrics = new Metrics(plugin, PLUGIN_ID);
        } catch (Exception ignored) {
            shutdown();
        }
    }

    public void shutdown() {
        Metrics current = metrics;
        metrics = null;
        if (current == null) {
            return;
        }
        try {
            current.shutdown();
        } catch (Exception ignored) {
        }
    }
}
