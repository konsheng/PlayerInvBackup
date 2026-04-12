package org.playerinvbackup.backup.store.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.MysqlDialect;

/**
 * MySQL / MariaDB 存储实现
 */
public final class MysqlBackupStore extends AbstractJdbcBackupStore {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MysqlBackupStore(String jdbcUrl, String username, String password, SqlTableNames tables) {
        super(new MysqlDialect(), tables);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    protected Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
