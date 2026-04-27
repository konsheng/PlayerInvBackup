package org.playerinvbackup.backup.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 该测试文件用于验证备份预览领取模式配置的读取结果
 * 覆盖缺失配置 显式开启和内置模板默认值场景
 * 确保 claim-once 在未配置时默认关闭并且可以正确解析
 */
class GuiPreviewSettingsTest {
    @Test
    // 验证缺少 gui.preview.claim-once 配置时
    // 会回退到默认的无限拿模式
    void claimOnceDefaultsToFalseWhenConfigIsMissing() {
        YamlConfiguration config = new YamlConfiguration();

        GuiPreviewSettings settings = GuiPreviewSettings.fromConfig(config);

        assertFalse(settings.claimOnce());
    }

    @Test
    // 验证显式配置 gui.preview.claim-once 为 true 时
    // 会切换到一次性领取模式
    void claimOnceReadsExplicitTrueValue() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gui.preview.claim-once", true);

        GuiPreviewSettings settings = GuiPreviewSettings.fromConfig(config);

        assertTrue(settings.claimOnce());
    }

    @Test
    // 验证内置英文和中文配置模板中的 claim-once 默认值
    // 都保持为 false
    void bundledConfigTemplatesUseInfiniteClaimByDefault() {
        assertFalse(loadBundledConfig("config.yml").claimOnce());
        assertFalse(loadBundledConfig("config.zh_CN.yml").claimOnce());
    }

    private static GuiPreviewSettings loadBundledConfig(String resourceName) {
        try (InputStream input = Objects.requireNonNull(
                GuiPreviewSettingsTest.class.getClassLoader().getResourceAsStream(resourceName),
                resourceName
        )) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return GuiPreviewSettings.fromConfig(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load resource: " + resourceName, e);
        }
    }
}
