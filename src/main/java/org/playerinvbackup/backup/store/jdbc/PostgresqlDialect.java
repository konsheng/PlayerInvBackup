package org.playerinvbackup.backup.store.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * PostgreSQL 方言
 */
public final class PostgresqlDialect implements JdbcDialect {
    @Override
    public String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public String blobType() {
        return "BYTEA";
    }

    @Override
    public String booleanType() {
        return "BOOLEAN NOT NULL DEFAULT FALSE";
    }

    @Override
    public String doubleType() {
        return "DOUBLE PRECISION";
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
    public boolean supportsCreateIndexIfNotExists() {
        return true;
    }

    @Override
    public boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String[] patterns = {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (columnExists(meta, pattern, column)) {
                return true;
            }
        }
        try (ResultSet rs = meta.getColumns(null, null, "%", null)) {
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
    public boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("duplicate");
    }

    private static boolean columnExists(DatabaseMetaData meta, String tablePattern, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, tablePattern, null)) {
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
