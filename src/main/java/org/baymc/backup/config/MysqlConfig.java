package org.baymc.backup.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * MySQL 连接配置
 *
 * <p>支持两种方式:
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
        String parameters
) {
    public static MysqlConfig from(FileConfiguration config) {
        String url = normalizeBlankToNull(config.getString("storage.mysql.url", null));
        String host = normalizeBlankToDefault(config.getString("storage.mysql.host", "127.0.0.1"), "127.0.0.1");
        int port = Math.max(1, config.getInt("storage.mysql.port", 3306));
        String database = normalizeBlankToDefault(config.getString("storage.mysql.database", "baymcbackup"), "baymcbackup");
        String username = normalizeBlankToDefault(config.getString("storage.mysql.username", "root"), "root");
        String password = config.getString("storage.mysql.password", "");
        String parameters = normalizeBlankToNull(config.getString(
                "storage.mysql.parameters",
                "useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
        ));
        return new MysqlConfig(url, host, port, database, username, password == null ? "" : password, parameters);
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
