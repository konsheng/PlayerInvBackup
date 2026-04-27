package org.playerinvbackup.backup.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.SqlTableNames;

/**
 * 该测试文件用于验证 JDBC 存储在移除 schema 版本字段后的建表和读写行为
 * 覆盖 H2 SQLite MySQL 方言和 PostgreSQL 方言下的列结构检查
 * 确保新表不再创建 schema_version 并且备份元数据仍能正常保存读取
 */
class JdbcSchemaRemovalTest {
    private static final String SERVER_ID = "survival-1";

    @TempDir
    Path tempDir;

    @Test
    // 验证 H2 SQLite MySQL 方言和 PostgreSQL 方言初始化新表后
    // backups 表中都不会再出现 schema_version 且会创建 server_id 列
    void supportedJdbcSchemasDoNotCreateSchemaVersionColumn() throws Exception {
        try (TestJdbcStore h2 = new TestJdbcStore(
                new H2Dialect(),
                "jdbc:h2:mem:h2-" + UUID.randomUUID() + ";DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        )) {
            h2.init();
            assertFalse(h2.hasBackupColumn("schema_version"));
            assertTrue(h2.hasBackupColumn("server_id"));
        }

        try (TestJdbcStore sqlite = new TestJdbcStore(
                new SqliteDialect(),
                "jdbc:sqlite:" + tempDir.resolve("schema-removal.db"),
                "",
                ""
        )) {
            sqlite.init();
            assertFalse(sqlite.hasBackupColumn("schema_version"));
            assertTrue(sqlite.hasBackupColumn("server_id"));
        }

        try (TestJdbcStore mysql = new TestJdbcStore(
                new DriverOverrideDialect("org.h2.Driver", new MysqlDialect()),
                "jdbc:h2:mem:mysql-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        )) {
            mysql.init();
            assertFalse(mysql.hasBackupColumn("schema_version"));
            assertTrue(mysql.hasBackupColumn("server_id"));
        }

        try (TestJdbcStore postgresql = new TestJdbcStore(
                new DriverOverrideDialect("org.h2.Driver", new PostgresqlDialect()),
                "jdbc:h2:mem:postgresql-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        )) {
            postgresql.init();
            assertFalse(postgresql.hasBackupColumn("schema_version"));
            assertTrue(postgresql.hasBackupColumn("server_id"));
        }
    }

    @Test
    // 验证 JDBC 存储在移除 schema_version 后
    // 仍然可以正常保存并读取 server_id 以及完整备份元数据与快照字节
    void jdbcStoreSaveAndLoadWorksWithoutSchemaVersionField() throws Exception {
        try (TestJdbcStore store = new TestJdbcStore(
                new H2Dialect(),
                "jdbc:h2:mem:save-load-" + UUID.randomUUID() + ";DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        )) {
            store.init();

            UUID playerUuid = UUID.randomUUID();
            UUID killerUuid = UUID.randomUUID();
            byte[] snapshotBytes = new byte[]{4, 5, 6, 7};
            BackupMeta meta = new BackupMeta(
                    "backup-1",
                    playerUuid,
                    SERVER_ID,
                    1_000L,
                    TriggerType.DEATH,
                    "sha256-backup-1",
                    snapshotBytes.length,
                    true,
                    "note-1",
                    "world",
                    10.0,
                    64.0,
                    -5.0,
                    "world_nether",
                    20.0,
                    70.0,
                    30.0,
                    killerUuid,
                    "killer"
            );

            store.saveBackup(new BackupRecord(meta, snapshotBytes));

            BackupMeta listed = store.listBackups(playerUuid, BackupQuery.all(), 0, 10).getFirst();
            assertEquals("backup-1", listed.backupId());
            assertEquals(SERVER_ID, listed.serverId());
            assertEquals(TriggerType.DEATH, listed.trigger());
            assertEquals("sha256-backup-1", listed.sha256Hex());
            assertEquals(snapshotBytes.length, listed.snapshotSizeBytes());
            assertEquals("note-1", listed.note());
            assertEquals("world_nether", listed.targetWorldName());
            assertEquals(killerUuid, listed.killerPlayerUuid());
            assertEquals("killer", listed.killerPlayerName());

            BackupRecord loaded = store.loadBackup(playerUuid, "backup-1").orElseThrow();
            assertEquals("backup-1", loaded.meta().backupId());
            assertEquals(SERVER_ID, loaded.meta().serverId());
            assertEquals("world", loaded.meta().worldName());
            assertEquals(Double.valueOf(10.0), loaded.meta().locationX());
            assertEquals(Double.valueOf(64.0), loaded.meta().locationY());
            assertEquals(Double.valueOf(-5.0), loaded.meta().locationZ());
            assertEquals("world_nether", loaded.meta().targetWorldName());
            assertEquals(Double.valueOf(20.0), loaded.meta().targetLocationX());
            assertEquals(Double.valueOf(70.0), loaded.meta().targetLocationY());
            assertEquals(Double.valueOf(30.0), loaded.meta().targetLocationZ());
            assertEquals(killerUuid, loaded.meta().killerPlayerUuid());
            assertEquals("killer", loaded.meta().killerPlayerName());
            assertArrayEquals(snapshotBytes, loaded.snapshotBytes());
        }
    }

    private static final class TestJdbcStore extends AbstractJdbcBackupStore {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private TestJdbcStore(JdbcDialect dialect, String jdbcUrl, String username, String password) {
            super(dialect, new SqlTableNames("testp_"));
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        protected Connection openConnection() throws Exception {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }

        private boolean hasBackupColumn(String column) throws Exception {
            return withConnection(connection -> dialect().columnExists(connection, tables().backups(), column));
        }
    }

    private record DriverOverrideDialect(String driverClassName, JdbcDialect delegate) implements JdbcDialect {
        @Override
        public String blobType() {
            return delegate.blobType();
        }

        @Override
        public String booleanType() {
            return delegate.booleanType();
        }

        @Override
        public String doubleType() {
            return delegate.doubleType();
        }

        @Override
        public String noteType() {
            return delegate.noteType();
        }

        @Override
        public String worldNameType() {
            return delegate.worldNameType();
        }

        @Override
        public String backupIdType() {
            return delegate.backupIdType();
        }

        @Override
        public String uuidType() {
            return delegate.uuidType();
        }

        @Override
        public String triggerType() {
            return delegate.triggerType();
        }

        @Override
        public String sha256Type() {
            return delegate.sha256Type();
        }

        @Override
        public String actorNameType() {
            return delegate.actorNameType();
        }

        @Override
        public String trueLiteral() {
            return delegate.trueLiteral();
        }

        @Override
        public String falseLiteral() {
            return delegate.falseLiteral();
        }

        @Override
        public String playerCreatedIndexColumns() {
            return delegate.playerCreatedIndexColumns();
        }

        @Override
        public void initializeConnection(Connection connection) throws SQLException {
            delegate.initializeConnection(connection);
        }

        @Override
        public void bindBoolean(java.sql.PreparedStatement statement, int index, boolean value) throws SQLException {
            delegate.bindBoolean(statement, index, value);
        }

        @Override
        public boolean readBoolean(java.sql.ResultSet rs, int columnIndex) throws SQLException {
            return delegate.readBoolean(rs, columnIndex);
        }

        @Override
        public boolean supportsCreateIndexIfNotExists() {
            return delegate.supportsCreateIndexIfNotExists();
        }

        @Override
        public boolean columnExists(Connection connection, String table, String column) throws SQLException {
            return delegate.columnExists(connection, table, column);
        }

        @Override
        public boolean isIndexAlreadyExists(SQLException e) {
            return delegate.isIndexAlreadyExists(e);
        }

        @Override
        public boolean isConstraintViolation(SQLException e) {
            return delegate.isConstraintViolation(e);
        }
    }
}
