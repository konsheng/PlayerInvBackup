package org.baymc.backup.platform;

import java.util.Locale;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.Permissions;
import org.baymc.backup.config.PluginConfig;
import org.baymc.backup.domain.TriggerType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 玩家生命周期事件监听
 *
 * <p>根据配置在上线/下线/死亡/切换世界时触发一次备份, 并处理自动备份排除逻辑
 */
public final class PlayerLifecycleListener implements Listener {
    private final BayMcBackUpPlugin plugin;

    public PlayerLifecycleListener(BayMcBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.backupScheduler() != null) {
            plugin.backupScheduler().onPlayerQuit(player.getUniqueId());
        }

        PluginConfig config = plugin.pluginConfig();
        if (config == null || !config.backupOnQuit()) {
            return;
        }
        if (shouldSkipAutomaticBackup(config, player, player.getWorld().getName())) {
            return;
        }
        var backupService = plugin.backupService();
        if (backupService == null) {
            return;
        }
        backupService.requestBackup(player, TriggerType.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PluginConfig config = plugin.pluginConfig();
        if (config == null || !config.backupOnJoin()) {
            return;
        }

        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, ignored -> {
            PluginConfig latest = plugin.pluginConfig();
            if (latest == null || !latest.backupOnJoin()) {
                return;
            }
            if (shouldSkipAutomaticBackup(latest, player, player.getWorld().getName())) {
                return;
            }
            var backupService = plugin.backupService();
            if (backupService == null) {
                return;
            }
            backupService.requestBackup(player, TriggerType.JOIN);
        }, null, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PluginConfig config = plugin.pluginConfig();
        if (config == null || !config.backupOnDeath()) {
            return;
        }
        Player player = event.getEntity();
        if (shouldSkipAutomaticBackup(config, player, player.getWorld().getName())) {
            return;
        }
        var backupService = plugin.backupService();
        if (backupService == null) {
            return;
        }
        backupService.requestBackup(player, TriggerType.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerTeleportEvent event) {
        PluginConfig config = plugin.pluginConfig();
        if (config == null || !config.backupOnWorldChange()) {
            return;
        }

        if (event.getTo() == null || event.getFrom().getWorld() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        Player player = event.getPlayer();
        String fromWorldName = event.getFrom().getWorld().getName();
        if (shouldSkipAutomaticBackup(config, player, fromWorldName)) {
            return;
        }
        var backupService = plugin.backupService();
        if (backupService == null) {
            return;
        }
        backupService.requestBackup(player, TriggerType.WORLD_CHANGE);
    }

    private static boolean shouldSkipAutomaticBackup(PluginConfig config, Player player, String worldName) {
        if (config == null || player == null) {
            return true;
        }
        if (player.hasPermission(Permissions.BACKUP_EXEMPT)) {
            return true;
        }
        if (config.excludedWorlds().isEmpty()) {
            return false;
        }
        if (worldName == null) {
            return false;
        }
        return config.excludedWorlds().contains(worldName.toLowerCase(Locale.ROOT));
    }
}
