package org.playerinvbackup.backup.store.mysql;

import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractPooledJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.MysqlDialect;

/**
 * MySQL / MariaDB 存储实现
 */
public final class MysqlBackupStore extends AbstractPooledJdbcBackupStore {
    public MysqlBackupStore(String jdbcUrl, String username, String password, SqlTableNames tables) {
        super(new MysqlDialect(), tables, jdbcUrl, username, password, "PlayerInvBackup-MySQL");
    }
}
