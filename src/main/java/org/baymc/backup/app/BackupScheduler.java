package org.baymc.backup.app;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.Permissions;
import org.baymc.backup.config.PluginConfig;
import org.baymc.backup.domain.TriggerType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 自动备份调度器
 *
 * <p>在 Folia 下使用全局 Region Scheduler 每秒 tick 一次, 按玩家维度计算下一次到期时间
 * 并在玩家所在 Region 线程提交备份请求
 */
public final class BackupScheduler {
    private final BayMcBackUpPlugin plugin;
    private final PluginConfig config;
    private final BackupService backupService;

    private final Map<UUID, Long> nextDueAtMillis = new ConcurrentHashMap<>();
    private ScheduledTask task;

    public BackupScheduler(BayMcBackUpPlugin plugin, PluginConfig config, BackupService backupService) {
        this.plugin = plugin;
        this.config = config;
        this.backupService = backupService;
    }

    public void start() {
        if (task != null) {
            return;
        }
        if (config.backupInterval().isZero()) {
            plugin.getLogger().info(plugin.lang().plain("console.scheduler.auto-disabled"));
            return;
        }

        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> tick(), 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nextDueAtMillis.clear();
    }

    public void onPlayerQuit(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        nextDueAtMillis.remove(playerUuid);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long intervalMillis = config.backupInterval().toMillis();
        long jitterMillis = Math.max(0L, config.jitter().toMillis());

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            long dueAt = nextDueAtMillis.computeIfAbsent(uuid, ignored -> now + intervalMillis + randomJitter(jitterMillis));
            if (now < dueAt) {
                continue;
            }

            long next = now + intervalMillis + randomJitter(jitterMillis);
            nextDueAtMillis.put(uuid, next);

            player.getScheduler().run(plugin, ignored -> {
                if (player.hasPermission(Permissions.BACKUP_EXEMPT)) {
                    return;
                }
                if (!config.excludedWorlds().isEmpty()) {
                    String world = player.getWorld().getName();
                    if (world != null && config.excludedWorlds().contains(world.toLowerCase(Locale.ROOT))) {
                        return;
                    }
                }
                backupService.requestBackup(player, TriggerType.TIMER);
            }, null);
        }
    }

    private static long randomJitter(long jitterMillis) {
        if (jitterMillis <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(0, jitterMillis + 1);
    }
}
