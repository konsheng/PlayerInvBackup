package org.playerinvbackup.backup.config;

import java.nio.file.Path;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * H2 连接配置
 *
 * <p>支持两种方式
 * 1) 直接填写 storage.h2.url
 * 2) 填写 file + parameters, 自动拼接成 JDBC URL (文件模式)
 */
public record H2Config(
        String url,
        Path file,
        String username,
        String password,
        String parameters,
        String tablePrefix
) {
    public static H2Config from(FileConfiguration config) {
        String url = normalizeBlankToNull(config.getString("storage.h2.url", null));
        Path file = Path.of(config.getString("storage.h2.file", "data/backups_h2"));
        String username = normalizeBlankToDefault(config.getString("storage.h2.username", "sa"), "sa");
        String password = config.getString("storage.h2.password", "");
        String parameters = normalizeBlankToNull(config.getString(
                "storage.h2.parameters",
                "MODE=MySQL;DATABASE_TO_UPPER=false"
        ));
        String tablePrefix = config.getString("storage.h2.table-prefix", "pib_");
        if (tablePrefix == null) {
            tablePrefix = "pib_";
        } else {
            tablePrefix = tablePrefix.trim();
        }
        return new H2Config(url, file, username, password == null ? "" : password, parameters, tablePrefix);
    }

    /**
     * 获取 JDBC URL
     *
     * <p>如果 url 不为空, 直接使用 url; 否则使用 file + parameters 拼接
     */
    public String jdbcUrl(Path resolvedFileBase) {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }

        // H2 的 file URL 建议使用正斜杠, 避免反斜杠转义问题
        String filePart = resolvedFileBase.toAbsolutePath().toString().replace('\\', '/');
        StringBuilder sb = new StringBuilder("jdbc:h2:file:").append(filePart);
        if (parameters != null && !parameters.isBlank()) {
            String p = parameters.trim();
            if (!p.startsWith(";")) {
                sb.append(';');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeBlankToDefault(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }
}
