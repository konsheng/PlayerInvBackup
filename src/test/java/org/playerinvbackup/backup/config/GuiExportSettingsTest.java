package org.playerinvbackup.backup.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * 该测试文件用于验证 GUI 潜影盒导出配置
 * 覆盖默认值 有效颜色 非潜影盒材质和内置模板同步场景
 */
class GuiExportSettingsTest {
    @Test
    void exportShulkerMaterialDefaultsToPlainShulkerBox() {
        GuiExportSettings settings = GuiExportSettings.fromConfig(null, null, new YamlConfiguration());

        assertEquals(Material.SHULKER_BOX, settings.shulkerBoxMaterial());
    }

    @Test
    void exportShulkerMaterialAcceptsColoredShulkerBoxes() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gui.backup-view.export.shulker-box-material", "blue_shulker_box");

        GuiExportSettings settings = GuiExportSettings.fromConfig(null, null, config);

        assertEquals(Material.BLUE_SHULKER_BOX, settings.shulkerBoxMaterial());
    }

    @Test
    void exportShulkerMaterialRejectsNonShulkerMaterials() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gui.backup-view.export.shulker-box-material", "CHEST");

        GuiExportSettings settings = GuiExportSettings.fromConfig(null, null, config);

        assertEquals(Material.SHULKER_BOX, settings.shulkerBoxMaterial());
    }

    @Test
    void bundledConfigTemplatesContainExportDefaults() {
        assertBundledConfigHasDefaultExportSettings("config.yml");
        assertBundledConfigHasDefaultExportSettings("config.zh_CN.yml");
    }

    private static void assertBundledConfigHasDefaultExportSettings(String resourceName) {
        GuiExportSettings settings = loadBundledExportSettings(resourceName);

        assertEquals(Material.SHULKER_BOX, settings.shulkerBoxMaterial());
        assertTrue(GuiExportSettings.isAllowedShulkerBox(settings.shulkerBoxMaterial()));
    }

    private static GuiExportSettings loadBundledExportSettings(String resourceName) {
        try (InputStream input = Objects.requireNonNull(
                GuiExportSettingsTest.class.getClassLoader().getResourceAsStream(resourceName),
                resourceName
        )) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return GuiExportSettings.fromConfig(null, null, config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load resource: " + resourceName, e);
        }
    }
}
