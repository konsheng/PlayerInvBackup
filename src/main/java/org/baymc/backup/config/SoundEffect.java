package org.baymc.backup.config;

import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.text.Lang;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * 声音效果配置
 *
 * <p>用于 GUI 按钮点击反馈等场景, 可在 {@code config.yml} 里关闭或自定义
 */
public record SoundEffect(
        Sound sound,
        float volume,
        float pitch
) {
    public static SoundEffect disabled() {
        return new SoundEffect(null, 1.0f, 1.0f);
    }

    public boolean enabled() {
        return sound != null;
    }

    public static SoundEffect fromConfigOrFallback(
            Plugin plugin,
            Lang lang,
            ConfigurationSection section,
            String path,
            SoundEffect fallback
    ) {
        SoundEffect safeFallback = fallback == null ? disabled() : fallback;
        if (section == null || path == null || path.isBlank() || !hasExplicitConfig(section, path)) {
            return safeFallback;
        }

        String rawName = section.getString(path + ".name", null);
        if (rawName == null || rawName.isBlank() || rawName.equalsIgnoreCase("inherit")) {
            return safeFallback;
        }

        return fromConfig(
                plugin,
                lang,
                section,
                path,
                safeFallback.enabled() ? safeFallback.sound().name() : "none",
                safeFallback.volume(),
                safeFallback.pitch()
        );
    }

    public static SoundEffect fromConfig(
            Plugin plugin,
            Lang lang,
            ConfigurationSection section,
            String path,
            String defaultName,
            float defaultVolume,
            float defaultPitch
    ) {
        if (section == null) {
            return disabled();
        }

        String name = section.getString(path + ".name", defaultName);
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) {
            return disabled();
        }

        Sound sound;
        try {
            sound = Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (plugin != null && lang != null) {
                plugin.getLogger().warning(lang.plain(
                        "console.sound.invalid",
                        Placeholder.unparsed("path", path),
                        Placeholder.unparsed("name", name)
                ));
            }
            return disabled();
        }

        double volume = section.getDouble(path + ".volume", defaultVolume);
        double pitch = section.getDouble(path + ".pitch", defaultPitch);

        return new SoundEffect(
                sound,
                (float) clamp(volume, 0.0, 10.0),
                (float) clamp(pitch, 0.0, 2.0)
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static boolean hasExplicitConfig(ConfigurationSection section, String path) {
        return section.contains(path, true)
                || section.contains(path + ".name", true)
                || section.contains(path + ".volume", true)
                || section.contains(path + ".pitch", true);
    }
}
