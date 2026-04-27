package org.playerinvbackup.backup.store.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.domain.UndeliveredClaim;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.util.AtomicFiles;

/**
 * 本地文件存储实现
 */
public final class LocalBackupStore implements BackupStore {
    private static final Comparator<BackupMeta> BACKUP_ORDER =
            Comparator.comparing(BackupMeta::locked).reversed()
                    .thenComparing(Comparator.comparingLong(BackupMeta::createdAtMillis).reversed());

    private static final Comparator<CachedClaim> CLAIM_ORDER =
            Comparator.comparingLong(CachedClaim::claimedAtMillis);

    private final Path baseDir;
    private final Path backupsDir;
    private final Path claimsDir;
    private final ConcurrentHashMap<UUID, PlayerBackupIndex> playerIndexes = new ConcurrentHashMap<>();
    private final ClaimsIndex claimsIndex = new ClaimsIndex();

    public LocalBackupStore(Path baseDir) {
        this.baseDir = baseDir;
        this.backupsDir = baseDir.resolve("backups");
        this.claimsDir = baseDir.resolve("claims");
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(baseDir);
        Files.createDirectories(backupsDir);
        Files.createDirectories(claimsDir);
    }

    @Override
    public void saveBackup(BackupRecord record) throws IOException {
        BackupMeta meta = record.meta();
        Path playerDir = playerDir(meta.playerUuid());
        Files.createDirectories(playerDir);

        AtomicFiles.writeBytesAtomic(snapshotPath(meta.playerUuid(), meta.backupId()), record.snapshotBytes());
        AtomicFiles.writeStringAtomic(metaPath(meta.playerUuid(), meta.backupId()), serializeMeta(meta), StandardCharsets.UTF_8, true);

        playerIndex(meta.playerUuid()).upsertIfLoaded(meta);
    }

    @Override
    public List<BackupMeta> listBackups(UUID playerUuid, BackupQuery query, int offset, int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }

