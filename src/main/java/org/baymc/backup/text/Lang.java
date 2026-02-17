package org.baymc.backup.text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 语言文件加载与 MiniMessage 解析器
 *
 * <p>负责:
 * 1) 从 yml 读取文本与列表文本
 * 2) 使用 MiniMessage 解析颜色与占位符
 * 3) 默认关闭斜体(避免 GUI/物品文本默认斜体), 但允许语言文件显式开启/关闭
 * 4) 对缺失键进行一次性告警, 避免刷屏
 */
public final class Lang {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final Path file;
    private final YamlConfiguration config;
    // 插件内置默认语言, 用于自动补全缺失键并作为兜底值
    private final YamlConfiguration defaults;
    private final MiniMessage miniMessage;
    private final Component prefix;
    // 提供给语言文件使用的 <prefix> 变量, 由语言文件自行决定是否引用
    private final TagResolver prefixPlaceholder;
    // 避免同一个缺失键反复刷日志
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();

    private Lang(Plugin plugin, Path file, YamlConfiguration config, YamlConfiguration defaults) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = Objects.requireNonNull(file, "file");
        this.config = Objects.requireNonNull(config, "config");
        this.defaults = defaults;
        this.miniMessage = MiniMessage.builder().strict(false).build();

        // prefix 是可选项: 不配置(键不存在)则视为不需要前缀, <prefix> 将解析为空
        // 需要前缀时, 由语言文件自行定义 prefix 的内容与样式
        String prefixRaw = config.getString("prefix", "");
        this.prefix = deserializeRaw(prefixRaw);
        this.prefixPlaceholder = Placeholder.component("prefix", this.prefix);
    }

    public static Lang load(Plugin plugin, Path file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        YamlConfiguration defaults = loadDefaults(plugin, file);
        return new Lang(plugin, file, yaml, defaults);
    }

    /**
     * 尝试从插件 Jar 内加载默认语言文件
     *
     * <p>优先加载与外部语言文件同名的资源(例如 lang/zh_CN.yml),
     * 如果不存在则回退到 lang/zh_CN.yml
     */
    private static YamlConfiguration loadDefaults(Plugin plugin, Path file) {
        if (plugin == null || file == null) {
            return null;
        }

        String name = String.valueOf(file.getFileName());
        String resourcePath = "lang/" + name;
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            resourcePath = "lang/zh_CN.yml";
            stream = plugin.getResource(resourcePath);
        }
        if (stream == null) {
            return null;
        }

        try (InputStream in = stream;
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            return null;
        }
    }

    public Path file() {
        return file;
    }

    public Component msg(String path, TagResolver... placeholders) {
        String raw = config.getString(path, null);
        if (raw == null) {
            String fallback = defaults == null ? null : defaults.getString(path, null);
            if (fallback != null) {
                tryAutoFill(path, fallback);
                raw = fallback;
            }
        }
        if (raw == null) {
            warnMissing("console.lang.missing-key", path);
            Component fallback = internalMsg(
                    "errors.lang-key-missing",
                    Placeholder.unparsed("key", path)
            );
            return fallback == null ? Component.empty() : fallback;
        }
        return deserialize(raw, placeholders);
    }

    /**
     * 获取语言键对应的纯文本(用于控制台输出等场景)
     */
    public String plain(String path, TagResolver... placeholders) {
        return PLAIN_TEXT.serialize(msg(path, placeholders));
    }

    private record ResolvedStringList(List<String> list, boolean keyIsSet) {
    }

    private ResolvedStringList resolveStringList(String path) {
        List<String> raw = config.getStringList(path);
        boolean keyIsSet = config.isSet(path);

        if (raw.isEmpty() && !keyIsSet) {
            List<String> fallback = defaults == null ? List.of() : defaults.getStringList(path);
            if (!fallback.isEmpty()) {
                tryAutoFill(path, fallback);
                raw = fallback;
                keyIsSet = true;
            }
        }
        return new ResolvedStringList(raw, keyIsSet);
    }

    public List<Component> msgList(String path, TagResolver... placeholders) {
        ResolvedStringList resolved = resolveStringList(path);
        List<String> raw = resolved.list();
        if (raw.isEmpty()) {
            if (resolved.keyIsSet()) {
                // 允许管理员把列表设置为空, 用于“关闭”某些多行提示
                return List.of();
            }
            warnMissing("console.lang.missing-list-key", path);
            Component fallback = internalMsg(
                    "errors.lang-key-missing",
                    Placeholder.unparsed("key", path)
            );
            return fallback == null ? List.of() : List.of(fallback);
        }
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            out.add(deserialize(line, placeholders));
        }
        return out;
    }

    public List<String> rawList(String path) {
        ResolvedStringList resolved = resolveStringList(path);
        List<String> raw = resolved.list();
        if (raw.isEmpty()) {
            if (resolved.keyIsSet()) {
                return List.of();
            }
            warnMissing("console.lang.missing-list-key", path);
            return List.of();
        }
        return raw;
    }

    public String raw(String path) {
        String raw = config.getString(path, null);
        if (raw == null) {
            String fallback = defaults == null ? null : defaults.getString(path, null);
            if (fallback != null) {
                tryAutoFill(path, fallback);
                return fallback;
            }
            warnMissing("console.lang.missing-key", path);
            String none = defaults == null ? null : defaults.getString("common.none", null);
            return none == null ? "" : none;
        }
        return raw;
    }

    /**
     * 尝试把缺失键写回语言文件, 让管理员后续可直接编辑
     *
     * <p>这是一个 best-effort 行为: 写入失败不会影响插件继续运行
     */
    private void tryAutoFill(String path, Object value) {
        if (path == null || path.isBlank() || value == null) {
            return;
        }
        try {
            config.set(path, value);
            config.save(file.toFile());
        } catch (Exception e) {
            String message = internalPlain(
                    "console.lang.autofill-write-failed",
                    Placeholder.unparsed("key", path),
                    Placeholder.unparsed("file", String.valueOf(file)),
                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
            );
            if (message != null && !message.isBlank()) {
                plugin.getLogger().log(Level.WARNING, message, e);
            } else {
                plugin.getLogger().log(Level.WARNING, String.valueOf(e.getMessage()), e);
            }
        }
    }

    private Component deserialize(String raw, TagResolver... placeholders) {
        String original = raw;
        raw = normalizeLegacyTags(raw);
        try {
            TagResolver[] resolvers = withPrefix(placeholders);
            return applyDefaultStyles(miniMessage.deserialize(raw, resolvers));
        } catch (Exception e) {
            String message = internalPlain(
                    "console.lang.parse-failed",
                    Placeholder.unparsed("file", String.valueOf(file)),
                    Placeholder.unparsed("text", String.valueOf(original))
            );
            if (message != null && !message.isBlank()) {
                plugin.getLogger().log(Level.WARNING, message, e);
            } else {
                plugin.getLogger().log(Level.WARNING, String.valueOf(e.getMessage()), e);
            }
            return applyDefaultStyles(Component.text(raw));
        }
    }

    /**
     * 从内置默认语言里读取消息, 避免“语言缺失”的场景下递归依赖外部语言文件
     */
    private Component internalMsg(String path, TagResolver... placeholders) {
        if (defaults == null || path == null || path.isBlank()) {
            return null;
        }
        String raw = defaults.getString(path, null);
        if (raw == null) {
            return null;
        }
        try {
            TagResolver[] resolvers = withPrefix(placeholders);
            return applyDefaultStyles(miniMessage.deserialize(normalizeLegacyTags(raw), resolvers));
        } catch (Exception e) {
            return applyDefaultStyles(Component.text(raw));
        }
    }

    private String internalPlain(String path, TagResolver... placeholders) {
        Component component = internalMsg(path, placeholders);
        if (component == null) {
            return null;
        }
        return PLAIN_TEXT.serialize(component);
    }

    private void warnMissing(String internalMessageKey, String missingKey) {
        if (internalMessageKey == null || internalMessageKey.isBlank() || missingKey == null || missingKey.isBlank()) {
            return;
        }
        if (!warnedMissingKeys.add(missingKey)) {
            return;
        }
        String message = internalPlain(
                internalMessageKey,
                Placeholder.unparsed("key", missingKey),
                Placeholder.unparsed("file", String.valueOf(file))
        );
        if (message != null && !message.isBlank()) {
            plugin.getLogger().warning(message);
        }
    }

    private TagResolver[] withPrefix(TagResolver... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return new TagResolver[]{prefixPlaceholder};
        }
        TagResolver[] out = new TagResolver[placeholders.length + 1];
        out[0] = prefixPlaceholder;
        System.arraycopy(placeholders, 0, out, 1, placeholders.length);
        return out;
    }

    private static String normalizeLegacyTags(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return raw
                .replace("<storageName>", "<storage_name>")
                .replace("<storagePath>", "<storage_path>")
                .replace("<backupId>", "<backup_id>")
                .replace("<notExecuted>", "<not_executed>");
    }

    private Component deserializeRaw(String raw) {
        try {
            return applyDefaultStyles(miniMessage.deserialize(raw == null ? "" : raw));
        } catch (Exception e) {
            return applyDefaultStyles(Component.text(raw == null ? "" : raw));
        }
    }

    private static Component applyDefaultStyles(Component component) {
        if (component == null) {
            return Component.empty();
        }

        // Minecraft 的物品显示名/lore 在未指定时通常默认斜体
        // 这里把“默认值”设为非斜体, 但不覆盖语言文件显式设置的 <italic:true>/<italic:false>
        if (component.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            component = component.decoration(TextDecoration.ITALIC, false);
        }
        return component;
    }
}
