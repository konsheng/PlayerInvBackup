package org.playerinvbackup.backup.store.jdbc;

import java.sql.Connection;
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
import org.playerinvbackup.backup.store.SqlTableNames;

/**
 * 共享 JDBC 存储基类
 *
 * <p>收敛各 SQL 后端通用的 CRUD, 映射与迁移流程
 */
public abstract class AbstractJdbcBackupStore implements BackupStore {
    private final Object sharedConnectionLock = new Object();
    private final JdbcDialect dialect;
    private final SqlTableNames tables;
    private Connection sharedConnection;

    protected AbstractJdbcBackupStore(JdbcDialect dialect, SqlTableNames tables) {
        this.dialect = dialect;
        this.tables = tables;
    }

    protected boolean useSharedConnection() {
        return true;
    }

    protected final JdbcDialect dialect() {
        return dialect;
    }

    protected final SqlTableNames tables() {
        return tables;
    }

    protected void beforeConnect() throws Exception {
    }

    protected void closeResources() throws Exception {
    }

    protected abstract Connection openConnection() throws Exception;

    @Override
    public final void init() throws Exception {
        dialect.loadDriver();
        beforeConnect();

        if (useSharedConnection()) {
            synchronized (sharedConnectionLock) {
                sharedConnection = openConnection();
                sharedConnection.setAutoCommit(true);
                dialect.initializeConnection(sharedConnection);
                createSchema(sharedConnection);
                migrateSchema(sharedConnection);
            }
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(true);
            dialect.initializeConnection(connection);
            createSchema(connection);
            migrateSchema(connection);
        }
    }

