package org.playerinvbackup.backup.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.playerinvbackup.backup.text.Lang;

/**
 * 服务器来源配置
 */
public record ServerSettings(String id, Map<String, String> aliases) {
    private static final String DEFAULT_ID = "default";
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_.-]+$");

    public static ServerSettings fromConfig(FileConfiguration config) {
        return fromConfig(config, null, null);
    }

    public static ServerSettings fromConfig(FileConfiguration config, Logger logger, Lang lang) {
        String configuredId = normalize(config.getString("server.id", DEFAULT_ID));
        String id = DEFAULT_ID;
        if (configuredId != null && !configuredId.isBlank()) {
            if (VALID_ID.matcher(configuredId).matches()) {
                id = configuredId;
            } else {
                warn(
                        logger,
                        lang,
                        "console.config.server-id-invalid",
                        "Invalid server.id, using default: " + configuredId,
                        Placeholder.unparsed("value", configuredId)
                );
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        ConfigurationSection aliasesSection = config.getConfigurationSection("server.aliases");
        if (aliasesSection != null) {
            for (String rawKey : aliasesSection.getKeys(false)) {
                String key = normalize(rawKey);
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (!VALID_ID.matcher(key).matches()) {
                    warn(
                            logger,
                            lang,
                            "console.config.server-alias-key-invalid",
                            "Invalid server.aliases key ignored: " + key,
                            Placeholder.unparsed("key", key)
                    );
                    continue;
                }

                String value = normalize(aliasesSection.getString(rawKey));
                if (value == null || value.isBlank()) {
                    continue;
                }
                aliases.put(key, value);
            }
        }

        return new ServerSettings(id, Map.copyOf(aliases));
    }

    public String displayName(String serverId) {
        String normalizedId = normalize(serverId);
        if (normalizedId == null || normalizedId.isBlank()) {
            normalizedId = id;
        }
        String alias = aliases.get(normalizedId);
        if (alias == null || alias.isBlank()) {
            return normalizedId;
        }
        return alias;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void warn(
            Logger logger,
            Lang lang,
            String key,
            String fallbackMessage,
            TagResolver placeholder
    ) {
        if (logger == null) {
            return;
        }
        if (lang != null) {
            logger.warning(lang.plain(key, placeholder));
            return;
        }
        logger.warning(fallbackMessage);
    }
}
