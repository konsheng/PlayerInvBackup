package org.baymc.backup.store.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SlotClaim;
import org.baymc.backup.domain.SlotType;
import org.baymc.backup.domain.TriggerType;
import org.baymc.backup.domain.UndeliveredClaim;
import org.baymc.backup.store.BackupQuery;
import org.baymc.backup.store.BackupStore;
import org.baymc.backup.util.AtomicFiles;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 文件存储实现
 *
 * <p>目录结构:
 * - backups/<playerUuid>/<backupId>.bkp: 二进制快照
 * - backups/<playerUuid>/<backupId>.yml: 元数据
 * - claims/<backupId>/*.yml: 领取与投递记录
 */
public final class LocalBackupStore implements BackupStore {
    private final Path baseDir;
    private final Path backupsDir;
    private final Path claimsDir;

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
        Path playerDir = backupsDir.resolve(meta.playerUuid().toString());
        Files.createDirectories(playerDir);

        Path snapshotPath = playerDir.resolve(meta.backupId() + ".bkp");
        AtomicFiles.writeBytesAtomic(snapshotPath, record.snapshotBytes());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("backup-id", meta.backupId());
        yaml.set("player-uuid", meta.playerUuid().toString());
        yaml.set("created-at-millis", meta.createdAtMillis());
        yaml.set("trigger", meta.trigger().name());
        yaml.set("schema-version", meta.schemaVersion());
        yaml.set("sha256", meta.sha256Hex());
        yaml.set("snapshot-size-bytes", meta.snapshotSizeBytes());
        yaml.set("locked", meta.locked());
        yaml.set("note", meta.note());

