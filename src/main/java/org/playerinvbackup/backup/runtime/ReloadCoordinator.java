package org.playerinvbackup.backup.runtime;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.audit.AuditService;
import org.playerinvbackup.backup.app.BackupScheduler;
import org.playerinvbackup.backup.app.BackupService;
import org.playerinvbackup.backup.app.IoDispatcher;
import org.playerinvbackup.backup.command.BackupCommand;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.gui.GuiService;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.metrics.BStatsService;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 协调整个重载流程, 停止旧运行时, 重载配置与语言, 再创建新运行时
 *
 * <p>这个对象是主类瘦身后的核心编排层, 负责把 reload 划分为停旧, 读新配置, 创建新 runtime
 * 三个阶段, 并保持现有日志语义, 失败回退语义和组件初始化顺序
 */
public final class ReloadCoordinator {
    public record ReloadResult(BackupRuntime runtime, int cancelledBackupTargets, int discardedIoTasks) {
    }

    private record RuntimeServices(
            AuditService auditService,
            BStatsService bStatsService,
            RestoreService restoreService,
            GuiService guiService
    ) {
    }

    private record ReloadInputs(PluginConfig pluginConfig, Lang lang) {
    }

    private record LangAutoFillResult(int added, Throwable error, String defaultsResourcePath) {
    }

    private record ConfigAutoFillResult(int added, Throwable error, String defaultsResourcePath) {
    }

    private final PlayerInvBackupPlugin plugin;
    private final BackupStoreFactory backupStoreFactory;
    private final GuiRuntimeManager guiRuntimeManager;

    public ReloadCoordinator(
            PlayerInvBackupPlugin plugin,
            BackupStoreFactory backupStoreFactory,
            GuiRuntimeManager guiRuntimeManager
    ) {
        this.plugin = plugin;
        this.backupStoreFactory = backupStoreFactory;
        this.guiRuntimeManager = guiRuntimeManager;
    }

    /**
     * 执行一次完整 reload
     *
     * <p>顺序保持为, 先取消命令中的活动操作, 再关闭旧 runtime 里需要替换的组件
     * 然后重载配置和语言, 最后创建新 runtime 并把统计结果返回给主类
     */
    public ReloadResult reload(BackupRuntime currentRuntime, BackupCommand currentCommand) {
        RuntimeServices services = resolveServices(currentRuntime);
        int cancelledBackupTargets = currentCommand == null ? 0 : currentCommand.cancelActiveOperationsForReload();
        int discardedIoTasks = currentRuntime == null ? 0 : currentRuntime.shutdownForReload(guiRuntimeManager);

        ReloadInputs inputs = loadConfigurationAndLanguage();
        BackupRuntime runtime = createRuntime(services, inputs);
        return new ReloadResult(runtime, cancelledBackupTargets, discardedIoTasks);
    }

    /**
     * 加载目标语言文件, 并执行缺失语言键自动补全
     *
     * <p>这里保留原有安全校验, 默认语言回退, 内置语言文件释放和自动补全日志
     * 上层只关心返回最终可用的 Lang 对象
     */
    public Lang loadLang(String languageFile) {
        Path langDir = plugin.getDataFolder().toPath().resolve("lang");

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
            plugin.saveResource("lang/zh_CN.yml", false);
        }

        String fileName = languageFile == null || languageFile.isBlank() ? "zh_CN.yml" : languageFile.trim();
        if (!isSafeLangFileName(fileName)) {
            plugin.getLogger().warning("language 配置项非法, 已回退到默认语言文件: " + languageFile);
            fileName = "zh_CN.yml";
        }

        Path requested = normalizedLangDir.resolve(fileName).normalize();
        if (!requested.startsWith(normalizedLangDir)) {
            fileName = "zh_CN.yml";
            requested = defaultFile;
        }

