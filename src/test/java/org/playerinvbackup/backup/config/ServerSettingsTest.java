package org.playerinvbackup.backup.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 该测试文件用于验证备份来源服务器配置的读取和显示名转换行为
 * 覆盖默认值 别名映射 非法配置过滤和内置配置模板默认值场景
 * 确保 server.id 与 server.aliases 在不同输入下都能稳定得到预期结果
 */
class ServerSettingsTest {
    @Test
    // 验证缺少 server 配置时
    // 会回退到默认 server.id 且别名映射为空
    void defaultsToDefaultIdWhenConfigIsMissing() {
        YamlConfiguration config = new YamlConfiguration();

        ServerSettings settings = ServerSettings.fromConfig(config);

        assertEquals("default", settings.id());
        assertTrue(settings.aliases().isEmpty());
        assertEquals("default", settings.displayName(null));
        assertEquals("skyblock", settings.displayName("skyblock"));
    }

    @Test
    // 验证存在别名映射时
    // 会优先显示 aliases 中配置的服务器名称
    void displayNameUsesAliasesWhenPresent() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.id", "survival-1");
        config.set("server.aliases.survival-1", "生存一区");
        config.set("server.aliases.resource", "资源区");

        ServerSettings settings = ServerSettings.fromConfig(config);

        assertEquals("survival-1", settings.id());
        assertEquals("生存一区", settings.displayName("survival-1"));
        assertEquals("生存一区", settings.displayName(null));
        assertEquals("资源区", settings.displayName("resource"));
        assertEquals("skyblock", settings.displayName("skyblock"));
    }

    @Test
    // 验证非法 server.id 和非法 aliases 键
    // 会被自动忽略并回退到安全结果
    void invalidValuesAreIgnoredOrFallbackToDefault() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server.id", "bad id");
        config.set("server.aliases.good_id", "主服");
        config.set("server.aliases.bad key", "坏配置");
        config.set("server.aliases.blank", " ");

        ServerSettings settings = ServerSettings.fromConfig(config);

        assertEquals("default", settings.id());
        assertEquals("主服", settings.displayName("good_id"));
        assertFalse(settings.aliases().containsKey("bad key"));
        assertFalse(settings.aliases().containsKey("blank"));
        assertEquals("bad key", settings.displayName("bad key"));
    }

    @Test
    // 验证内置英文和中文配置模板中的 server 默认配置
    // 都会提供 default 服务器标识和可用的显示名称
    void bundledConfigTemplatesContainServerDefaults() {
        assertBundledConfigHasDefaultServerSettings("config.yml");
        assertBundledConfigHasDefaultServerSettings("config.zh_CN.yml");
    }

    private static void assertBundledConfigHasDefaultServerSettings(String resourceName) {
        ServerSettings settings = loadBundledServerSettings(resourceName);
        assertEquals("default", settings.id());
        assertFalse(settings.displayName("default").isBlank());
    }

    private static ServerSettings loadBundledServerSettings(String resourceName) {
        try (InputStream input = Objects.requireNonNull(
                ServerSettingsTest.class.getClassLoader().getResourceAsStream(resourceName),
                resourceName
        )) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return ServerSettings.fromConfig(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load resource: " + resourceName, e);
        }
    }
}
