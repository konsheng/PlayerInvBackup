package org.playerinvbackup.backup.config;

import java.util.EnumMap;
import java.util.Map;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * GUI 按钮音效配置集合
 */
public record GuiButtonSounds(Map<GuiSoundAction, SoundEffect> effects) {
    public GuiButtonSounds {
        effects = Map.copyOf(effects);
    }

    public static GuiButtonSounds from(
            Plugin plugin,
            Lang lang,
            ConfigurationSection section,
            SoundEffect fallback
    ) {
        EnumMap<GuiSoundAction, SoundEffect> map = new EnumMap<>(GuiSoundAction.class);
        for (GuiSoundAction action : GuiSoundAction.values()) {
            map.put(action, SoundEffect.fromConfigOrFallback(plugin, lang, section, action.configPath(), fallback));
        }
        return new GuiButtonSounds(map);
    }

    public SoundEffect effectFor(GuiSoundAction action) {
        if (action == null) {
            return SoundEffect.disabled();
        }
        SoundEffect effect = effects.get(action);
        return effect == null ? SoundEffect.disabled() : effect;
    }
}
