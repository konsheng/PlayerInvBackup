package org.playerinvbackup.backup;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.audit.AuditService;
import org.playerinvbackup.backup.app.BackupScheduler;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.app.IoDispatcher;
import org.playerinvbackup.backup.command.BackupCommand;
import org.playerinvbackup.backup.config.GuiMode;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.gui.BukkitGuiListener;
import org.playerinvbackup.backup.gui.GuiChatListener;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.metrics.BStatsService;
import org.playerinvbackup.backup.platform.PlayerLifecycleListener;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.h2.H2BackupStore;
import org.playerinvbackup.backup.store.local.LocalBackupStore;
import org.playerinvbackup.backup.store.mysql.MysqlBackupStore;
import org.playerinvbackup.backup.store.postgresql.PostgresqlBackupStore;
import org.playerinvbackup.backup.store.sqlite.SqliteBackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件主类
 *
 * <p>负责:
 * 1) 加载配置与语言文件
 * 2) 初始化存储后端与异步 I/O 队列
 * 3) 启动自动备份调度与事件触发备份
 * 4) 初始化 GUI 服务 (ProtocolLib 为可选依赖, 未安装时使用原生 GUI)
 */
public final class PlayerInvBackupPlugin extends JavaPlugin {
    private PluginConfig pluginConfig;
    private BackupStore store;
    private IoDispatcher ioDispatcher;
    private BackupService backupService;
    private BackupScheduler backupScheduler;
    private AuditService auditService;
    private BStatsService bStatsService;
    private GuiService guiService;
    private RestoreService restoreService;
    private Lang lang;
    private PacketGuiManager packetGuiManager;
    // 最近一次存储初始化失败原因, 用于 status 与 reload 反馈
    private volatile String storeInitFailedReason;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.auditService = new AuditService(this);
        this.bStatsService = new BStatsService(this);
        this.restoreService = new RestoreService(this);
        this.guiService = new GuiService(this, restoreService);
        reload();
        if (!isEnabled()) {
            return;
        }

        // 语言与配置在 reload() 里初始化, 事件监听与 GUI 依赖它们, 所以这里放到 reload() 之后
        getServer().getPluginManager().registerEvents(new BukkitGuiListener(guiService), this);
        getServer().getPluginManager().registerEvents(new GuiChatListener(guiService), this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);