        List<BackupMeta> filtered = filteredBackups(playerUuid, query);
        int from = Math.min(Math.max(0, offset), filtered.size());
        int to = Math.min(from + limit, filtered.size());
        return List.copyOf(filtered.subList(from, to));
    }

    @Override
    public int countBackups(UUID playerUuid, BackupQuery query) throws IOException {
        return filteredBackups(playerUuid, query).size();
    }

    private List<BackupMeta> filteredBackups(UUID playerUuid, BackupQuery query) throws IOException {
        TriggerType triggerFilter = query == null ? null : query.trigger();
        long createdAfterMillis = query == null ? 0L : query.createdAfterMillis();

        List<BackupMeta> filtered = new ArrayList<>();
        for (BackupMeta meta : playerIndex(playerUuid).snapshot()) {
            if (triggerFilter != null && meta.trigger() != triggerFilter) {
                continue;
            }
            if (createdAfterMillis > 0 && meta.createdAtMillis() < createdAfterMillis) {
                continue;
            }
            filtered.add(meta);
        }
        return filtered;
    }

    @Override
    public Optional<BackupRecord> loadBackup(UUID playerUuid, String backupId) throws IOException {
        Optional<BackupMeta> meta = playerIndex(playerUuid).find(backupId);
        if (meta.isEmpty()) {
            return Optional.empty();
        }

        Path snapshotPath = snapshotPath(playerUuid, backupId);
        if (!Files.isRegularFile(snapshotPath)) {
            invalidatePlayerIndex(playerUuid);
            return Optional.empty();
        }
        return Optional.of(new BackupRecord(meta.get(), Files.readAllBytes(snapshotPath)));
    }

    @Override
    public List<SlotClaim> listClaims(UUID playerUuid, String backupId) throws IOException {
        return claimsIndex().listClaims(backupId);
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
    ) throws IOException {
        Path dir = claimDir(backupId);
        Files.createDirectories(dir);

        Path claimPath = claimPath(backupId, slotType, slotIndex);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player-uuid", playerUuid.toString());
        yaml.set("backup-id", backupId);
        yaml.set("slot-type", slotType.name());
        yaml.set("slot-index", slotIndex);
        yaml.set("actor-uuid", actorUuid.toString());
        yaml.set("actor-name", actorName);
        yaml.set("claimed-at-millis", claimedAtMillis);
        yaml.set("item-bytes-base64", Base64.getEncoder().encodeToString(itemBytes));
        yaml.set("delivered", false);
        yaml.set("delivered-at-millis", 0L);

        try {
            AtomicFiles.writeStringAtomic(claimPath, yaml.saveToString(), StandardCharsets.UTF_8, false);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;
        }

        claimsIndex.upsertIfLoaded(new CachedClaim(
                claimPath,
                playerUuid,
                backupId,
                slotType,
                slotIndex,
                actorUuid,
                actorName,
                claimedAtMillis,
                itemBytes,
                false,
                0L
        ));
        return true;
    }

    @Override
    public List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        return claimsIndex().listUndelivered(actorUuid, limit);
    }

    @Override
    public boolean markDelivered(
            UUID actorUuid,
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            long deliveredAtMillis
    ) throws IOException {
        Path claimPath = claimPath(backupId, slotType, slotIndex);
        if (!Files.isRegularFile(claimPath)) {
            return false;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
        if (yaml.getBoolean("delivered", false)) {
            return false;
        }
        if (!actorUuid.toString().equalsIgnoreCase(yaml.getString("actor-uuid", ""))) {
            return false;
        }
        if (!playerUuid.toString().equalsIgnoreCase(yaml.getString("player-uuid", ""))) {
            return false;
        }

        yaml.set("delivered", true);
        yaml.set("delivered-at-millis", deliveredAtMillis);
        AtomicFiles.writeStringAtomic(claimPath, yaml.saveToString(), StandardCharsets.UTF_8, true);

        claimsIndex.markDeliveredIfLoaded(backupId, slotType, slotIndex, deliveredAtMillis);
        return true;
    }

    @Override
    public boolean setBackupLocked(UUID playerUuid, String backupId, boolean locked) throws IOException {
        if (playerUuid == null || backupId == null || backupId.isBlank()) {
            return false;
        }

        Path metaPath = metaPath(playerUuid, backupId);
        Path snapshotPath = snapshotPath(playerUuid, backupId);
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(snapshotPath)) {
            return false;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
        yaml.set("locked", locked);
        AtomicFiles.writeStringAtomic(metaPath, yaml.saveToString(), StandardCharsets.UTF_8, true);

        playerIndex(playerUuid).updateLockedIfLoaded(backupId, locked);
        return true;
    }

    @Override
    public boolean setBackupNote(UUID playerUuid, String backupId, String note) throws IOException {
        if (playerUuid == null || backupId == null || backupId.isBlank()) {
            return false;
        }

        Path metaPath = metaPath(playerUuid, backupId);
        Path snapshotPath = snapshotPath(playerUuid, backupId);
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(snapshotPath)) {
            return false;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
        yaml.set("note", note == null ? "" : note);
        AtomicFiles.writeStringAtomic(metaPath, yaml.saveToString(), StandardCharsets.UTF_8, true);

        playerIndex(playerUuid).updateNoteIfLoaded(backupId, note == null ? "" : note);
        return true;
    }

    @Override
    public void purgeBackups(UUID playerUuid, int keepPerPlayer, long keepAfterMillis) throws IOException {
        if (keepPerPlayer <= 0 && keepAfterMillis <= 0) {
            return;
        }

        PlayerBackupIndex index = playerIndex(playerUuid);
        List<BackupMeta> unlocked = index.snapshot().stream()
                .filter(meta -> !meta.locked())
                .sorted(Comparator.comparingLong(BackupMeta::createdAtMillis).reversed())
                .toList();

        if (keepAfterMillis <= 0 && keepPerPlayer > 0 && unlocked.size() <= keepPerPlayer) {
            return;
        }

        for (int i = 0; i < unlocked.size(); i++) {
            BackupMeta meta = unlocked.get(i);
            boolean deleteByCount = keepPerPlayer > 0 && i >= keepPerPlayer;
            boolean deleteByAge = keepAfterMillis > 0 && meta.createdAtMillis() < keepAfterMillis;
            if (!deleteByCount && !deleteByAge) {
                continue;
            }
            if (hasUndeliveredClaims(meta.backupId())) {
                continue;
            }

            Files.deleteIfExists(metaPath(playerUuid, meta.backupId()));
            Files.deleteIfExists(snapshotPath(playerUuid, meta.backupId()));
            deleteDirectoryIfExists(claimDir(meta.backupId()));

            index.removeIfLoaded(meta.backupId());
            claimsIndex.removeBackupIfLoaded(meta.backupId());
        }
    }

    @Override
    public void close() {
        clearCaches();
    }

    void invalidatePlayerIndex(UUID playerUuid) {
        PlayerBackupIndex removed = playerIndexes.remove(playerUuid);
        if (removed != null) {
            removed.invalidate();
        }
    }

    void invalidateClaimsIndex() {
        claimsIndex.invalidate();
    }

    void clearCaches() {
        for (PlayerBackupIndex index : playerIndexes.values()) {
            index.invalidate();
        }
        playerIndexes.clear();
        claimsIndex.invalidate();
    }

    private PlayerBackupIndex playerIndex(UUID playerUuid) throws IOException {
        PlayerBackupIndex index = playerIndexes.computeIfAbsent(playerUuid, ignored -> new PlayerBackupIndex());
        index.ensureLoaded(() -> loadPlayerBackupsFromDisk(playerUuid));
        return index;
    }

    private ClaimsIndex claimsIndex() throws IOException {
        claimsIndex.ensureLoaded(this::loadAllClaimsFromDisk);
        return claimsIndex;
    }

    private List<BackupMeta> loadPlayerBackupsFromDisk(UUID playerUuid) throws IOException {
        Path playerDir = playerDir(playerUuid);
        if (!Files.isDirectory(playerDir)) {
            return List.of();
        }

        List<BackupMeta> metas = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir, "*.yml")) {
            for (Path metaPath : stream) {
                BackupMeta meta = readMeta(metaPath);
                if (meta == null || !playerUuid.equals(meta.playerUuid())) {
                    continue;
                }
                if (!Files.isRegularFile(snapshotPath(playerUuid, meta.backupId()))) {
                    continue;
                }
                metas.add(meta);
            }
        }
        metas.sort(BACKUP_ORDER);
        return metas;
    }

    private List<CachedClaim> loadAllClaimsFromDisk() throws IOException {
        if (!Files.isDirectory(claimsDir)) {
            return List.of();
        }

        List<CachedClaim> claims = new ArrayList<>();
        try (DirectoryStream<Path> backupDirs = Files.newDirectoryStream(claimsDir)) {
            for (Path backupDir : backupDirs) {
                if (!Files.isDirectory(backupDir)) {
                    continue;
                }
                try (DirectoryStream<Path> claimFiles = Files.newDirectoryStream(backupDir, "*.yml")) {
                    for (Path claimPath : claimFiles) {
                        CachedClaim claim = readCachedClaim(claimPath);
                        if (claim != null) {
                            claims.add(claim);
                        }
                    }
                }
            }
        }
        return claims;
    }

    private boolean hasUndeliveredClaims(String backupId) {
        if (claimsIndex.isLoaded()) {
            return claimsIndex.hasUndeliveredClaims(backupId);
        }
        return hasUndeliveredClaimsOnDisk(claimDir(backupId));
    }

    private String serializeMeta(BackupMeta meta) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("backup-id", meta.backupId());
        yaml.set("player-uuid", meta.playerUuid().toString());
        yaml.set("server-id", meta.serverId());
        yaml.set("created-at-millis", meta.createdAtMillis());
        yaml.set("trigger", meta.trigger().name());
        yaml.set("sha256", meta.sha256Hex());
        yaml.set("snapshot-size-bytes", meta.snapshotSizeBytes());
        yaml.set("locked", meta.locked());
        yaml.set("note", meta.note());
        yaml.set("world-name", meta.worldName());
        yaml.set("location.x", meta.locationX());
        yaml.set("location.y", meta.locationY());
        yaml.set("location.z", meta.locationZ());
        yaml.set("target-world-name", meta.targetWorldName());
        yaml.set("target-location.x", meta.targetLocationX());
        yaml.set("target-location.y", meta.targetLocationY());
        yaml.set("target-location.z", meta.targetLocationZ());
        yaml.set("killer-player-uuid", meta.killerPlayerUuid() == null ? null : meta.killerPlayerUuid().toString());
        yaml.set("killer-player-name", meta.killerPlayerName());
        return yaml.saveToString();
    }

    private BackupMeta readMeta(Path metaPath) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
            String backupId = yaml.getString("backup-id", null);
            String player = yaml.getString("player-uuid", null);
            String serverId = yaml.getString("server-id", null);
            if (backupId == null || player == null || serverId == null || serverId.isBlank()) {
                return null;
            }

            String note = yaml.getString("note", "");
            return new BackupMeta(
                    backupId,
                    UUID.fromString(player),
                    serverId,
                    yaml.getLong("created-at-millis", 0L),
                    TriggerType.valueOf(yaml.getString("trigger", TriggerType.TIMER.name())),
                    yaml.getString("sha256", ""),
                    yaml.getInt("snapshot-size-bytes", 0),
                    yaml.getBoolean("locked", false),
                    note == null ? "" : note,
                    yaml.getString("world-name", null),
                    yaml.contains("location.x") ? yaml.getDouble("location.x") : null,
                    yaml.contains("location.y") ? yaml.getDouble("location.y") : null,
                    yaml.contains("location.z") ? yaml.getDouble("location.z") : null,
                    yaml.getString("target-world-name", null),
                    yaml.contains("target-location.x") ? yaml.getDouble("target-location.x") : null,
                    yaml.contains("target-location.y") ? yaml.getDouble("target-location.y") : null,
                    yaml.contains("target-location.z") ? yaml.getDouble("target-location.z") : null,
                    readNullableUuid(yaml, "killer-player-uuid"),
                    yaml.getString("killer-player-name", null)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private CachedClaim readCachedClaim(Path claimPath) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
            String player = yaml.getString("player-uuid", null);
            String backupId = yaml.getString("backup-id", null);
            String slotType = yaml.getString("slot-type", null);
            String actor = yaml.getString("actor-uuid", null);
            if (player == null || backupId == null || slotType == null || actor == null) {
                return null;
            }
            String actorName = yaml.getString("actor-name", "");
            String encoded = yaml.getString("item-bytes-base64", "");
            return new CachedClaim(
                    claimPath,
                    UUID.fromString(player),
                    backupId,
                    SlotType.valueOf(slotType),
                    yaml.getInt("slot-index"),
                    UUID.fromString(actor),
                    actorName == null ? "" : actorName,
                    yaml.getLong("claimed-at-millis"),
                    Base64.getDecoder().decode(encoded),
                    yaml.getBoolean("delivered", false),
                    yaml.getLong("delivered-at-millis", 0L)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private Path playerDir(UUID playerUuid) {
        return backupsDir.resolve(playerUuid.toString());
    }

    private Path metaPath(UUID playerUuid, String backupId) {
        return playerDir(playerUuid).resolve(backupId + ".yml");
    }

    private Path snapshotPath(UUID playerUuid, String backupId) {
        return playerDir(playerUuid).resolve(backupId + ".bkp");
    }

    private Path claimDir(String backupId) {
        return claimsDir.resolve(backupId);
    }

    private Path claimPath(String backupId, SlotType slotType, int slotIndex) {
        return claimDir(backupId).resolve(slotType.name() + "_" + slotIndex + ".yml");
    }

    private static void deleteDirectoryIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean hasUndeliveredClaimsOnDisk(Path claimDir) {
        if (!Files.isDirectory(claimDir)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(claimDir, "*.yml")) {
            for (Path claimPath : stream) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
                if (!yaml.getBoolean("delivered", false)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    private static UUID readNullableUuid(YamlConfiguration yaml, String path) {
        String raw = yaml.getString(path, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw);
    }

    @FunctionalInterface
    private interface Loader<T> {
        T load() throws IOException;
    }

    private static final class PlayerBackupIndex {
        private final Object lock = new Object();
        private boolean loaded;
        private final List<BackupMeta> sortedBackups = new ArrayList<>();
        private final Map<String, BackupMeta> byBackupId = new HashMap<>();

        void ensureLoaded(Loader<List<BackupMeta>> loader) throws IOException {
            if (loaded) {
                return;
            }
            synchronized (lock) {
                if (loaded) {
                    return;
                }
                replaceAll(loader.load());
                loaded = true;
            }
        }

        List<BackupMeta> snapshot() {
            synchronized (lock) {
                return new ArrayList<>(sortedBackups);
            }
        }

        Optional<BackupMeta> find(String backupId) {
            synchronized (lock) {
                return Optional.ofNullable(byBackupId.get(backupId));
            }
        }

        void upsertIfLoaded(BackupMeta meta) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                removeInternal(meta.backupId());
                insertInternal(meta);
            }
        }

        void updateLockedIfLoaded(String backupId, boolean locked) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                BackupMeta existing = byBackupId.get(backupId);
                if (existing == null) {
                    return;
                }
                upsertUnlocked(new BackupMeta(
                        existing.backupId(),
                        existing.playerUuid(),
                        existing.serverId(),
                        existing.createdAtMillis(),
                        existing.trigger(),
                        existing.sha256Hex(),
                        existing.snapshotSizeBytes(),
                        locked,
                        existing.note(),
                        existing.worldName(),
                        existing.locationX(),
                        existing.locationY(),
                        existing.locationZ(),
                        existing.targetWorldName(),
                        existing.targetLocationX(),
                        existing.targetLocationY(),
                        existing.targetLocationZ(),
                        existing.killerPlayerUuid(),
                        existing.killerPlayerName()
                ));
            }
        }

        void updateNoteIfLoaded(String backupId, String note) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                BackupMeta existing = byBackupId.get(backupId);
                if (existing == null) {
                    return;
                }
                upsertUnlocked(new BackupMeta(
                        existing.backupId(),
                        existing.playerUuid(),
                        existing.serverId(),
                        existing.createdAtMillis(),
                        existing.trigger(),
                        existing.sha256Hex(),
                        existing.snapshotSizeBytes(),
                        existing.locked(),
                        note,
                        existing.worldName(),
                        existing.locationX(),
                        existing.locationY(),
                        existing.locationZ(),
                        existing.targetWorldName(),
                        existing.targetLocationX(),
                        existing.targetLocationY(),
                        existing.targetLocationZ(),
                        existing.killerPlayerUuid(),
                        existing.killerPlayerName()
                ));
            }
        }

        void removeIfLoaded(String backupId) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                removeInternal(backupId);
            }
        }

        void invalidate() {
            synchronized (lock) {
                loaded = false;
                sortedBackups.clear();
                byBackupId.clear();
            }
        }

        private void replaceAll(List<BackupMeta> metas) {
            sortedBackups.clear();
            byBackupId.clear();
            for (BackupMeta meta : metas) {
                insertInternal(meta);
            }
        }

        private void upsertUnlocked(BackupMeta meta) {
            removeInternal(meta.backupId());
            insertInternal(meta);
        }

        private void insertInternal(BackupMeta meta) {
            byBackupId.put(meta.backupId(), meta);
            sortedBackups.add(meta);
            sortedBackups.sort(BACKUP_ORDER);
        }

        private void removeInternal(String backupId) {
            BackupMeta removed = byBackupId.remove(backupId);
            if (removed != null) {
                sortedBackups.removeIf(meta -> meta.backupId().equals(backupId));
            }
        }
    }

    private static final class ClaimsIndex {
        private final Object lock = new Object();
        private boolean loaded;
        private final Map<String, CachedClaim> byClaimKey = new HashMap<>();
        private final Map<String, List<CachedClaim>> claimsByBackupId = new HashMap<>();
        private final Map<UUID, List<CachedClaim>> undeliveredByActor = new HashMap<>();

        boolean isLoaded() {
            synchronized (lock) {
                return loaded;
            }
        }

        void ensureLoaded(Loader<List<CachedClaim>> loader) throws IOException {
            if (loaded) {
                return;
            }
            synchronized (lock) {
                if (loaded) {
                    return;
                }
                replaceAll(loader.load());
                loaded = true;
            }
        }

        List<SlotClaim> listClaims(String backupId) {
            synchronized (lock) {
                List<CachedClaim> claims = claimsByBackupId.get(backupId);
                if (claims == null || claims.isEmpty()) {
                    return List.of();
                }
                List<SlotClaim> result = new ArrayList<>(claims.size());
                for (CachedClaim claim : claims) {
                    result.add(claim.toSlotClaim());
                }
                return result;
            }
        }

        List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) {
            synchronized (lock) {
                List<CachedClaim> claims = undeliveredByActor.get(actorUuid);
                if (claims == null || claims.isEmpty()) {
                    return List.of();
                }
                int end = Math.min(limit, claims.size());
                List<UndeliveredClaim> result = new ArrayList<>(end);
                for (int i = 0; i < end; i++) {
                    result.add(claims.get(i).toUndeliveredClaim());
                }
                return result;
            }
        }

        boolean hasUndeliveredClaims(String backupId) {
            synchronized (lock) {
                List<CachedClaim> claims = claimsByBackupId.get(backupId);
                if (claims == null || claims.isEmpty()) {
                    return false;
                }
                for (CachedClaim claim : claims) {
                    if (!claim.delivered()) {
                        return true;
                    }
                }
                return false;
            }
        }

        void upsertIfLoaded(CachedClaim claim) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                removeInternal(claim.claimKey());
                insertInternal(claim);
            }
        }

        void markDeliveredIfLoaded(String backupId, SlotType slotType, int slotIndex, long deliveredAtMillis) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                String claimKey = CachedClaim.claimKey(backupId, slotType, slotIndex);
                CachedClaim existing = byClaimKey.get(claimKey);
                if (existing == null || existing.delivered()) {
                    return;
                }
                removeInternal(claimKey);
                insertInternal(existing.withDelivered(deliveredAtMillis));
            }
        }

        void removeBackupIfLoaded(String backupId) {
            synchronized (lock) {
                if (!loaded) {
                    return;
                }
                List<CachedClaim> claims = claimsByBackupId.remove(backupId);
                if (claims == null) {
                    return;
                }
                for (CachedClaim claim : new ArrayList<>(claims)) {
                    removeInternal(claim.claimKey());
                }
            }
        }

        void invalidate() {
            synchronized (lock) {
                loaded = false;
                byClaimKey.clear();
                claimsByBackupId.clear();
                undeliveredByActor.clear();
            }
        }

        private void replaceAll(List<CachedClaim> claims) {
            byClaimKey.clear();
            claimsByBackupId.clear();
            undeliveredByActor.clear();
            for (CachedClaim claim : claims) {
                insertInternal(claim);
            }
        }

        private void insertInternal(CachedClaim claim) {
            byClaimKey.put(claim.claimKey(), claim);
            claimsByBackupId.computeIfAbsent(claim.backupId(), ignored -> new ArrayList<>()).add(claim);
            if (!claim.delivered()) {
                List<CachedClaim> undelivered = undeliveredByActor.computeIfAbsent(claim.actorUuid(), ignored -> new ArrayList<>());
                undelivered.add(claim);
                undelivered.sort(CLAIM_ORDER);
            }
        }

        private void removeInternal(String claimKey) {
            CachedClaim existing = byClaimKey.remove(claimKey);
            if (existing == null) {
                return;
            }
            List<CachedClaim> byBackup = claimsByBackupId.get(existing.backupId());
            if (byBackup != null) {
                byBackup.removeIf(claim -> claim.claimKey().equals(claimKey));
                if (byBackup.isEmpty()) {
                    claimsByBackupId.remove(existing.backupId());
                }
            }
            if (!existing.delivered()) {
                List<CachedClaim> undelivered = undeliveredByActor.get(existing.actorUuid());
                if (undelivered != null) {
                    undelivered.removeIf(claim -> claim.claimKey().equals(claimKey));
                    if (undelivered.isEmpty()) {
                        undeliveredByActor.remove(existing.actorUuid());
                    }
                }
            }
        }
    }

    private record CachedClaim(
            Path path,
            UUID playerUuid,
            String backupId,
            SlotType slotType,
            int slotIndex,
            UUID actorUuid,
            String actorName,
            long claimedAtMillis,
            byte[] itemBytes,
            boolean delivered,
            long deliveredAtMillis
    ) {
        SlotClaim toSlotClaim() {
            return new SlotClaim(backupId, slotType, slotIndex, actorUuid, claimedAtMillis);
        }

        UndeliveredClaim toUndeliveredClaim() {
            return new UndeliveredClaim(
                    playerUuid,
                    backupId,
                    slotType,
                    slotIndex,
                    actorUuid,
                    actorName,
                    claimedAtMillis,
                    itemBytes
            );
        }

        CachedClaim withDelivered(long deliveredAtMillis) {
            return new CachedClaim(
                    path,
                    playerUuid,
                    backupId,
                    slotType,
                    slotIndex,
                    actorUuid,
                    actorName,
                    claimedAtMillis,
                    itemBytes,
                    true,
                    deliveredAtMillis
            );
        }

        String claimKey() {
            return claimKey(backupId, slotType, slotIndex);
        }

        static String claimKey(String backupId, SlotType slotType, int slotIndex) {
            return backupId + "#" + slotType.name() + "#" + slotIndex;
        }
    }
}
