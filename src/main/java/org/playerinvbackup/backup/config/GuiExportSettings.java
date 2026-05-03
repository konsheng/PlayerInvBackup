package org.playerinvbackup.backup.config;

import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.playerinvbackup.backup.text.Lang;

/**
 * GUI 备份导出配置
 */
public record GuiExportSettings(Material shulkerBoxMaterial) {
    private static final Material DEFAULT_SHULKER_BOX = Material.SHULKER_BOX;
    private static final String MATERIAL_PATH = "gui.backup-view.export.shulker-box-material";

    public GuiExportSettings {
        if (!isAllowedShulkerBox(shulkerBoxMaterial)) {
            shulkerBoxMaterial = DEFAULT_SHULKER_BOX;
        }
    }

    public static GuiExportSettings fromConfig(Plugin plugin, Lang lang, FileConfiguration config) {
        if (config == null) {
            return new GuiExportSettings(DEFAULT_SHULKER_BOX);
        }

        String raw = config.getString(MATERIAL_PATH, DEFAULT_SHULKER_BOX.name());
        Material material = parseMaterial(raw);
        if (!isAllowedShulkerBox(material)) {
            warnInvalid(plugin, lang, raw);
            material = DEFAULT_SHULKER_BOX;
        }
        return new GuiExportSettings(material);
    }

    public static boolean isAllowedShulkerBox(Material material) {
        return material != null && material.name().endsWith("SHULKER_BOX");
    }

    private static Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void warnInvalid(Plugin plugin, Lang lang, String raw) {
        if (plugin == null) {
            return;
        }
        if (lang != null) {
            plugin.getLogger().warning(lang.plain(
                    "console.config.export-shulker-material-invalid",
                    Placeholder.unparsed("path", MATERIAL_PATH),
                    Placeholder.unparsed("value", String.valueOf(raw)),
                    Placeholder.unparsed("fallback", DEFAULT_SHULKER_BOX.name())
            ));
            return;
        }
        plugin.getLogger().warning(MATERIAL_PATH + " is invalid: " + raw + ", using " + DEFAULT_SHULKER_BOX.name());
    }
}
