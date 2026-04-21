package org.playerinvbackup.backup.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * PostgreSQL 连接配置
 *
 * <p>支持两种方式:
 * 1) 直接填写 storage.postgresql.url
 * 2) 填写 host/port/database + parameters, 自动拼接 JDBC URL
 */
public record PostgresqlConfig(
        String url,
        String host,
        int port,
        String database,
        String username,
        String password,
        String parameters,
        String tablePrefix
) {
    public static PostgresqlConfig from(FileConfiguration config) {
        String url = normalizeBlankToNull(config.getString("storage.postgresql.url", null));
        String host = normalizeBlankToDefault(config.getString("storage.postgresql.host", "127.0.0.1"), "127.0.0.1");
        int port = Math.max(1, config.getInt("storage.postgresql.port", 5432));
        String database = normalizeBlankToDefault(config.getString("storage.postgresql.database", "playerinvbackup"), "playerinvbackup");
        String username = normalizeBlankToDefault(config.getString("storage.postgresql.username", "postgres"), "postgres");
        String password = config.getString("storage.postgresql.password", "");
        String parameters = normalizeBlankToNull(config.getString(
                "storage.postgresql.parameters",
                "sslmode=disable"
        ));
        String tablePrefix = config.getString("storage.postgresql.table-prefix", "pib_");
        if (tablePrefix == null) {
            tablePrefix = "pib_";
        } else {
            tablePrefix = tablePrefix.trim();
        }
        return new PostgresqlConfig(url, host, port, database, username, password == null ? "" : password, parameters, tablePrefix);
    }

    /**
     * 获取 JDBC URL
     */
    public String jdbcUrl() {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:postgresql://")
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
