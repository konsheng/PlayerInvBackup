package org.playerinvbackup.backup.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 方言接口
 *
 * <p>用于隔离不同数据库之间的少量 SQL 差异
 */
public interface JdbcDialect {
    String driverClassName();

    String blobType();

    String booleanType();

    String doubleType();

    String noteType();

    String worldNameType();

    default String backupIdType() {
        return "VARCHAR(64)";
    }

    default String uuidType() {
        return "VARCHAR(36)";
    }

    default String triggerType() {
        return "VARCHAR(32)";
    }

    default String sha256Type() {
        return "VARCHAR(64)";
    }

    default String actorNameType() {
        return "VARCHAR(64)";
    }

    default String trueLiteral() {
        return "TRUE";
    }

    default String falseLiteral() {
        return "FALSE";
    }

    default String playerCreatedIndexColumns() {
        return "player_uuid, created_at";
    }

    default void initializeConnection(Connection connection) throws SQLException {
    }

    default void bindBoolean(PreparedStatement statement, int index, boolean value) throws SQLException {
        statement.setBoolean(index, value);
    }

    default boolean readBoolean(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getBoolean(columnIndex);
    }

    default boolean supportsCreateIndexIfNotExists() {
        return false;
    }

    default void loadDriver() throws ClassNotFoundException {
        Class.forName(driverClassName());
    }

    default void createIndex(Connection connection, String indexName, String tableName, String columns) throws SQLException {
        String sql = supportsCreateIndexIfNotExists()
                ? "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + "(" + columns + ")"
                : "CREATE INDEX " + indexName + " ON " + tableName + "(" + columns + ")";
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (isIndexAlreadyExists(e)) {
                return;
            }
            throw e;
        }
    }

    boolean columnExists(Connection connection, String table, String column) throws SQLException;

    default boolean isIndexAlreadyExists(SQLException e) {
        return false;
    }

    default boolean isConstraintViolation(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
