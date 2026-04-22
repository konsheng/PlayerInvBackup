package org.playerinvbackup.backup;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.audit.AuditService;
import org.playerinvbackup.backup.app.BackupScheduler;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.app.IoDispatcher;
import org.playerinvbackup.backup.command.BackupCommand;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.gui.BukkitGuiListener;
import org.playerinvbackup.backup.gui.GuiChatListener;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.platform.PlayerLifecycleListener;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.runtime.BackupRuntime;
import org.playerinvbackup.backup.runtime.BackupStoreFactory;
import org.playerinvbackup.backup.runtime.GuiRuntimeManager;
import org.playerinvbackup.backup.runtime.PluginBootstrap;
import org.playerinvbackup.backup.runtime.ReloadCoordinator;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件入口, 只负责生命周期回调和高层编排
 *
 * <p>主类不再直接承担配置重载, 存储创建, GUI 模式切换, 运行时组件装配这些细节
 * 这些工作分别委派给 runtime 包里的编排对象
 * 这里保留当前运行时引用, 在生命周期里切换运行时, 并向其他模块暴露必要的只读访问入口
 */
public final class PlayerInvBackupPlugin extends JavaPlugin {
    private BackupRuntime runtime;
    private Lang bootstrapLang;
    private BackupCommand backupCommand;
    private GuiRuntimeManager guiRuntimeManager;
    private ReloadCoordinator reloadCoordinator;
    private PluginBootstrap pluginBootstrap;
    private volatile int lastReloadCancelledBackupTargets;
    private volatile int lastReloadDiscardedIoTasks;
    private volatile String buildCommitShort;

    @Override
    public void onEnable() {
        long startupStartedAt = System.nanoTime();
        ensureLifecycleComponents();

        ReloadCoordinator.ReloadResult result = pluginBootstrap.bootstrap();
        if (!isEnabled() || result == null) {
            return;
        }

        applyReloadResult(result);
        registerCoreListeners();

        long startupElapsedMs = (System.nanoTime() - startupStartedAt) / 1_000_000L;
        getLogger().info(lang().plain(
                "console.plugin.enabled",
                Placeholder.unparsed("elapsed_ms", String.valueOf(startupElapsedMs))
        ));
    }

    @Override
    public void onDisable() {
        Lang currentLang = lang();
        if (runtime != null && guiRuntimeManager != null) {
            runtime.shutdown(guiRuntimeManager);
        }

        if (currentLang != null) {
            getLogger().info(currentLang.plain("console.plugin.disabled"));
        } else {
            boolean chinese = "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
            getLogger().info(chinese ? "PlayerInvBackup 已关闭" : "PlayerInvBackup has been disabled");
        }

        runtime = null;
        backupCommand = null;
    }

    /**
     * 对外暴露的重载入口, 只负责委派完整的停旧换新流程
     *
     * <p>命令层和其他调用方只需要关心调用这个入口, 不需要知道内部如何停止旧调度器
     * 如何关闭旧存储, 如何重建语言, 配置和运行时组件
     */
    public void reload() {
        ensureLifecycleComponents();
        applyReloadResult(reloadCoordinator.reload(runtime, backupCommand));
    }

    public int lastReloadCancelledBackupTargets() {
        return lastReloadCancelledBackupTargets;
    }

    public int lastReloadDiscardedIoTasks() {
        return lastReloadDiscardedIoTasks;
    }

    public String statusPluginVersion() {
        String version = getPluginMeta().getVersion();
        if (version == null || version.isBlank()) {
            return version;
        }
        if (!version.toUpperCase(Locale.ROOT).contains("SNAPSHOT")) {
            return version;
        }

        String commit = buildCommitShort();
        if (commit == null || commit.isBlank()) {
            return version;
        }
        return version + "-" + commit;
    }

    public PluginConfig pluginConfig() {
        return runtime == null ? null : runtime.pluginConfig();
    }

    public BackupStore store() {
        return runtime == null ? null : runtime.store();
    }

    public BackupService backupService() {
        return runtime == null ? null : runtime.backupService();
    }

    public BackupScheduler backupScheduler() {
        return runtime == null ? null : runtime.backupScheduler();
    }