        getLogger().info(lang.plain("console.plugin.enabled"));
    }

    @Override
    public void onDisable() {
        shutdownPacketGui();
        if (bStatsService != null) {
            bStatsService.shutdown();
            bStatsService = null;
        }
        if (backupScheduler != null) {
            backupScheduler.stop();
            backupScheduler = null;
        }
        if (ioDispatcher != null) {
            ioDispatcher.close();
            ioDispatcher = null;
        }
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignored) {
            }
            store = null;
        }
        getLogger().info(lang.plain("console.plugin.disabled"));
    }

    public void reload() {
        if (backupScheduler != null) {
            backupScheduler.stop();
            backupScheduler = null;
        }

        reloadConfig();
        String languageFile = getConfig().getString("language");
        if (languageFile != null) {
            languageFile = languageFile.trim();
        }
        this.lang = loadLang(languageFile);
        Chat.init(this.lang);

        // 自动补全 config.yml 缺失的键, 避免升级后因为缺少新配置导致功能异常
        Path configFile = getDataFolder().toPath().resolve("config.yml");
        ConfigAutoFillResult configAutoFill = autoFillMissingConfigKeys(configFile);
        if (configAutoFill.error != null) {
            String reason = configAutoFill.error.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = lang.plain(
                        "console.config.defaults-resource-missing",
                        Placeholder.unparsed("resource", configAutoFill.defaultsResourcePath)
                );
            }
            getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.config.autofill-failed",
                            Placeholder.unparsed("file", configFile.toString()),
                            Placeholder.unparsed("defaults", configAutoFill.defaultsResourcePath),
                            Placeholder.unparsed("reason", reason)
                    ),
                    configAutoFill.error
            );
        } else if (configAutoFill.added > 0) {
            getLogger().info(lang.plain(
                    "console.config.autofill-added",
                    Placeholder.unparsed("file", configFile.toString()),
                    Placeholder.unparsed("added", String.valueOf(configAutoFill.added))
            ));
            reloadConfig();
        }

        this.pluginConfig = PluginConfig.from(this, this.lang, getConfig());
        auditService.reload(pluginConfig);
        bStatsService.reload(pluginConfig);
        applyGuiMode();

        try {
            if (store != null) {
                store.close();
            }
            if (ioDispatcher != null) {
                ioDispatcher.close();
            }
        } catch (Exception ignored) {
        }
        this.store = null;
        this.ioDispatcher = null;
        this.backupService = null;
        this.storeInitFailedReason = null;

        BackupStore newStore = null;
        try {
            newStore = createStore(pluginConfig);
            newStore.init();
        } catch (Exception e) {
            this.storeInitFailedReason = String.valueOf(e.getMessage());
            getLogger().severe(lang.plain(
                    "console.store.init-failed",
                    Placeholder.unparsed("reason", String.valueOf(storeInitFailedReason))
            ));
            getLogger().warning(lang.plain("console.store.unavailable-standby"));
            if (newStore != null) {
                try {
                    newStore.close();
                } catch (Exception ignored) {
                }
            }
            // 存储不可用时不禁用插件, 保持命令可用以便管理员修正配置后 /pib reload 重试
            newStore = null;
        }
        if (newStore != null) {
            this.store = newStore;
            this.ioDispatcher = new IoDispatcher(
                    pluginConfig.queueLimit(),
                    pluginConfig.maxWritesPerSecond(),
                    getName() + "-io"
            );
            this.backupService = new BackupService(this, pluginConfig, store, ioDispatcher);

            this.backupScheduler = new BackupScheduler(this, pluginConfig, backupService);
            this.backupScheduler.start();

            logStartupConfig();
        }

        var command = new BackupCommand(this);
        var pluginCommand = getCommand("playerinvbackup");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
    }

    private BackupStore createStore(PluginConfig config) {
        Path dataFolder = getDataFolder().toPath();
        return switch (config.storageType()) {
            case LOCAL -> new LocalBackupStore(dataFolder.resolve(config.localBasePath()));
            case SQLITE -> new SqliteBackupStore(
                    dataFolder.resolve(config.sqliteFile()),
                    new SqlTableNames(config.sqliteTablePrefix())
            );
            case MYSQL -> new MysqlBackupStore(
                    config.mysql().jdbcUrl(),
                    config.mysql().username(),
                    config.mysql().password(),
                    new SqlTableNames(config.mysql().tablePrefix())
            );
            case POSTGRESQL -> new PostgresqlBackupStore(
                    config.postgresql().jdbcUrl(),
                    config.postgresql().username(),
                    config.postgresql().password(),
                    new SqlTableNames(config.postgresql().tablePrefix())
            );
            case H2 -> {
                Path fileBase = dataFolder.resolve(config.h2().file());
                String jdbcUrl = config.h2().jdbcUrl(fileBase);
                yield new H2BackupStore(
                        fileBase,
                        jdbcUrl,
                        config.h2().username(),
                        config.h2().password(),
                        new SqlTableNames(config.h2().tablePrefix())
                );
            }
        };
    }

    private record LangAutoFillResult(int added, Throwable error, String defaultsResourcePath) {
    }

    private record ConfigAutoFillResult(int added, Throwable error, String defaultsResourcePath) {
    }

    private Lang loadLang(String languageFile) {
        Path langDir = getDataFolder().toPath().resolve("lang");

        try {
            Files.createDirectories(langDir);
        } catch (Exception ignored) {
        }

        Path normalizedLangDir;
        try {
            normalizedLangDir = langDir.toAbsolutePath().normalize();
        } catch (Exception e) {
            normalizedLangDir = langDir;
        }

        Path defaultFile = normalizedLangDir.resolve("zh_CN.yml");

        extractBundledLangFiles(langDir);

        if (!Files.exists(defaultFile)) {
            saveResource("lang/zh_CN.yml", false);
        }

        String fileName = languageFile == null || languageFile.isBlank() ? "zh_CN.yml" : languageFile.trim();
        if (!isSafeLangFileName(fileName)) {
            getLogger().warning("language 配置不合法, 已回退到默认语言: " + languageFile);
            fileName = "zh_CN.yml";
        }

        Path requested = normalizedLangDir.resolve(fileName).normalize();
        if (!requested.startsWith(normalizedLangDir)) {
            fileName = "zh_CN.yml";
            requested = defaultFile;
        }

        // 如果用户指定了语言文件且本地不存在, 尝试从插件 Jar 内复制一份默认模板出来
        if (!Files.exists(requested)) {
            String resourcePath = "lang/" + fileName;
            try (InputStream in = getResource(resourcePath)) {
                if (in != null) {
                    Files.copy(in, requested);
                }
            } catch (Exception ignored) {
            }
        }

        String selectedName = fileName;
        Path selected = requested;
        boolean fallbackToDefault = false;
        if (!Files.exists(selected)) {
            fallbackToDefault = true;
            selectedName = "zh_CN.yml";
            selected = defaultFile;
        }

        LangAutoFillResult autoFill = autoFillMissingLangKeys(selected, selectedName);
        Lang loaded = Lang.load(this, selected);

        if (fallbackToDefault) {
            getLogger().warning(loaded.plain(
                    "console.lang.fallback",
                    Placeholder.unparsed("requested", fileName),
                    Placeholder.unparsed("path", requested.toString())
            ));
        }

        if (autoFill.error != null) {
            String reason = autoFill.error.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = loaded.plain(
                        "console.lang.defaults-resource-missing",
                        Placeholder.unparsed("resource", autoFill.defaultsResourcePath)
                );
            }
            getLogger().log(
                    Level.WARNING,
                    loaded.plain(
                            "console.lang.autofill-failed",
                            Placeholder.unparsed("file", selected.toString()),
                            Placeholder.unparsed("defaults", autoFill.defaultsResourcePath),
                            Placeholder.unparsed("reason", reason)
                    ),
                    autoFill.error
            );
        } else if (autoFill.added > 0) {
            getLogger().info(loaded.plain(
                    "console.lang.autofill-added",
                    Placeholder.unparsed("file", selected.toString()),
                    Placeholder.unparsed("added", String.valueOf(autoFill.added))
            ));
        }

        return loaded;
    }

    /**
     * 自动补全配置文件缺失的键, 让升级后不必手动对比 config.yml
     *
     * <p>注意: Bukkit 的 YamlConfiguration 保存时会重写文件, 可能会改变键顺序并丢失注释
     */
    private ConfigAutoFillResult autoFillMissingConfigKeys(Path file) {
        if (file == null) {
            return new ConfigAutoFillResult(0, null, "-");
        }

        String resourcePath = "config.yml";
        InputStream stream = getResource(resourcePath);
        if (stream == null) {
            return new ConfigAutoFillResult(0, new IllegalStateException(), resourcePath);
        }

        int added = 0;
        try (InputStream defaultsStream = stream;
             InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file.toFile());
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue;
                }
                if (current.contains(key)) {
                    continue;
                }
                current.set(key, defaults.get(key));
                added++;
            }
            if (added > 0) {
                current.save(file.toFile());
            }
        } catch (Exception e) {
            return new ConfigAutoFillResult(added, e, resourcePath);
        }

        return new ConfigAutoFillResult(added, null, resourcePath);
    }

    /**
     * 提取所有内置语言文件到 plugins/PlayerInvBackup/lang/ 目录 (仅在文件不存在时写入, 不覆盖修改).
     */
    private void extractBundledLangFiles(Path langDir) {
        if (langDir == null) {
            return;
        }

        try {
            Files.createDirectories(langDir);
        } catch (Exception ignored) {
        }

        Path normalizedLangDir;
        try {
            normalizedLangDir = langDir.toAbsolutePath().normalize();
        } catch (Exception e) {
            normalizedLangDir = langDir;
        }

        // 兜底: 开发环境下可能无法扫描 jar, 这里确保常用语言文件至少会被提取出来
        saveBundledLangIfMissing(normalizedLangDir, "zh_CN.yml");
        saveBundledLangIfMissing(normalizedLangDir, "en_US.yml");

        try {
            Path jarPath = pluginJarPath();
            if (jarPath == null
                    || !Files.isRegularFile(jarPath)
                    || jarPath.getFileName() == null
                    || !jarPath.getFileName().toString().toLowerCase().endsWith(".jar")) {
                return;
            }

            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry == null || entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (name == null || !name.startsWith("lang/") || !name.endsWith(".yml")) {
                        continue;
                    }
                    String fileName = name.substring("lang/".length());
                    // 安全: 只允许简单文件名(不含路径分隔符), 并校验 normalize 后仍位于 langDir 下, 避免路径穿越
                    if (!isSafeLangFileName(fileName)) {
                        continue;
                    }
                    Path target = normalizedLangDir.resolve(fileName).normalize();
                    if (!target.startsWith(normalizedLangDir)) {
                        continue;
                    }
                    if (Files.exists(target)) {
                        continue;
                    }
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        Files.copy(in, target);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Path pluginJarPath() {
        try {
            URL url = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (url == null) {
                return null;
            }
            URI uri = url.toURI();
            return uri == null ? null : Path.of(uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveBundledLangIfMissing(Path langDir, String fileName) {
        if (langDir == null || fileName == null || fileName.isBlank()) {
            return;
        }
        try {
            Path target = langDir.resolve(fileName).normalize();
            if (Files.exists(target)) {
                return;
            }
            saveResource("lang/" + fileName, false);
        } catch (Exception ignored) {
        }
    }

    private boolean isSafeLangFileName(String fileName) {
        if (fileName == null) {
            return false;
        }

        String trimmed = fileName.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if (trimmed.contains("..")) {
            return false;
        }
        return trimmed.matches("^[A-Za-z0-9][A-Za-z0-9._-]*\\.yml$");
    }

    /**
     * 自动补全语言文件缺失的键, 以减少升级后出现的 "missing:key" 警告
     *
     * <p>注意: 这里使用 Bukkit 的 YamlConfiguration 保存文件, 可能会改变键顺序并丢失注释
     */
    private LangAutoFillResult autoFillMissingLangKeys(Path file, String name) {
        if (file == null) {
            return new LangAutoFillResult(0, null, "-");
        }

        String fileName = name == null || name.isBlank() ? "zh_CN.yml" : name.trim();
        String resourcePath = "lang/" + fileName;
        InputStream stream = getResource(resourcePath);

        // 使用插件内置语言文件来补全缺失的键. 如果不存在对应资源, 则回退到默认 zh_CN.yml
        if (stream == null) {
            resourcePath = "lang/zh_CN.yml";
            stream = getResource(resourcePath);
        }
        if (stream == null) {
            // 这里不写死提示文本, 由后续加载到的语言文件决定如何显示错误原因
            return new LangAutoFillResult(0, new IllegalStateException(), resourcePath);
        }

        int added = 0;
        try (InputStream defaultsStream = stream;
             InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file.toFile());
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue;
                }
                // prefix 是可选项: 管理员可以不配置它来实现“无前缀”, 因此不自动补全该键
                if ("prefix".equalsIgnoreCase(key)) {
                    continue;
                }
                if (current.contains(key)) {
                    continue;
                }
                current.set(key, defaults.get(key));
                added++;
            }
            if (added > 0) {
                current.save(file.toFile());
            }
        } catch (Exception e) {
            return new LangAutoFillResult(added, e, resourcePath);
        }

        return new LangAutoFillResult(added, null, resourcePath);
    }

    private void logStartupConfig() {
        String storageInfo = switch (pluginConfig.storageType()) {
            case LOCAL -> "local(" + getDataFolder().toPath().resolve(pluginConfig.localBasePath()) + ")";
            case SQLITE -> "sqlite(" + getDataFolder().toPath().resolve(pluginConfig.sqliteFile()) + ")";
            case MYSQL -> "mysql(" + pluginConfig.mysql().host() + ":" + pluginConfig.mysql().port() + "/" + pluginConfig.mysql().database() + ")";
            case POSTGRESQL -> "postgresql(" + pluginConfig.postgresql().host() + ":" + pluginConfig.postgresql().port() + "/" + pluginConfig.postgresql().database() + ")";
            case H2 -> "h2(" + getDataFolder().toPath().resolve(pluginConfig.h2().file()) + ")";
        };

        getLogger().info(lang.plain(
                "console.startup.storage",
                Placeholder.unparsed("storage", storageInfo)
        ));
        getLogger().info(lang.plain(
                "console.startup.schedule",
                Placeholder.unparsed("interval", String.valueOf(pluginConfig.backupInterval().toMinutes())),
                Placeholder.unparsed("jitter", String.valueOf(pluginConfig.jitter().toSeconds()))
        ));
        getLogger().info(lang.plain(
                "console.startup.retention",
                Placeholder.unparsed("keep", String.valueOf(pluginConfig.keepPerPlayer())),
                Placeholder.unparsed("keep_days", String.valueOf(pluginConfig.keepDuration().toDays())),
                Placeholder.unparsed("queue_limit", String.valueOf(pluginConfig.queueLimit())),
                Placeholder.unparsed("max_writes", String.valueOf(pluginConfig.maxWritesPerSecond()))
        ));
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public BackupStore store() {
        return store;
    }

    public BackupService backupService() {
        return backupService;
    }

    public BackupScheduler backupScheduler() {
        return backupScheduler;
    }

    public IoDispatcher ioDispatcher() {
        return ioDispatcher;
    }

    public boolean isStoreReady() {
        return store != null && ioDispatcher != null && backupService != null;
    }

    public String storeInitFailedReason() {
        return storeInitFailedReason;
    }

    public GuiService guiService() {
        return guiService;
    }

    public RestoreService restoreService() {
        return restoreService;
    }

    public AuditService auditService() {
        return auditService;
    }

    public Lang lang() {
        return lang;
    }

    public boolean isPacketGuiEnabled() {
        return packetGuiManager != null;
    }

    /**
     * ProtocolLib 是可选依赖
     * 支持自动切换与手动强制模式
     *
     * <p>gui.mode:
     * auto: 自动切换, ProtocolLib 存在则启用发包 GUI, 否则使用原生 GUI
     * bukkit: 强制使用原生 GUI
     * packet: 强制使用发包 GUI, 需要 ProtocolLib, 否则自动降级
     */
    private void applyGuiMode() {
        PluginConfig cfg = pluginConfig;
        GuiMode mode = cfg == null ? GuiMode.AUTO : cfg.guiMode();

        boolean protocolLibEnabled;
        try {
            protocolLibEnabled = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
        } catch (Exception ignored) {
            protocolLibEnabled = false;
        }

        boolean wantPacket = mode == GuiMode.PACKET || (mode == GuiMode.AUTO && protocolLibEnabled);
        if (!wantPacket) {
            shutdownPacketGui();
            return;
        }

        if (!protocolLibEnabled) {
            shutdownPacketGui();
            if (mode == GuiMode.PACKET) {
                getLogger().warning(lang.plain("console.dependency.protocollib-missing-forced"));
            } else {
                getLogger().info(lang.plain("console.dependency.protocollib-missing"));
            }
            return;
        }

        if (packetGuiManager != null) {
            return;
        }

        try {
            PacketGuiManager manager = new PacketGuiManager(this);
            manager.setGuiService(guiService);
            manager.register();
            this.packetGuiManager = manager;
            this.guiService.setPacketGuiManager(manager);
        } catch (NoClassDefFoundError | Exception e) {
            shutdownPacketGui();
            getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.gui.init-failed",
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
        }
    }

    private void shutdownPacketGui() {
        PacketGuiManager manager = packetGuiManager;
        if (manager == null) {
            if (guiService != null) {
                guiService.setPacketGuiManager(null);
            }
            return;
        }
        try {
            manager.shutdown();
        } catch (Exception ignored) {
        }
        packetGuiManager = null;
        if (guiService != null) {
            guiService.setPacketGuiManager(null);
        }
    }
}
