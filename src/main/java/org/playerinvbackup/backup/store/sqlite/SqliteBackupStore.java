package org.playerinvbackup.backup.store.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.domain.UndeliveredClaim;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.BackupStore;

/**
 * SQLite 存储实现
 *
 * <p>特点:
 * 1) 单文件数据库, 便于备份与迁移
 * 2) 通过 locked/note 字段支持置顶显示与备注
 * 3) purgeBackups 会跳过已置顶备份与存在待投递物品的备份
 */
public final class SqliteBackupStore implements BackupStore {
    private final Object lock = new Object();
    private final Path dbFile;
    private Connection connection;

    public SqliteBackupStore(Path dbFile) {
        this.dbFile = dbFile;
    }

    @Override
    public void init() throws Exception {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        synchronized (lock) {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            connection.setAutoCommit(true);
            applyPragmas(connection);
            createSchema(connection);
            migrateSchema(connection);
        }
    }

    @Override
    public void saveBackup(BackupRecord record) throws Exception {
        synchronized (lock) {
            String sql = """
                    INSERT INTO backups(backup_id, player_uuid, created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note, world_name, location_x, location_y, location_z)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                ps.setString(11, meta.worldName());
                ps.setObject(12, meta.locationX());
                ps.setObject(13, meta.locationY());
                ps.setObject(14, meta.locationZ());
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
                    SELECT backup_id, created_at, trigger, schema_version, sha256, snapshot_size, locked, note, world_name, location_x, location_y, location_z
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
                        String worldName = rs.getString(9);
                        Double locationX = readNullableDouble(rs, 10);
                        Double locationY = readNullableDouble(rs, 11);
                        Double locationZ = readNullableDouble(rs, 12);
                        out.add(new BackupMeta(backupId, playerUuid, createdAt, trigger, schemaVersion, sha256, size, locked, note == null ? "" : note, worldName, locationX, locationY, locationZ));
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
                    SELECT created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note, world_name, location_x, location_y, location_z
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
                    String worldName = rs.getString(9);
                    Double locationX = readNullableDouble(rs, 10);
                    Double locationY = readNullableDouble(rs, 11);
                    Double locationZ = readNullableDouble(rs, 12);
                    BackupMeta meta = new BackupMeta(backupId, playerUuid, createdAt, trigger, schemaVersion, sha256, size, locked, note == null ? "" : note, worldName, locationX, locationY, locationZ);
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
    public void purgeBackups(UUID playerUuid, int keepPerPlayer, long keepAfterMillis) throws Exception {
        if (keepPerPlayer <= 0 && keepAfterMillis <= 0) {
            return;
        }
        synchronized (lock) {
            connection.setAutoCommit(false);
            try {
                Set<String> toDelete = new LinkedHashSet<>();
                if (keepAfterMillis > 0) {
                    String selectOldSql = """
                            SELECT backup_id
                            FROM backups
                            WHERE player_uuid=? AND locked=0 AND created_at < ?
                            """;
                    try (PreparedStatement ps = connection.prepareStatement(selectOldSql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setLong(2, keepAfterMillis);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String id = rs.getString(1);
                                if (id != null && !id.isBlank()) {
                                    toDelete.add(id);
                                }
                            }
                        }
                    }
                }

                if (keepPerPlayer > 0) {
                    String selectSql = """
                            SELECT backup_id
                            FROM backups
                            WHERE player_uuid=? AND locked=0
                            ORDER BY created_at DESC
                            LIMIT -1 OFFSET ?
                            """;
                    try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setInt(2, keepPerPlayer);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String id = rs.getString(1);
                                if (id != null && !id.isBlank()) {
                                    toDelete.add(id);
                                }
                            }
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

    private static void applyPragmas(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=3000");
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS backups(
                      backup_id TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      trigger TEXT NOT NULL,
                      schema_version INTEGER NOT NULL,
                      sha256 TEXT NOT NULL,
                      snapshot_blob BLOB NOT NULL,
                      snapshot_size INTEGER NOT NULL,
                      locked INTEGER NOT NULL DEFAULT 0,
                      note TEXT,
                      world_name TEXT,
                      location_x REAL,
                      location_y REAL,
                      location_z REAL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_backups_player_created ON backups(player_uuid, created_at DESC)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS claims(
                      player_uuid TEXT NOT NULL,
                      backup_id TEXT NOT NULL,
                      slot_type TEXT NOT NULL,
                      slot_index INTEGER NOT NULL,
                      actor_uuid TEXT NOT NULL,
                      actor_name TEXT,
                      claimed_at INTEGER NOT NULL,
                      item_blob BLOB NOT NULL,
                      delivered INTEGER NOT NULL DEFAULT 0,
                      delivered_at INTEGER,
                      PRIMARY KEY(backup_id, slot_type, slot_index)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_backup ON claims(backup_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_actor_delivered ON claims(actor_uuid, delivered)");
        }
    }

    private static void migrateSchema(Connection connection) throws SQLException {
        ensureBackupColumn(connection, "locked", "INTEGER NOT NULL DEFAULT 0");
        ensureBackupColumn(connection, "note", "TEXT");
        ensureBackupColumn(connection, "world_name", "TEXT");
        ensureBackupColumn(connection, "location_x", "REAL");
        ensureBackupColumn(connection, "location_y", "REAL");
        ensureBackupColumn(connection, "location_z", "REAL");
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

    private static Double readNullableDouble(ResultSet rs, int columnIndex) throws SQLException {
        double value = rs.getDouble(columnIndex);
        return rs.wasNull() ? null : value;
    }

    private static boolean isConstraintViolation(SQLException e) {
        // SQLite 约束冲突错误码是 19 (SQLITE_CONSTRAINT)
        if (e.getErrorCode() == 19) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("constraint");
    }
}
