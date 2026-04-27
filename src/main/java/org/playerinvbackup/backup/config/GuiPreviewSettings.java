package org.playerinvbackup.backup.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * GUI 备份预览配置
 */
public record GuiPreviewSettings(boolean claimOnce) {
    public static GuiPreviewSettings fromConfig(FileConfiguration config) {
        return new GuiPreviewSettings(config.getBoolean("gui.preview.claim-once", false));
    }
}