    public IoDispatcher ioDispatcher() {
        return runtime == null ? null : runtime.ioDispatcher();
    }

    public boolean isStoreReady() {
        return runtime != null && runtime.isStoreReady();
    }

    public String storeInitFailedReason() {
        return runtime == null ? null : runtime.storeInitFailedReason();
    }

    public GuiService guiService() {
        return runtime == null ? null : runtime.guiService();
    }

    public RestoreService restoreService() {
        return runtime == null ? null : runtime.restoreService();
    }

    public AuditService auditService() {
        return runtime == null ? null : runtime.auditService();
    }

    public Lang lang() {
        return runtime == null ? bootstrapLang : runtime.lang();
    }

    public boolean isPacketGuiEnabled() {
        return runtime != null && runtime.packetGuiManager() != null;
    }

    public void setBootstrapLang(Lang bootstrapLang) {
        this.bootstrapLang = bootstrapLang;
    }

    /**
     * 确保高层编排对象已经创建
     *
     * <p>这些对象跨多次 reload 复用, 主类在首次启用或外部直接触发 reload 时按需懒加载
     * 这样可以把主类里的生命周期方法保持在很薄的一层
     */
    private void ensureLifecycleComponents() {
        if (guiRuntimeManager == null) {
            guiRuntimeManager = new GuiRuntimeManager(this);
        }
        if (reloadCoordinator == null) {
            BackupStoreFactory backupStoreFactory = new BackupStoreFactory(getDataFolder().toPath());
            reloadCoordinator = new ReloadCoordinator(this, backupStoreFactory, guiRuntimeManager);
        }
        if (pluginBootstrap == null) {
            pluginBootstrap = new PluginBootstrap(this, reloadCoordinator);
        }
    }

    /**
     * 应用一次 reload 的结果
     *
     * <p>这里负责切换当前 runtime, 更新上一次 reload 的统计信息, 重新初始化聊天文本系统
     * 并重建命令装配对象, 保持现有命令入口继续指向最新的运行时
     */
    private void applyReloadResult(ReloadCoordinator.ReloadResult result) {
        runtime = result.runtime();
        if (runtime != null) {
            bootstrapLang = runtime.lang();
        }
        lastReloadCancelledBackupTargets = result.cancelledBackupTargets();
        lastReloadDiscardedIoTasks = result.discardedIoTasks();

        Chat.init(lang());

        backupCommand = new BackupCommand(this);
        registerCommandHandlers();
    }

    /**
     * 监听器只在启动后注册一次, 运行时服务在重载时复用或重建
     *
     * <p>这里不在每次 reload 后重复注册监听器, 避免重复监听
     * 监听器依赖的服务对象通过主类 getter 访问当前运行时, 从而跟随 runtime 切换
     */
    private void registerCoreListeners() {
        GuiService currentGuiService = guiService();
        if (currentGuiService == null) {
            return;
        }

        getServer().getPluginManager().registerEvents(new BukkitGuiListener(currentGuiService), this);
        getServer().getPluginManager().registerEvents(new GuiChatListener(currentGuiService), this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);
    }

    /**
     * 把当前命令装配对象挂到 plugin.yml 声明的主命令上
     *
     * <p>命令对象会在每次 reload 后重建, 这样它内部拿到的是最新 runtime 和最新语言环境
     */
    private void registerCommandHandlers() {
        PluginCommand command = getCommand("playerinvbackup");
        if (command == null) {
            return;
        }
        command.setExecutor(backupCommand);
        command.setTabCompleter(backupCommand);
    }

    /**
     * 延迟读取构建信息里的短提交哈希
     *
     * <p>这里只读取构建时已经写入资源文件的值, 不在运行时调用 git
     * 这样可以保持本地运行和发布产物的行为一致
     */
    private String buildCommitShort() {
        String cached = buildCommitShort;
        if (cached != null) {
            return cached;
        }

        String loaded = "";
        try (InputStream in = getResource("build-info.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                String raw = properties.getProperty("git.commit.short", "");
                if (raw != null) {
                    loaded = raw.trim();
                }
            }
        } catch (Exception ignored) {
        }

        buildCommitShort = loaded;
        return loaded;
    }
}