        if (!Files.exists(requested)) {
            String resourcePath = "lang/" + fileName;
            try (InputStream in = plugin.getResource(resourcePath)) {
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
        Lang loaded = Lang.load(plugin, selected);

        if (fallbackToDefault) {
            plugin.getLogger().warning(loaded.plain(
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
            plugin.getLogger().log(
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
            plugin.getLogger().info(loaded.plain(
                    "console.lang.autofill-added",
                    Placeholder.unparsed("file", selected.toString()),
                    Placeholder.unparsed("added", String.valueOf(autoFill.added))
            ));
        }

        return loaded;
    }

    /**
     * 重载配置文件和语言文件, 并生成新的配置快照
     *
     * <p>这里仍然保留 config.yml 缺失键自动补全逻辑
     * 自动补全后如果有新键写入, 会重新 reloadConfig 再生成 PluginConfig
     */
    private ReloadInputs loadConfigurationAndLanguage() {
        plugin.reloadConfig();
        String languageFile = plugin.getConfig().getString("language");
        if (languageFile != null) {
            languageFile = languageFile.trim();
        }

        Lang lang = loadLang(languageFile);
        Chat.init(lang);

        Path configFile = plugin.getDataFolder().toPath().resolve("config.yml");
        ConfigAutoFillResult configAutoFill = autoFillMissingConfigKeys(configFile);
        if (configAutoFill.error != null) {
            String reason = configAutoFill.error.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = lang.plain(
                        "console.config.defaults-resource-missing",
                        Placeholder.unparsed("resource", configAutoFill.defaultsResourcePath)
                );
            }
            plugin.getLogger().log(
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
            plugin.getLogger().info(lang.plain(
                    "console.config.autofill-added",
                    Placeholder.unparsed("file", configFile.toString()),
                    Placeholder.unparsed("added", String.valueOf(configAutoFill.added))
            ));
            plugin.reloadConfig();
        }

        YamlConfiguration soundsConfig = loadSoundsConfiguration(lang);
        PluginConfig pluginConfig = PluginConfig.from(plugin, lang, plugin.getConfig(), soundsConfig);
        return new ReloadInputs(pluginConfig, lang);
    }

    private YamlConfiguration loadSoundsConfiguration(Lang lang) {
        Path soundsFile = plugin.getDataFolder().toPath().resolve("sounds.yml");
        saveLocalizedSoundsIfMissing(soundsFile);

        ConfigAutoFillResult soundsAutoFill = autoFillMissingSoundsKeys(soundsFile);
        if (soundsAutoFill.error != null) {
            String reason = soundsAutoFill.error.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = lang.plain(
                        "console.sounds.defaults-resource-missing",
                        Placeholder.unparsed("resource", soundsAutoFill.defaultsResourcePath)
                );
            }
            plugin.getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.sounds.autofill-failed",
                            Placeholder.unparsed("file", soundsFile.toString()),
                            Placeholder.unparsed("defaults", soundsAutoFill.defaultsResourcePath),
                            Placeholder.unparsed("reason", reason)
                    ),
                    soundsAutoFill.error
            );
        } else if (soundsAutoFill.added > 0) {
            plugin.getLogger().info(lang.plain(
                    "console.sounds.autofill-added",
                    Placeholder.unparsed("file", soundsFile.toString()),
                    Placeholder.unparsed("added", String.valueOf(soundsAutoFill.added))
            ));
        }

        try {
            return loadYamlFileStrict(soundsFile);
        } catch (Exception e) {
            plugin.getLogger().log(
                    Level.WARNING,
                    lang.plain(
                            "console.sounds.load-failed",
                            Placeholder.unparsed("file", soundsFile.toString()),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
            try {
                return loadBundledYaml("sounds.yml");
            } catch (Exception bundledError) {
                plugin.getLogger().log(
                        Level.WARNING,
                        lang.plain(
                                "console.sounds.defaults-resource-missing",
                                Placeholder.unparsed("resource", "sounds.yml")
                        ),
                        bundledError
                );
                return new YamlConfiguration();
            }
        }
    }

    /**
     * 解析本次 reload 需要复用还是新建的长期服务对象
     *
     * <p>审计, bStats, 恢复服务, GUI 服务会跨 reload 复用
     * 只有在首次启动时才创建它们
     */
    private RuntimeServices resolveServices(BackupRuntime currentRuntime) {
        if (currentRuntime != null) {
            return new RuntimeServices(
                    currentRuntime.auditService(),
                    currentRuntime.bStatsService(),
                    currentRuntime.restoreService(),
                    currentRuntime.guiService()
            );
        }

        RestoreService restoreService = new RestoreService(plugin);
        return new RuntimeServices(
                new AuditService(plugin),
                new BStatsService(plugin),
                restoreService,
                new GuiService(plugin, restoreService)
        );
    }

    /**
     * 基于最新配置创建新的 runtime
     *
     * <p>这里先应用 GUI 模式, 再初始化存储
     * 如果存储初始化失败, 会保留插件可继续运行并支持后续 reload 重试
     * 如果存储可用, 再继续创建 I/O 调度器, 备份服务和调度器
     */
    private BackupRuntime createRuntime(RuntimeServices services, ReloadInputs inputs) {
        services.auditService().reload(inputs.pluginConfig());
        services.bStatsService().reload(inputs.pluginConfig());

        PacketGuiManager packetGuiManager = guiRuntimeManager.applyGuiMode(
                inputs.pluginConfig(),
                inputs.lang(),
                services.guiService()
        );

        BackupStore store = null;
        IoDispatcher ioDispatcher = null;
        BackupService backupService = null;
        BackupScheduler backupScheduler = null;
        String storeInitFailedReason = null;

        try {
            store = backupStoreFactory.create(inputs.pluginConfig());
            store.init();
        } catch (Exception e) {
            storeInitFailedReason = String.valueOf(e.getMessage());
            plugin.getLogger().severe(inputs.lang().plain(
                    "console.store.init-failed",
                    Placeholder.unparsed("reason", String.valueOf(storeInitFailedReason))
            ));
            plugin.getLogger().warning(inputs.lang().plain("console.store.unavailable-standby"));
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                }
            }
            store = null;
        }

        if (store != null) {
            ioDispatcher = new IoDispatcher(
                    inputs.pluginConfig().queueLimit(),
                    inputs.pluginConfig().maxWritesPerSecond(),
                    plugin.getName() + "-io"
            );
            backupService = new BackupService(plugin, inputs.pluginConfig(), store, ioDispatcher);
            backupScheduler = new BackupScheduler(plugin, inputs.pluginConfig(), backupService);
            backupScheduler.start();
            logStartupConfig(inputs.pluginConfig(), inputs.lang());
        }

        return new BackupRuntime(
                inputs.pluginConfig(),
                inputs.lang(),
                store,
                ioDispatcher,
                backupService,
                backupScheduler,
                services.auditService(),
                services.bStatsService(),
                services.guiService(),
                services.restoreService(),
                packetGuiManager,
                storeInitFailedReason
        );
    }

    /**
     * 输出当前运行配置摘要
     *
     * <p>这里保留重构前的控制台摘要语义, 方便管理员在每次成功 reload 后看到当前存储
     * 调度和保留策略是否符合预期
     */
    private void logStartupConfig(PluginConfig pluginConfig, Lang lang) {
        String storageInfo = switch (pluginConfig.storageType()) {
            case LOCAL -> "local(" + plugin.getDataFolder().toPath().resolve(pluginConfig.localBasePath()) + ")";
            case SQLITE -> "sqlite(" + plugin.getDataFolder().toPath().resolve(pluginConfig.sqliteFile()) + ")";
            case MYSQL -> "mysql(" + pluginConfig.mysql().host() + ":" + pluginConfig.mysql().port() + "/" + pluginConfig.mysql().database() + ")";
            case POSTGRESQL -> "postgresql(" + pluginConfig.postgresql().host() + ":" + pluginConfig.postgresql().port() + "/" + pluginConfig.postgresql().database() + ")";
            case H2 -> "h2(" + plugin.getDataFolder().toPath().resolve(pluginConfig.h2().file()) + ")";
        };

        plugin.getLogger().info(lang.plain(
                "console.startup.storage",
                Placeholder.unparsed("storage", storageInfo)
        ));
        plugin.getLogger().info(lang.plain(
                "console.startup.schedule",
                Placeholder.unparsed("interval", String.valueOf(pluginConfig.backupInterval().toMinutes())),
                Placeholder.unparsed("jitter", String.valueOf(pluginConfig.jitter().toSeconds()))
        ));
        plugin.getLogger().info(lang.plain(
                "console.startup.retention",
                Placeholder.unparsed("keep", String.valueOf(pluginConfig.keepPerPlayer())),
                Placeholder.unparsed("keep_days", String.valueOf(pluginConfig.keepDuration().toDays())),
                Placeholder.unparsed("queue_limit", String.valueOf(pluginConfig.queueLimit())),
                Placeholder.unparsed("max_writes", String.valueOf(pluginConfig.maxWritesPerSecond()))
        ));
    }

    private ConfigAutoFillResult autoFillMissingConfigKeys(Path file) {
        if (file == null) {
            return new ConfigAutoFillResult(0, null, "-");
        }

        String resourcePath = "config.yml";
        InputStream stream = plugin.getResource(resourcePath);
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

    private ConfigAutoFillResult autoFillMissingSoundsKeys(Path file) {
        if (file == null) {
            return new ConfigAutoFillResult(0, null, "-");
        }

        String resourcePath = "sounds.yml";
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            return new ConfigAutoFillResult(0, new IllegalStateException(), resourcePath);
        }

        int added = 0;
        try (InputStream defaultsStream = stream;
             InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration current = loadYamlFileStrict(file);
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

    private void saveLocalizedSoundsIfMissing(Path soundsFile) {
        if (soundsFile == null || Files.exists(soundsFile)) {
            return;
        }

        try {
            Files.createDirectories(soundsFile.getParent());
        } catch (Exception ignored) {
        }

        String resourcePath = "zh".equalsIgnoreCase(Locale.getDefault().getLanguage())
                ? "sounds.zh_CN.yml"
                : "sounds.yml";
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                Files.copy(in, soundsFile);
                return;
            }
        } catch (Exception ignored) {
        }

        try (InputStream in = plugin.getResource("sounds.yml")) {
            if (in != null) {
                Files.copy(in, soundsFile);
            }
        } catch (Exception ignored) {
        }
    }

    private YamlConfiguration loadYamlFileStrict(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private YamlConfiguration loadBundledYaml(String resourcePath) {
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled resource: " + resourcePath);
        }
        try (InputStream in = stream;
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled resource: " + resourcePath, e);
        }
    }

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
                    if (!isSafeLangFileName(fileName)) {
                        continue;
                    }
                    Path target = normalizedLangDir.resolve(fileName).normalize();
                    if (!target.startsWith(normalizedLangDir) || Files.exists(target)) {
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
            URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
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
            plugin.saveResource("lang/" + fileName, false);
        } catch (Exception ignored) {
        }
    }

    private boolean isSafeLangFileName(String fileName) {
        if (fileName == null) {
            return false;
        }

        String trimmed = fileName.trim();
        if (trimmed.isBlank() || trimmed.contains("..")) {
            return false;
        }
        return trimmed.matches("^[A-Za-z0-9][A-Za-z0-9._-]*\\.yml$");
    }

    private LangAutoFillResult autoFillMissingLangKeys(Path file, String name) {
        if (file == null) {
            return new LangAutoFillResult(0, null, "-");
        }

        String fileName = name == null || name.isBlank() ? "zh_CN.yml" : name.trim();
        String resourcePath = "lang/" + fileName;
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            resourcePath = "lang/zh_CN.yml";
            stream = plugin.getResource(resourcePath);
        }
        if (stream == null) {
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
                if ("prefix".equalsIgnoreCase(key) || current.contains(key)) {
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
}
