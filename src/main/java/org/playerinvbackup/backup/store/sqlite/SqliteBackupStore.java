package org.playerinvbackup.backup.store.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.SqliteDialect;

/**
 * SQLite 存储实现
 */
public final class SqliteBackupStore extends AbstractJdbcBackupStore {
    private final Path dbFile;

    public SqliteBackupStore(Path dbFile, SqlTableNames tables) {
        super(new SqliteDialect(), tables);
        this.dbFile = dbFile;
    }

    @Override
    protected void beforeConnect() throws Exception {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    protected Connection openConnection() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
    }
}
