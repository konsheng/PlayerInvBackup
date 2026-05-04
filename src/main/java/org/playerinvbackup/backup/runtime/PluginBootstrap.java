package org.playerinvbackup.backup.runtime;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/**
 * 负责首次启动时的引导流程, 在正常运行时创建前完成基础准备
 *
 * <p>这里处理启动前检查, 默认配置释放, 轻量语言初始化和 banner 输出
 * 真正的运行时装配仍然交给 ReloadCoordinator, 这样首次启动和后续 reload 走同一套装配路径
 */
public final class PluginBootstrap {
    private final PlayerInvBackupPlugin plugin;
    private final ReloadCoordinator reloadCoordinator;

    public PluginBootstrap(PlayerInvBackupPlugin plugin, ReloadCoordinator reloadCoordinator) {
        this.plugin = plugin;
        this.reloadCoordinator = reloadCoordinator;
    }

    /**
     * 执行首次启动引导
     *
     * <p>如果服务端不受支持, 这里会直接禁用插件并终止后续流程
     * 否则会先完成默认配置释放和启动横幅输出, 然后走一次标准 reload 来创建首个 runtime
     */
    public ReloadCoordinator.ReloadResult bootstrap() {
        if (checkUnsupportedServerAndDisable()) {
            return null;
        }

        saveLocalizedDefaultConfig();
        saveLocalizedDefaultSounds();
        Lang startupLang = initializeStartupLang();
        plugin.setBootstrapLang(startupLang);
        Chat.init(startupLang);
        logStartupBanner();
        return reloadCoordinator.reload(null, null);
    }

    private Lang initializeStartupLang() {
        plugin.reloadConfig();
        String languageFile = plugin.getConfig().getString("language");
        if (languageFile != null) {
            languageFile = languageFile.trim();
        }
        return reloadCoordinator.loadLang(languageFile);
    }

    private void logStartupBanner() {
        Chat.plainList(
                Bukkit.getConsoleSender(),
                "console.plugin.banner",
                Placeholder.unparsed("version", plugin.statusPluginVersion()),
                Placeholder.unparsed("server", Bukkit.getName())
        );
    }

    private void saveLocalizedDefaultConfig() {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path configFile = dataFolder.resolve("config.yml");
        if (Files.exists(configFile)) {
            return;
        }

        try {
            Files.createDirectories(dataFolder);
        } catch (Exception ignored) {
        }

        String resourcePath = "zh".equalsIgnoreCase(Locale.getDefault().getLanguage())
                ? "config.zh_CN.yml"
                : "config.yml";
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.saveDefaultConfig();
                return;
            }
            Files.copy(in, configFile);
        } catch (Exception e) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to save default config from " + resourcePath + ", falling back to bundled config.yml",
                    e
            );
            plugin.saveDefaultConfig();
        }
    }

    private void saveLocalizedDefaultSounds() {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path soundsFile = dataFolder.resolve("sounds.yml");
        if (Files.exists(soundsFile)) {
            return;
        }

        try {
            Files.createDirectories(dataFolder);
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
        } catch (Exception e) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to save default sounds from " + resourcePath + ", falling back to bundled sounds.yml",
                    e
            );
        }

        try (InputStream in = plugin.getResource("sounds.yml")) {
            if (in != null) {
                Files.copy(in, soundsFile);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save default sounds.yml", e);
        }
    }

    private boolean checkUnsupportedServerAndDisable() {
        String serverName = String.valueOf(Bukkit.getName());
        String versionText = String.valueOf(Bukkit.getVersion());

        String serverNameLower = serverName.toLowerCase(Locale.ROOT);
        String versionTextLower = versionText.toLowerCase(Locale.ROOT);

        boolean unsupported = serverNameLower.contains("spigot")
                || serverNameLower.contains("craftbukkit")
                || versionTextLower.contains("spigot")
                || versionTextLower.contains("craftbukkit");
        if (!unsupported) {
            return false;
        }

        boolean chinese = "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
        // 这里特意使用旧版字符串颜色输出, 让不兼容核心也能看到关闭提示
        String color = ChatColor.YELLOW.toString();
        String prefix = color + "[PlayerInvBackup] ";
        if (chinese) {
            Bukkit.getConsoleSender().sendMessage(prefix + "========================================");
            Bukkit.getConsoleSender().sendMessage(prefix + "检测到不受支持的服务端");
            Bukkit.getConsoleSender().sendMessage(prefix + "当前服务端名称: " + serverName);
            Bukkit.getConsoleSender().sendMessage(prefix + "当前版本信息: " + versionText);
            Bukkit.getConsoleSender().sendMessage(prefix + "PlayerInvBackup 不支持 Spigot 或 CraftBukkit");
            Bukkit.getConsoleSender().sendMessage(prefix + "请使用 Paper, Purpur, Leaf, Folia 或其他兼容服务端");
            Bukkit.getConsoleSender().sendMessage(prefix + "插件已自动关闭");
            Bukkit.getConsoleSender().sendMessage(prefix + "========================================");
        } else {
            Bukkit.getConsoleSender().sendMessage(prefix + "========================================");
            Bukkit.getConsoleSender().sendMessage(prefix + "Detected unsupported server software");
            Bukkit.getConsoleSender().sendMessage(prefix + "Server name: " + serverName);
            Bukkit.getConsoleSender().sendMessage(prefix + "Server version: " + versionText);
            Bukkit.getConsoleSender().sendMessage(prefix + "PlayerInvBackup does not support Spigot or CraftBukkit");
            Bukkit.getConsoleSender().sendMessage(prefix + "Please use Paper, Purpur, Leaf, Folia, or another compatible server");
            Bukkit.getConsoleSender().sendMessage(prefix + "The plugin has been disabled automatically");
            Bukkit.getConsoleSender().sendMessage(prefix + "========================================");
        }

        Bukkit.getPluginManager().disablePlugin(plugin);
        return true;
    }
}