        Path metaPath = playerDir.resolve(meta.backupId() + ".yml");
        AtomicFiles.writeStringAtomic(metaPath, yaml.saveToString(), StandardCharsets.UTF_8, true);
    }

    @Override
    public List<BackupMeta> listBackups(UUID playerUuid, BackupQuery query, int offset, int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }

        Path playerDir = backupsDir.resolve(playerUuid.toString());
        if (!Files.isDirectory(playerDir)) {
            return List.of();
        }

        TriggerType triggerFilter = query == null ? null : query.trigger();
        long createdAfterMillis = query == null ? 0L : query.createdAfterMillis();

        List<BackupMeta> all = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir, "*.yml")) {
            for (Path metaPath : stream) {
                BackupMeta meta = readMeta(metaPath);
                if (meta == null) {
                    continue;
                }
                if (triggerFilter != null && meta.trigger() != triggerFilter) {
                    continue;
                }
                if (createdAfterMillis > 0 && meta.createdAtMillis() < createdAfterMillis) {
                    continue;
                }
                Path snapshotPath = playerDir.resolve(meta.backupId() + ".bkp");
                if (!Files.isRegularFile(snapshotPath)) {
                    continue;
                }
                all.add(meta);
            }
        }

        all.sort(
                Comparator.comparing(BackupMeta::locked).reversed()
                        .thenComparing(Comparator.comparingLong(BackupMeta::createdAtMillis).reversed())
        );
        int from = Math.min(Math.max(0, offset), all.size());
        int to = Math.min(from + limit, all.size());
        return all.subList(from, to);
    }

    @Override
    public Optional<BackupRecord> loadBackup(UUID playerUuid, String backupId) throws IOException {
        Path playerDir = backupsDir.resolve(playerUuid.toString());
        Path metaPath = playerDir.resolve(backupId + ".yml");
        Path snapshotPath = playerDir.resolve(backupId + ".bkp");
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(snapshotPath)) {
            return Optional.empty();
        }
        BackupMeta meta = readMeta(metaPath);
        if (meta == null) {
            return Optional.empty();
        }
        byte[] snapshotBytes = Files.readAllBytes(snapshotPath);
        return Optional.of(new BackupRecord(meta, snapshotBytes));
    }

    @Override
    public List<SlotClaim> listClaims(UUID playerUuid, String backupId) throws IOException {
        Path dir = claimsDir.resolve(backupId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .map(this::readClaim)
                    .filter(c -> c != null)
                    .collect(Collectors.toList());
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
    ) throws IOException {
        Path dir = claimsDir.resolve(backupId);
        Files.createDirectories(dir);
        Path claimPath = dir.resolve(slotType.name() + "_" + slotIndex + ".yml");

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
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;
        }
    }

    @Override
    public List<UndeliveredClaim> listUndelivered(UUID actorUuid, int limit) throws IOException {
        if (!Files.isDirectory(claimsDir) || limit <= 0) {
            return List.of();
        }

        List<UndeliveredClaim> out = new ArrayList<>();
        try (var backupDirs = Files.list(claimsDir)) {
            for (Path backupDir : backupDirs.toList()) {
                if (!Files.isDirectory(backupDir)) {
                    continue;
                }
                try (var claimFiles = Files.list(backupDir)) {
                    for (Path claimPath : claimFiles.toList()) {
                        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
                        if (yaml.getBoolean("delivered", false)) {
                            continue;
                        }
                        String actor = yaml.getString("actor-uuid", "");
                        if (!actorUuid.toString().equalsIgnoreCase(actor)) {
                            continue;
                        }
                        UUID playerUuid = UUID.fromString(yaml.getString("player-uuid"));
                        String backupId = yaml.getString("backup-id");
                        SlotType slotType = SlotType.valueOf(yaml.getString("slot-type"));
                        int slotIndex = yaml.getInt("slot-index");
                        String actorName = yaml.getString("actor-name", "");
                        long claimedAt = yaml.getLong("claimed-at-millis");
                        byte[] itemBytes = Base64.getDecoder().decode(yaml.getString("item-bytes-base64", ""));
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
                }
            }
        }

        out.sort(Comparator.comparingLong(UndeliveredClaim::claimedAtMillis));
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
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
        Path claimPath = claimsDir.resolve(backupId).resolve(slotType.name() + "_" + slotIndex + ".yml");
        if (!Files.isRegularFile(claimPath)) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
        if (yaml.getBoolean("delivered", false)) {
            return false;
        }
        String actor = yaml.getString("actor-uuid", "");
        if (!actorUuid.toString().equalsIgnoreCase(actor)) {
            return false;
        }
        String player = yaml.getString("player-uuid", "");
        if (!playerUuid.toString().equalsIgnoreCase(player)) {
            return false;
        }
        yaml.set("delivered", true);
        yaml.set("delivered-at-millis", deliveredAtMillis);
        AtomicFiles.writeStringAtomic(claimPath, yaml.saveToString(), StandardCharsets.UTF_8, true);
        return true;
    }

    @Override
    public boolean setBackupLocked(UUID playerUuid, String backupId, boolean locked) throws IOException {
        if (playerUuid == null || backupId == null || backupId.isBlank()) {
            return false;
        }
        Path playerDir = backupsDir.resolve(playerUuid.toString());
        Path metaPath = playerDir.resolve(backupId + ".yml");
        Path snapshotPath = playerDir.resolve(backupId + ".bkp");
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(snapshotPath)) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
        yaml.set("locked", locked);
        AtomicFiles.writeStringAtomic(metaPath, yaml.saveToString(), StandardCharsets.UTF_8, true);
        return true;
    }

    @Override
    public boolean setBackupNote(UUID playerUuid, String backupId, String note) throws IOException {
        if (playerUuid == null || backupId == null || backupId.isBlank()) {
            return false;
        }
        Path playerDir = backupsDir.resolve(playerUuid.toString());
        Path metaPath = playerDir.resolve(backupId + ".yml");
        Path snapshotPath = playerDir.resolve(backupId + ".bkp");
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(snapshotPath)) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
        yaml.set("note", note == null ? "" : note);
        AtomicFiles.writeStringAtomic(metaPath, yaml.saveToString(), StandardCharsets.UTF_8, true);
        return true;
    }

    @Override
    public void purgeBackups(UUID playerUuid, int keepPerPlayer) throws IOException {
        if (keepPerPlayer <= 0) {
            return;
        }
        Path playerDir = backupsDir.resolve(playerUuid.toString());
        if (!Files.isDirectory(playerDir)) {
            return;
        }

        List<BackupMeta> metas = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir, "*.yml")) {
            for (Path metaPath : stream) {
                BackupMeta meta = readMeta(metaPath);
                if (meta != null) {
                    metas.add(meta);
                }
            }
        }
        List<BackupMeta> unlocked = metas.stream()
                .filter(meta -> meta != null && !meta.locked())
                .sorted(Comparator.comparingLong(BackupMeta::createdAtMillis).reversed())
                .toList();
        if (unlocked.size() <= keepPerPlayer) {
            return;
        }

        for (int i = keepPerPlayer; i < unlocked.size(); i++) {
            BackupMeta meta = unlocked.get(i);
            Path claimDir = claimsDir.resolve(meta.backupId());
            if (hasUndeliveredClaims(claimDir)) {
                continue;
            }
            Files.deleteIfExists(playerDir.resolve(meta.backupId() + ".yml"));
            Files.deleteIfExists(playerDir.resolve(meta.backupId() + ".bkp"));
            deleteDirectoryIfExists(claimDir);
        }
    }

    @Override
    public void close() {
        // 无操作
    }

    private BackupMeta readMeta(Path metaPath) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaPath.toFile());
            String backupId = yaml.getString("backup-id", null);
            String player = yaml.getString("player-uuid", null);
            if (backupId == null || player == null) {
                return null;
            }
            UUID playerUuid = UUID.fromString(player);
            long createdAt = yaml.getLong("created-at-millis", 0L);
            TriggerType trigger = TriggerType.valueOf(yaml.getString("trigger", TriggerType.TIMER.name()));
            int schemaVersion = yaml.getInt("schema-version", 0);
            String sha256 = yaml.getString("sha256", "");
            int size = yaml.getInt("snapshot-size-bytes", 0);
            boolean locked = yaml.getBoolean("locked", false);
            String note = yaml.getString("note", "");
            if (note == null) {
                note = "";
            }
            return new BackupMeta(backupId, playerUuid, createdAt, trigger, schemaVersion, sha256, size, locked, note);
        } catch (Exception e) {
            return null;
        }
    }

    private SlotClaim readClaim(Path claimPath) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimPath.toFile());
            String backupId = yaml.getString("backup-id");
            SlotType slotType = SlotType.valueOf(yaml.getString("slot-type"));
            int slotIndex = yaml.getInt("slot-index");
            UUID actorUuid = UUID.fromString(yaml.getString("actor-uuid"));
            long claimedAt = yaml.getLong("claimed-at-millis");
            return new SlotClaim(backupId, slotType, slotIndex, actorUuid, claimedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteDirectoryIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            List<Path> list = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path p : list) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean hasUndeliveredClaims(Path claimDir) {
        if (!Files.isDirectory(claimDir)) {
            return false;
        }
        try (var stream = Files.list(claimDir)) {
            for (Path claimPath : stream.toList()) {
                if (!claimPath.getFileName().toString().endsWith(".yml")) {
                    continue;
                }
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
}
