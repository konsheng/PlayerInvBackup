package org.playerinvbackup.backup.store.h2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.playerinvbackup.backup.store.SqlTableNames;
import org.playerinvbackup.backup.store.jdbc.AbstractJdbcBackupStore;
import org.playerinvbackup.backup.store.jdbc.H2Dialect;

/**
 * H2 存储实现
 */
public final class H2BackupStore extends AbstractJdbcBackupStore {
    private final Path fileBase;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public H2BackupStore(Path fileBase, String jdbcUrl, String username, String password, SqlTableNames tables) {
        super(new H2Dialect(), tables);
        this.fileBase = fileBase;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    protected void beforeConnect() throws Exception {
        Path parent = fileBase.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    protected Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
