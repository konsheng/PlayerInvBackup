package org.playerinvbackup.backup.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * SQLite 方言
 */
public final class SqliteDialect implements JdbcDialect {
    @Override
    public String driverClassName() {
        return "org.sqlite.JDBC";
    }

    @Override
    public String blobType() {
        return "BLOB";
    }

    @Override
    public String booleanType() {
        return "INTEGER NOT NULL DEFAULT 0";
    }

    @Override
    public String doubleType() {
        return "REAL";
    }

    @Override
    public String noteType() {
        return "TEXT";
    }

    @Override
    public String worldNameType() {
        return "TEXT";
    }

    @Override
    public String backupIdType() {
        return "TEXT";
    }

    @Override
    public String uuidType() {
        return "TEXT";
    }

    @Override
    public String triggerType() {
        return "TEXT";
    }

    @Override
    public String sha256Type() {
        return "TEXT";
    }

    @Override
    public String actorNameType() {
        return "TEXT";
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
    public String playerCreatedIndexColumns() {
        return "player_uuid, created_at DESC";
    }

    @Override
    public void initializeConnection(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=3000");
        }
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
    public boolean supportsCreateIndexIfNotExists() {
        return true;
    }

    @Override
    public boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("unique");
    }
}
