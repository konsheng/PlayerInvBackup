package org.playerinvbackup.backup.store.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.PostgresqlDialect;

/**
 * PostgreSQL 存储实现
 */
public final class PostgresqlBackupStore extends AbstractJdbcBackupStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresqlBackupStore(String jdbcUrl, String username, String password, SqlTableNames tables) {
        super(new PostgresqlDialect(), tables);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    protected Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
