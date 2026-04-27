package org.playerinvbackup.backup.config;

import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.playerinvbackup.backup.store.jdbc.JdbcPoolSettings;
import org.playerinvbackup.backup.text.Lang;

/**
 * MySQL 连接配置
 *
 * <p>支持两种方式
 * 1) 直接填写 storage.mysql.url
 * 2) 填写 host/port/database + parameters, 自动拼接成 JDBC URL
 */
public record MysqlConfig(
        String url,
        String host,
        int port,
        String database,
        String username,
        String password,
        String parameters,
        String tablePrefix,
        JdbcPoolSettings poolSettings
) {
    public static MysqlConfig from(FileConfiguration config, Logger logger, Lang lang) {
        String url = normalizeBlankToNull(config.getString("storage.mysql.url", null));
        String host = normalizeBlankToDefault(config.getString("storage.mysql.host", "127.0.0.1"), "127.0.0.1");
        int port = Math.max(1, config.getInt("storage.mysql.port", 3306));
        String database = normalizeBlankToDefault(config.getString("storage.mysql.database", "playerinvbackup"), "playerinvbackup");
        String username = normalizeBlankToDefault(config.getString("storage.mysql.username", "root"), "root");
        String password = config.getString("storage.mysql.password", "");
        String parameters = normalizeBlankToNull(config.getString(
                "storage.mysql.parameters",
                "useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
        ));
        String tablePrefix = config.getString("storage.mysql.table-prefix", "pib_");
        if (tablePrefix == null) {
            tablePrefix = "pib_";
        } else {
            tablePrefix = tablePrefix.trim();
        }
        JdbcPoolSettings poolSettings = JdbcPoolSettings.fromConfig(config, "storage.mysql.pool", logger, lang);
        return new MysqlConfig(
                url,
                host,
                port,
                database,
                username,
                password == null ? "" : password,
                parameters,
                tablePrefix,
                poolSettings
        );
    }

    /**
     * 获取 JDBC URL
     *
     * <p>如果 url 不为空, 直接使用 url; 否则使用 host/port/database + parameters 拼接
     */
    public String jdbcUrl() {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:mysql://")
                .append(host)
                .append(":")
                .append(port)
                .append("/")
                .append(database);
        if (parameters != null && !parameters.isBlank()) {
            String p = parameters.trim();
            if (p.startsWith("?")) {
                p = p.substring(1);
            }
            sb.append("?").append(p);
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