    @Override
    public final void saveBackup(BackupRecord record) throws Exception {
        withConnection(connection -> {
            String sql = """
                    INSERT INTO %s(backup_id, player_uuid, created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note, world_name, location_x, location_y, location_z)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.formatted(tables.backups());
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
                dialect.bindBoolean(ps, 9, meta.locked());
                ps.setString(10, meta.note());
                ps.setString(11, meta.worldName());
                ps.setObject(12, meta.locationX());
                ps.setObject(13, meta.locationY());
                ps.setObject(14, meta.locationZ());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public final List<BackupMeta> listBackups(UUID playerUuid, BackupQuery query, int offset, int limit) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        return withConnection(connection -> {
            StringBuilder sql = new StringBuilder("""
                    SELECT backup_id, created_at, trigger, schema_version, sha256, snapshot_size, locked, note, world_name, location_x, location_y, location_z
                    FROM %s
                    WHERE player_uuid=?
                    """.formatted(tables.backups()));
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
                        out.add(mapBackupMeta(rs, playerUuid));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public final Optional<BackupRecord> loadBackup(UUID playerUuid, String backupId) throws Exception {
        return withConnection(connection -> {
            String sql = """
                    SELECT created_at, trigger, schema_version, sha256, snapshot_blob, snapshot_size, locked, note, world_name, location_x, location_y, location_z
                    FROM %s
                    WHERE player_uuid=? AND backup_id=?
                    """.formatted(tables.backups());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, backupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapBackupRecord(rs, playerUuid, backupId));
                }
            }
        });
    }

    @Override
    public final boolean setBackupLocked(UUID playerUuid, String backupId, boolean locked) throws Exception {
        return withConnection(connection -> {
            String sql = "UPDATE " + tables.backups() + " SET locked=? WHERE player_uuid=? AND backup_id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                dialect.bindBoolean(ps, 1, locked);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, backupId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public final boolean setBackupNote(UUID playerUuid, String backupId, String note) throws Exception {
        return withConnection(connection -> {
            String sql = "UPDATE " + tables.backups() + " SET note=? WHERE player_uuid=? AND backup_id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, note);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, backupId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public final List<SlotClaim> listClaims(UUID playerUuid, String backupId) throws Exception {
        return withConnection(connection -> {
            String sql = """
                    SELECT slot_type, slot_index, actor_uuid, claimed_at
                    FROM %s
                    WHERE backup_id=?
                    """.formatted(tables.claims());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, backupId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SlotClaim> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapSlotClaim(rs, backupId));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public final boolean tryClaimSlot(
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            UUID actorUuid,
            String actorName,
            long claimedAtMillis,
            byte[] itemBytes
    ) throws Exception {
        return withConnection(connection -> {
            String sql = """
                    INSERT INTO %s(player_uuid, backup_id, slot_type, slot_index, actor_uuid, actor_name, claimed_at, item_blob, delivered, delivered_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, %s, NULL)
                    """.formatted(tables.claims(), dialect.falseLiteral());
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
                if (dialect.isConstraintViolation(e)) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public final List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        return withConnection(connection -> {
            String sql = """
                    SELECT player_uuid, backup_id, slot_type, slot_index, actor_name, claimed_at, item_blob
                    FROM %s
                    WHERE actor_uuid=? AND delivered=%s
                    ORDER BY claimed_at ASC
                    LIMIT ?
                    """.formatted(tables.claims(), dialect.falseLiteral());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, actorUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<UndeliveredClaim> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapPendingClaim(rs, actorUuid));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public final boolean markDelivered(
            UUID actorUuid,
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            long deliveredAtMillis
    ) throws Exception {
        return withConnection(connection -> {
            String sql = """
                    UPDATE %s
                    SET delivered=%s, delivered_at=?
                    WHERE actor_uuid=? AND player_uuid=? AND backup_id=? AND slot_type=? AND slot_index=? AND delivered=%s
                    """.formatted(tables.claims(), dialect.trueLiteral(), dialect.falseLiteral());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, deliveredAtMillis);
                ps.setString(2, actorUuid.toString());
                ps.setString(3, playerUuid.toString());
                ps.setString(4, backupId);
                ps.setString(5, slotType.name());
                ps.setInt(6, slotIndex);
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public final void purgeBackups(UUID playerUuid, int keepPerPlayer, long keepAfterMillis) throws Exception {
        if (keepPerPlayer <= 0 && keepAfterMillis <= 0) {
            return;
        }
        withTransaction(conn -> {
            Set<String> toDelete = new LinkedHashSet<>();
            String selectSql = """
                    SELECT backup_id, created_at
                    FROM %s
                    WHERE player_uuid=? AND locked=%s
                    ORDER BY created_at DESC
                    """.formatted(tables.backups(), dialect.falseLiteral());
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    int idx = 0;
                    while (rs.next()) {
                        idx++;
                        String currentBackupId = rs.getString(1);
                        if (currentBackupId == null || currentBackupId.isBlank()) {
                            continue;
                        }
                        long createdAt = rs.getLong(2);
                        boolean deleteByCount = keepPerPlayer > 0 && idx > keepPerPlayer;
                        boolean deleteByAge = keepAfterMillis > 0 && createdAt < keepAfterMillis;
                        if (deleteByCount || deleteByAge) {
                            toDelete.add(currentBackupId);
                        }
                    }
                }
            }

            try (PreparedStatement deleteClaims = conn.prepareStatement("DELETE FROM " + tables.claims() + " WHERE backup_id=?");
                 PreparedStatement deleteBackup = conn.prepareStatement("DELETE FROM " + tables.backups() + " WHERE backup_id=?")) {
                for (String currentBackupId : toDelete) {
                    if (hasUndeliveredClaims(conn, currentBackupId)) {
                        continue;
                    }
                    deleteClaims.setString(1, currentBackupId);
                    deleteClaims.executeUpdate();
                    deleteBackup.setString(1, currentBackupId);
                    deleteBackup.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public final void close() throws Exception {
        Exception closeError = null;

        if (useSharedConnection()) {
            synchronized (sharedConnectionLock) {
                if (sharedConnection != null) {
                    try {
                        sharedConnection.close();
                    } catch (Exception e) {
                        closeError = e;
                    } finally {
                        sharedConnection = null;
                    }
                }
            }
        }

        try {
            closeResources();
        } catch (Exception e) {
            if (closeError == null) {
                closeError = e;
            } else {
                closeError.addSuppressed(e);
            }
        }

        if (closeError != null) {
            throw closeError;
        }
    }

    private void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS %s(
                      backup_id %s PRIMARY KEY,
                      player_uuid %s NOT NULL,
                      created_at BIGINT NOT NULL,
                      trigger %s NOT NULL,
                      schema_version INT NOT NULL,
                      sha256 %s NOT NULL,
                      snapshot_blob %s NOT NULL,
                      snapshot_size INT NOT NULL,
                      locked %s,
                      note %s,
                      world_name %s,
                      location_x %s,
                      location_y %s,
                      location_z %s
                    )
                    """.formatted(
                    tables.backups(),
                    dialect.backupIdType(),
                    dialect.uuidType(),
                    dialect.triggerType(),
                    dialect.sha256Type(),
                    dialect.blobType(),
                    dialect.booleanType(),
                    dialect.noteType(),
                    dialect.worldNameType(),
                    dialect.doubleType(),
                    dialect.doubleType(),
                    dialect.doubleType()
            ));

            st.execute("""
                    CREATE TABLE IF NOT EXISTS %s(
                      player_uuid %s NOT NULL,
                      backup_id %s NOT NULL,
                      slot_type %s NOT NULL,
                      slot_index INT NOT NULL,
                      actor_uuid %s NOT NULL,
                      actor_name %s,
                      claimed_at BIGINT NOT NULL,
                      item_blob %s NOT NULL,
                      delivered %s,
                      delivered_at BIGINT,
                      PRIMARY KEY(backup_id, slot_type, slot_index)
                    )
                    """.formatted(
                    tables.claims(),
                    dialect.uuidType(),
                    dialect.backupIdType(),
                    dialect.triggerType(),
                    dialect.uuidType(),
                    dialect.actorNameType(),
                    dialect.blobType(),
                    dialect.booleanType()
            ));
        }

        dialect.createIndex(conn, tables.idxBackupsPlayerCreated(), tables.backups(), dialect.playerCreatedIndexColumns());
        dialect.createIndex(conn, tables.idxClaimsBackup(), tables.claims(), "backup_id");
        dialect.createIndex(conn, tables.idxClaimsActorDelivered(), tables.claims(), "actor_uuid, delivered");
    }

