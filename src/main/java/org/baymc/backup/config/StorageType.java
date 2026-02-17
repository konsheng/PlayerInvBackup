package org.baymc.backup.config;

/**
 * 存储后端类型
 *
 * <p>local: 文件夹存储(每个备份一个二进制快照文件 + 元数据 yml)
 * sqlite: 单文件数据库存储
 * mysql: MySQL/MariaDB 数据库存储
 * h2: H2 文件数据库存储
 */
public enum StorageType {
    LOCAL,
    SQLITE,
    MYSQL,
    H2;

    public static StorageType fromConfigValue(String value) {
        if (value == null) {
            return SQLITE;
        }
        return switch (value.trim().toLowerCase()) {
            case "local" -> LOCAL;
            case "sqlite" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            case "h2" -> H2;
            default -> SQLITE;
        };
    }
}
