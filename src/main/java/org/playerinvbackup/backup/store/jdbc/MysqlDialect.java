package org.playerinvbackup.backup.store.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * MySQL / MariaDB 方言
 */
public final class MysqlDialect implements JdbcDialect {
    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String blobType() {
        return "MEDIUMBLOB";
    }

    @Override
    public String booleanType() {
        return "TINYINT(1) NOT NULL DEFAULT 0";
    }

    @Override
    public String doubleType() {
        return "DOUBLE";
    }

    @Override
    public String noteType() {
        return "TEXT";
    }

    @Override
    public String worldNameType() {
        return "VARCHAR(255)";
    }

    @Override
    public String uuidType() {
        return "CHAR(36)";
    }

    @Override
    public String sha256Type() {
        return "CHAR(64)";
    }

    @Override
    public String trueLiteral() {
        return "1";
    }

    @Override
    public String falseLiteral() {
        return "0";
    }

    @Override
    public void bindBoolean(PreparedStatement statement, int index, boolean value) throws SQLException {
        statement.setInt(index, value ? 1 : 0);
    }

    @Override
    public boolean readBoolean(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getInt(columnIndex) != 0;
    }

    @Override
    public boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = safeCatalog(connection);
        String[] patterns = {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (columnExists(meta, catalog, pattern, column)) {
                return true;
            }
        }
        try (ResultSet rs = meta.getColumns(catalog, null, "%", null)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName == null || !tableName.equalsIgnoreCase(table)) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isIndexAlreadyExists(SQLException e) {
        if (e.getErrorCode() == 1061) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("duplicate") && lower.contains("key name");
    }

    @Override
    public boolean isConstraintViolation(SQLException e) {
        if (e.getErrorCode() == 1062) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("duplicate");
    }

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static boolean columnExists(DatabaseMetaData meta, String catalog, String tablePattern, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(catalog, null, tablePattern, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
