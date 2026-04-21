package org.playerinvbackup.backup.runtime;

import org.playerinvbackup.backup.audit.AuditService;
import org.playerinvbackup.backup.app.BackupScheduler;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.app.IoDispatcher;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.metrics.BStatsService;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Lang;

/**
 * 聚合当前这一套运行时组件
 *
 * <p>一个 runtime 代表一次完整装配后的运行状态, 包含配置快照, 语言, 存储, I/O 调度器
 * 备份服务, GUI 服务和其他长生命周期组件
 * 主类只持有这个聚合对象, 而不是分散持有大量字段
 */
public final class BackupRuntime {
    private final PluginConfig pluginConfig;
    private final Lang lang;
    private final BackupStore store;
    private final IoDispatcher ioDispatcher;
    private final BackupService backupService;
    private final BackupScheduler backupScheduler;
    private final AuditService auditService;
    private final BStatsService bStatsService;
    private final GuiService guiService;
    private final RestoreService restoreService;
    private final PacketGuiManager packetGuiManager;
    private final String storeInitFailedReason;

    public BackupRuntime(
            PluginConfig pluginConfig,
            Lang lang,
            BackupStore store,
            IoDispatcher ioDispatcher,
            BackupService backupService,
            BackupScheduler backupScheduler,
            AuditService auditService,
            BStatsService bStatsService,
            GuiService guiService,
            RestoreService restoreService,
            PacketGuiManager packetGuiManager,
            String storeInitFailedReason
    ) {
        this.pluginConfig = pluginConfig;
        this.lang = lang;
        this.store = store;
        this.ioDispatcher = ioDispatcher;
        this.backupService = backupService;
        this.backupScheduler = backupScheduler;
        this.auditService = auditService;
        this.bStatsService = bStatsService;
        this.guiService = guiService;
        this.restoreService = restoreService;
        this.packetGuiManager = packetGuiManager;
        this.storeInitFailedReason = storeInitFailedReason;
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public Lang lang() {
        return lang;
    }

    public BackupStore store() {
        return store;
    }

    public IoDispatcher ioDispatcher() {
        return ioDispatcher;
    }

    public BackupService backupService() {
        return backupService;
    }

    public BackupScheduler backupScheduler() {
        return backupScheduler;
    }

    public AuditService auditService() {
        return auditService;
    }

    public BStatsService bStatsService() {
        return bStatsService;
    }

    public GuiService guiService() {
        return guiService;
    }

    public RestoreService restoreService() {
        return restoreService;
    }

    public PacketGuiManager packetGuiManager() {
        return packetGuiManager;
    }

    public String storeInitFailedReason() {
        return storeInitFailedReason;
    }

    public boolean isStoreReady() {
        return store != null && ioDispatcher != null && backupService != null;
    }

    /**
     * 在 reload 前关闭本轮需要替换的组件
     *
     * <p>这里保留 GUI 服务, 恢复服务, 审计服务, bStats 这些可以跨 reload 复用的对象
     * 只关闭当前运行时里的 packet GUI, 调度器, I/O 队列和存储
     * 返回值用于把 reload 时丢弃的未开始 I/O 任务数量回传给上层
     */
    public int shutdownForReload(GuiRuntimeManager guiRuntimeManager) {
        if (guiRuntimeManager != null) {
            guiRuntimeManager.shutdownPacketGui(guiService, packetGuiManager);
        }
        if (backupScheduler != null) {
            backupScheduler.stop();
        }

        int discardedIoTasks = 0;
        if (ioDispatcher != null) {
            discardedIoTasks = ioDispatcher.discardQueuedAndAwaitRunning();
        }
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }
        return discardedIoTasks;
    }

    /**
     * 在插件停用时关闭整个运行时
     *
     * <p>和 reload 不同, 停服时连长生命周期组件也要一并关闭
     * 因此这里会在 shutdownForReload 的基础上继续停止 bStats 和审计维护任务
     */
    public void shutdown(GuiRuntimeManager guiRuntimeManager) {
        shutdownForReload(guiRuntimeManager);
        if (bStatsService != null) {
            bStatsService.shutdown();
        }
        if (auditService != null) {
            auditService.shutdown();
        }
    }
}
