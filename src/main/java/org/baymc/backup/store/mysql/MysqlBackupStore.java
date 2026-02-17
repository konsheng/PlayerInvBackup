package org.baymc.backup.store.mysql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SlotClaim;
import org.baymc.backup.domain.SlotType;
import org.baymc.backup.domain.TriggerType;
import org.baymc.backup.domain.UndeliveredClaim;
import org.baymc.backup.store.BackupQuery;
import org.baymc.backup.store.BackupStore;

/**
 * MySQL/MariaDB 存储实现
 *
 * <p>说明:
 * 1) 使用单连接 + synchronized, 配合插件的单线程 I/O 队列即可满足顺序写入
 * 2) snapshot/item 数据使用 MEDIUMBLOB, 避免 BLOB 64KB 限制导致写入失败
 */
public final class MysqlBackupStore implements BackupStore {
    private final Object lock = new Object();
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private Connection connection;

    public MysqlBackupStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public void init() throws Exception {
        synchronized (lock) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            connection.setAutoCommit(true);
            createSchema(connection);
            migrateSchema(connection);
        }
    }

    @Override
    public void saveBackup(BackupRecord record) throws Exception {
        synchronized (lock) {
            String sql = """
                    INSERT INTO backups(backup_id, player_uuid, created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                BackupMeta meta = record.meta();
                ps.setString(1, meta.backupId());
                ps.setString(2, meta.playerUuid().toString());
                ps.setLong(3, meta.createdAtMillis());
                ps.setString(4, meta.trigger().name());
                ps.setInt(5, meta.schemaVersion());
                ps.setString(6, meta.sha256Hex());
                ps.setBytes(7, record.snapshotBytes());
                ps.setInt(8, meta.snapshotSizeBytes());
                ps.setInt(9, meta.locked() ? 1 : 0);
                ps.setString(10, meta.note());
                ps.executeUpdate();
            }
        }
    }

    @Override
    public List<BackupMeta> listBackups(UUID playerUuid, BackupQuery query, int offset, int limit) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            StringBuilder sql = new StringBuilder("""
                    SELECT backup_id, created_at, trigger, schema_version, sha256, snapshot_size, locked, note
                    FROM backups
                    WHERE player_uuid=?
                    """);
            TriggerType triggerFilter = query == null ? null : query.trigger();
            long createdAfterMillis = query == null ? 0L : query.createdAfterMillis();
            if (triggerFilter != null) {
                sql.append(" AND trigger=?");
            }
            if (createdAfterMillis > 0) {
                sql.append(" AND created_at>=?");
            }
            sql.append("""
                    ORDER BY locked DESC, created_at DESC
                    LIMIT ? OFFSET ?
                    """);

            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, playerUuid.toString());
                if (triggerFilter != null) {
                    ps.setString(idx++, triggerFilter.name());
                }
                if (createdAfterMillis > 0) {
                    ps.setLong(idx++, createdAfterMillis);
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    List<BackupMeta> out = new ArrayList<>();
                    while (rs.next()) {
                        String backupId = rs.getString(1);
                        long createdAt = rs.getLong(2);
                        TriggerType trigger = TriggerType.valueOf(rs.getString(3));
                        int schemaVersion = rs.getInt(4);
                        String sha256 = rs.getString(5);
                        int size = rs.getInt(6);
                        boolean locked = rs.getInt(7) != 0;
                        String note = rs.getString(8);
                        out.add(new BackupMeta(
                                backupId,
                                playerUuid,
                                createdAt,
                                trigger,
                                schemaVersion,
                                sha256,
                                size,
                                locked,
                                note == null ? "" : note
                        ));
                    }
                    return out;
                }
            }
        }
    }

    @Override
    public Optional<BackupRecord> loadBackup(UUID playerUuid, String backupId) throws Exception {
        synchronized (lock) {
            String sql = """
                    SELECT created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note
                    FROM backups
                    WHERE player_uuid=? AND backup_id=?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, backupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    long createdAt = rs.getLong(1);
                    TriggerType trigger = TriggerType.valueOf(rs.getString(2));
                    int schemaVersion = rs.getInt(3);
                    String sha256 = rs.getString(4);
                    byte[] snapshot = rs.getBytes(5);
                    int size = rs.getInt(6);
                    boolean locked = rs.getInt(7) != 0;
                    String note = rs.getString(8);
                    BackupMeta meta = new BackupMeta(
                            backupId,
                            playerUuid,
                            createdAt,
                            trigger,
                            schemaVersion,
                            sha256,
                            size,
                            locked,
                            note == null ? "" : note
                    );
                    return Optional.of(new BackupRecord(meta, snapshot));
                }
            }
        }
    }

    @Override
    public boolean setBackupLocked(UUID playerUuid, String backupId, boolean locked) throws Exception {
        synchronized (lock) {
            String sql = "UPDATE backups SET locked=? WHERE player_uuid=? AND backup_id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, locked ? 1 : 0);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, backupId);
                return ps.executeUpdate() > 0;
            }
        }
    }

    @Override
    public boolean setBackupNote(UUID playerUuid, String backupId, String note) throws Exception {
        synchronized (lock) {
            String sql = "UPDATE backups SET note=? WHERE player_uuid=? AND backup_id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, note);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, backupId);
                return ps.executeUpdate() > 0;
            }
        }
    }

    @Override
    public List<SlotClaim> listClaims(UUID playerUuid, String backupId) throws Exception {
        synchronized (lock) {
            String sql = """
                    SELECT slot_type, slot_index, actor_uuid, claimed_at
                    FROM claims
                    WHERE backup_id=?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, backupId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SlotClaim> out = new ArrayList<>();
                    while (rs.next()) {
                        SlotType slotType = SlotType.valueOf(rs.getString(1));
                        int slotIndex = rs.getInt(2);
                        UUID actorUuid = UUID.fromString(rs.getString(3));
                        long claimedAt = rs.getLong(4);
                        out.add(new SlotClaim(backupId, slotType, slotIndex, actorUuid, claimedAt));
                    }
                    return out;
                }
            }
        }
    }

    @Override
    public boolean tryClaimSlot(
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            UUID actorUuid,
            String actorName,
            long claimedAtMillis,
            byte[] itemBytes
    ) throws Exception {
        synchronized (lock) {
            String sql = """
                    INSERT INTO claims(player_uuid, backup_id, slot_type, slot_index, actor_uuid, actor_name, claimed_at, item_blob, delivered, delivered_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, 0, NULL)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, backupId);
                ps.setString(3, slotType.name());
                ps.setInt(4, slotIndex);
                ps.setString(5, actorUuid.toString());
                ps.setString(6, actorName);
                ps.setLong(7, claimedAtMillis);
                ps.setBytes(8, itemBytes);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isConstraintViolation(e)) {
                    return false;
                }
                throw e;
            }
        }
    }

    @Override
    public List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            String sql = """
                    SELECT player_uuid, backup_id, slot_type, slot_index, actor_name, claimed_at, item_blob
                    FROM claims
                    WHERE actor_uuid=? AND delivered=0
                    ORDER BY claimed_at ASC
                    LIMIT ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, actorUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<UndeliveredClaim> out = new ArrayList<>();
                    while (rs.next()) {
                        UUID playerUuid = UUID.fromString(rs.getString(1));
                        String backupId = rs.getString(2);
                        SlotType slotType = SlotType.valueOf(rs.getString(3));
                        int slotIndex = rs.getInt(4);
                        String actorName = rs.getString(5);
                        long claimedAt = rs.getLong(6);
                        byte[] itemBytes = rs.getBytes(7);
                        out.add(new UndeliveredClaim(
                                playerUuid,
                                backupId,
                                slotType,
                                slotIndex,
                                actorUuid,
                                actorName,
                                claimedAt,
                                itemBytes
                        ));
                    }
                    return out;
                }
            }
        }
    }

    @Override
    public boolean markDelivered(
            UUID actorUuid,
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            long deliveredAtMillis
    ) throws Exception {
        synchronized (lock) {
            String sql = """
                    UPDATE claims
                    SET delivered=1, delivered_at=?
                    WHERE actor_uuid=? AND player_uuid=? AND backup_id=? AND slot_type=? AND slot_index=? AND delivered=0
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, deliveredAtMillis);
                ps.setString(2, actorUuid.toString());
                ps.setString(3, playerUuid.toString());
                ps.setString(4, backupId);
                ps.setString(5, slotType.name());
                ps.setInt(6, slotIndex);
                return ps.executeUpdate() > 0;
            }
        }
    }

    @Override
    public void purgeBackups(UUID playerUuid, int keepPerPlayer) throws Exception {
        if (keepPerPlayer <= 0) {
            return;
        }
        synchronized (lock) {
            connection.setAutoCommit(false);
            try {
                List<String> toDelete = new ArrayList<>();
                String selectSql = """
                        SELECT backup_id
                        FROM backups
                        WHERE player_uuid=? AND locked=0
                        ORDER BY created_at DESC
                        """;
                try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        int idx = 0;
                        while (rs.next()) {
                            idx++;
                            if (idx <= keepPerPlayer) {
                                continue;
                            }
                            toDelete.add(rs.getString(1));
                        }
                    }
                }

                try (PreparedStatement deleteClaims = connection.prepareStatement("DELETE FROM claims WHERE backup_id=?");
                     PreparedStatement deleteBackup = connection.prepareStatement("DELETE FROM backups WHERE backup_id=?")) {
                    for (String backupId : toDelete) {
                        if (hasUndeliveredClaims(connection, backupId)) {
                            continue;
                        }
                        deleteClaims.setString(1, backupId);
                        deleteClaims.executeUpdate();
                        deleteBackup.setString(1, backupId);
                        deleteBackup.executeUpdate();
                    }
                }

                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public void close() throws Exception {
        synchronized (lock) {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS backups(
                      backup_id VARCHAR(64) PRIMARY KEY,
                      player_uuid CHAR(36) NOT NULL,
                      created_at BIGINT NOT NULL,
                      trigger VARCHAR(32) NOT NULL,
                      schema_version INT NOT NULL,
                      sha256 CHAR(64) NOT NULL,
                      snapshot_blob MEDIUMBLOB NOT NULL,
                      snapshot_size INT NOT NULL,
                      locked TINYINT(1) NOT NULL DEFAULT 0,
                      note TEXT
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS claims(
                      player_uuid CHAR(36) NOT NULL,
                      backup_id VARCHAR(64) NOT NULL,
                      slot_type VARCHAR(32) NOT NULL,
                      slot_index INT NOT NULL,
                      actor_uuid CHAR(36) NOT NULL,
                      actor_name VARCHAR(64),
                      claimed_at BIGINT NOT NULL,
                      item_blob MEDIUMBLOB NOT NULL,
                      delivered TINYINT(1) NOT NULL DEFAULT 0,
                      delivered_at BIGINT,
                      PRIMARY KEY(backup_id, slot_type, slot_index)
                    )
                    """);
        }

        // MySQL 不支持 CREATE INDEX IF NOT EXISTS, 这里通过捕获异常避免重复创建报错
        createIndexIgnoreExists(connection, "CREATE INDEX idx_backups_player_created ON backups(player_uuid, created_at)");
        createIndexIgnoreExists(connection, "CREATE INDEX idx_claims_backup ON claims(backup_id)");
        createIndexIgnoreExists(connection, "CREATE INDEX idx_claims_actor_delivered ON claims(actor_uuid, delivered)");
    }

    private static void migrateSchema(Connection connection) throws SQLException {
        ensureBackupColumn(connection, "locked", "TINYINT(1) NOT NULL DEFAULT 0");
        ensureBackupColumn(connection, "note", "TEXT");
    }

    private static void ensureBackupColumn(Connection connection, String column, String definition) throws SQLException {
        if (columnExists(connection, "backups", column)) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE backups ADD COLUMN " + column + " " + definition);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = safeCatalog(connection);

        // 某些驱动对 tableNamePattern 大小写敏感, 这里尝试常见大小写并在最后做一次全表扫描兜底
        String[] patterns = {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (columnExists(meta, catalog, pattern, column)) {
                return true;
            }
        }

        try (ResultSet rs = meta.getColumns(catalog, null, "%", null)) {
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

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static boolean columnExists(DatabaseMetaData meta, String catalog, String tablePattern, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(catalog, null, tablePattern, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void createIndexIgnoreExists(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (isIndexAlreadyExists(e)) {
                return;
            }
            throw e;
        }
    }

    private static boolean isIndexAlreadyExists(SQLException e) {
        // MySQL: Duplicate key name
        if (e.getErrorCode() == 1061) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("duplicate") && m.contains("key name");
    }

    private static boolean hasUndeliveredClaims(Connection connection, String backupId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM claims WHERE backup_id=? AND delivered=0 LIMIT 1"
        )) {
            ps.setString(1, backupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isConstraintViolation(SQLException e) {
        // MySQL: duplicate entry for key (primary key/unique key)
        if (e.getErrorCode() == 1062) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("duplicate");
    }
}
