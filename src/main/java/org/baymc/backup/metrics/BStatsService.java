package org.baymc.backup.metrics;

import java.util.Objects;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.config.PluginConfig;
import org.bstats.bukkit.Metrics;

/**
 * bStats 统计服务
 *
 * <p>bStats 自带全局开关(plugins/bStats/config.yml), 插件侧只负责初始化
 */
public final class BStatsService {
    private static final int PLUGIN_ID = 29588;

    private final BayMcBackUpPlugin plugin;
    private Metrics metrics;

    public BStatsService(BayMcBackUpPlugin plugin) {
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
