package org.playerinvbackup.backup.store.postgresql;

import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractPooledJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.JdbcPoolSettings;
import org.playerinvbackup.backup.store.jdbc.PostgresqlDialect;

/**
 * PostgreSQL 存储实现
 */
public final class PostgresqlBackupStore extends AbstractPooledJdbcBackupStore {
    public PostgresqlBackupStore(
            String jdbcUrl,
            String username,
            String password,
            SqlTableNames tables,
            JdbcPoolSettings poolSettings
    ) {
        super(new PostgresqlDialect(), tables, jdbcUrl, username, password, "PlayerInvBackup-PostgreSQL", poolSettings);
    }
}