    private void migrateSchema(Connection conn) throws SQLException {
        ensureBackupColumn(conn, "locked", dialect.booleanType());
        ensureBackupColumn(conn, "note", dialect.noteType());
        ensureBackupColumn(conn, "world_name", dialect.worldNameType());
        ensureBackupColumn(conn, "location_x", dialect.doubleType());
        ensureBackupColumn(conn, "location_y", dialect.doubleType());
        ensureBackupColumn(conn, "location_z", dialect.doubleType());
    }

    private void ensureBackupColumn(Connection conn, String column, String definition) throws SQLException {
        if (dialect.columnExists(conn, tables.backups(), column)) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + tables.backups() + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasUndeliveredClaims(Connection conn, String backupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + tables.claims() + " WHERE backup_id=? AND delivered=" + dialect.falseLiteral() + " LIMIT 1"
        )) {
            ps.setString(1, backupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private BackupMeta mapBackupMeta(ResultSet rs, UUID playerUuid) throws SQLException {
        return new BackupMeta(
                rs.getString(1),
                playerUuid,
                rs.getLong(2),
                TriggerType.valueOf(rs.getString(3)),
                rs.getInt(4),
                rs.getString(5),
                rs.getInt(6),
                dialect.readBoolean(rs, 7),
                defaultString(rs.getString(8)),
                rs.getString(9),
                readNullableDouble(rs, 10),
                readNullableDouble(rs, 11),
                readNullableDouble(rs, 12)
        );
    }

    private BackupRecord mapBackupRecord(ResultSet rs, UUID playerUuid, String backupId) throws SQLException {
        BackupMeta meta = new BackupMeta(
                backupId,
                playerUuid,
                rs.getLong(1),
                TriggerType.valueOf(rs.getString(2)),
                rs.getInt(3),
                rs.getString(4),
                rs.getInt(6),
                dialect.readBoolean(rs, 7),
                defaultString(rs.getString(8)),
                rs.getString(9),
                readNullableDouble(rs, 10),
                readNullableDouble(rs, 11),
                readNullableDouble(rs, 12)
        );
        return new BackupRecord(meta, rs.getBytes(5));
    }

    private SlotClaim mapSlotClaim(ResultSet rs, String backupId) throws SQLException {
        return new SlotClaim(
                backupId,
                SlotType.valueOf(rs.getString(1)),
                rs.getInt(2),
                UUID.fromString(rs.getString(3)),
                rs.getLong(4)
        );
    }

    private UndeliveredClaim mapPendingClaim(ResultSet rs, UUID actorUuid) throws SQLException {
        return new UndeliveredClaim(
                UUID.fromString(rs.getString(1)),
                rs.getString(2),
                SlotType.valueOf(rs.getString(3)),
                rs.getInt(4),
                actorUuid,
                rs.getString(5),
                rs.getLong(6),
                rs.getBytes(7)
        );
    }

    protected final <T> T withConnection(SqlWork<T> work) throws Exception {
        if (useSharedConnection()) {
            synchronized (sharedConnectionLock) {
                return work.run(requireSharedConnection());
            }
        }
        try (Connection connection = openConnection()) {
            return work.run(connection);
        }
    }

    protected final <T> T withTransaction(SqlWork<T> work) throws Exception {
        if (useSharedConnection()) {
            synchronized (sharedConnectionLock) {
                return inTransaction(requireSharedConnection(), work);
            }
        }
        try (Connection connection = openConnection()) {
            return inTransaction(connection, work);
        }
    }

    private Connection requireSharedConnection() {
        if (sharedConnection == null) {
            throw new IllegalStateException("shared JDBC connection is not initialized");
        }
        return sharedConnection;
    }

    private <T> T inTransaction(Connection connection, SqlWork<T> work) throws Exception {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static Double readNullableDouble(ResultSet rs, int columnIndex) throws SQLException {
        double value = rs.getDouble(columnIndex);
        return rs.wasNull() ? null : value;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }
}
