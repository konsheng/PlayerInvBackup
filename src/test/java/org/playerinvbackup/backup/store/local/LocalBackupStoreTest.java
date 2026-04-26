package org.playerinvbackup.backup.store.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.domain.UndeliveredClaim;
import org.playerinvbackup.backup.store.BackupQuery;

class LocalBackupStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void listBackupsAndLoadBackupStayConsistentAfterCacheUpdates() throws Exception {
        LocalBackupStore store = new LocalBackupStore(tempDir.resolve("store"));
        store.init();

        UUID playerUuid = UUID.randomUUID();
        BackupRecord backup1 = backup(playerUuid, "b1", 1_000L, false, "note-1", new byte[]{1});
        BackupRecord backup2 = backup(playerUuid, "b2", 2_000L, false, "note-2", new byte[]{2});
        BackupRecord backup3 = backup(playerUuid, "b3", 3_000L, false, "note-3", new byte[]{3});

        store.saveBackup(backup1);
        store.saveBackup(backup2);

        List<BackupMeta> firstPage = store.listBackups(playerUuid, BackupQuery.all(), 0, 10);
        assertEquals(List.of("b2", "b1"), firstPage.stream().map(BackupMeta::backupId).toList());

        assertTrue(store.setBackupLocked(playerUuid, "b1", true));
        assertTrue(store.setBackupNote(playerUuid, "b2", "changed"));
        store.saveBackup(backup3);

        List<BackupMeta> updated = store.listBackups(playerUuid, BackupQuery.all(), 0, 10);
        assertEquals(List.of("b1", "b3", "b2"), updated.stream().map(BackupMeta::backupId).toList());
        assertEquals("changed", updated.stream()
                .filter(meta -> meta.backupId().equals("b2"))
                .findFirst()
                .orElseThrow()
                .note());

        BackupRecord loaded = store.loadBackup(playerUuid, "b3").orElseThrow();
        assertEquals("b3", loaded.meta().backupId());
        assertArrayEquals(new byte[]{3}, loaded.snapshotBytes());
    }

    @Test
    void purgeBackupsUsesCachedMetasAndKeepsBackupsWithUndeliveredClaims() throws Exception {
        LocalBackupStore store = new LocalBackupStore(tempDir.resolve("store"));
        store.init();

        UUID playerUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        BackupRecord oldProtected = backup(playerUuid, "protected", 1_000L, false, "", new byte[]{1});
        BackupRecord oldRemoved = backup(playerUuid, "removed", 2_000L, false, "", new byte[]{2});
        BackupRecord newest = backup(playerUuid, "newest", 3_000L, false, "", new byte[]{3});

        store.saveBackup(oldProtected);
        store.saveBackup(oldRemoved);
        store.saveBackup(newest);
        store.tryClaimSlot(playerUuid, "protected", SlotType.INV, 0, actorUuid, "actor", 4_000L, new byte[]{9});

        store.listBackups(playerUuid, BackupQuery.all(), 0, 10);
        store.listUndelivered(actorUuid, 10);

        store.purgeBackups(playerUuid, 1, 0L);

        List<BackupMeta> remaining = store.listBackups(playerUuid, BackupQuery.all(), 0, 10);
        assertEquals(List.of("newest", "protected"), remaining.stream().map(BackupMeta::backupId).toList());
    }

    @Test
    void listUndeliveredTracksClaimAndDeliveryUpdates() throws Exception {
        LocalBackupStore store = new LocalBackupStore(tempDir.resolve("store"));
        store.init();

        UUID playerUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        store.saveBackup(backup(playerUuid, "b1", 1_000L, false, "", new byte[]{7}));

        assertTrue(store.tryClaimSlot(playerUuid, "b1", SlotType.ENDER, 5, actorUuid, "actor", 2_000L, new byte[]{8}));

        List<UndeliveredClaim> pending = store.listUndelivered(actorUuid, 10);
        assertEquals(1, pending.size());
        assertEquals("b1", pending.getFirst().backupId());
        assertEquals(1, store.listClaims(playerUuid, "b1").size());

        assertTrue(store.markDelivered(actorUuid, playerUuid, "b1", SlotType.ENDER, 5, 3_000L));

        assertTrue(store.listUndelivered(actorUuid, 10).isEmpty());
        assertEquals(1, store.listClaims(playerUuid, "b1").size());
    }

    @Test
    void worldChangeMetadataSurvivesLoadAndCacheUpdates() throws Exception {
        LocalBackupStore store = new LocalBackupStore(tempDir.resolve("store"));
        store.init();

        UUID playerUuid = UUID.randomUUID();
        BackupMeta meta = new BackupMeta(
                "wc1",
                playerUuid,
                1_000L,
                TriggerType.WORLD_CHANGE,
                1,
                "sha256-wc1",
                3,
                false,
                "",
                "world",
                100.0,
                64.0,
                -30.0,
                "world_nether",
                12.0,
                72.0,
                8.0
        );
        store.saveBackup(new BackupRecord(meta, new byte[]{1, 2, 3}));

        BackupMeta loaded = store.loadBackup(playerUuid, "wc1").orElseThrow().meta();
        assertEquals("world", loaded.worldName());
        assertEquals(Double.valueOf(100.0), loaded.locationX());
        assertEquals("world_nether", loaded.targetWorldName());
        assertEquals(Double.valueOf(12.0), loaded.targetLocationX());
        assertEquals(Double.valueOf(72.0), loaded.targetLocationY());
        assertEquals(Double.valueOf(8.0), loaded.targetLocationZ());

        store.listBackups(playerUuid, BackupQuery.all(), 0, 10);
        assertTrue(store.setBackupLocked(playerUuid, "wc1", true));
        assertTrue(store.setBackupNote(playerUuid, "wc1", "route"));

        BackupMeta updated = store.listBackups(playerUuid, BackupQuery.all(), 0, 10).getFirst();
        assertEquals("world_nether", updated.targetWorldName());
        assertEquals(Double.valueOf(12.0), updated.targetLocationX());
        assertEquals(Double.valueOf(72.0), updated.targetLocationY());
        assertEquals(Double.valueOf(8.0), updated.targetLocationZ());
        assertEquals("route", updated.note());
        assertTrue(updated.locked());
    }

    private static BackupRecord backup(
            UUID playerUuid,
            String backupId,
            long createdAtMillis,
            boolean locked,
            String note,
            byte[] snapshotBytes
    ) {
        BackupMeta meta = new BackupMeta(
                backupId,
                playerUuid,
                createdAtMillis,
                TriggerType.MANUAL,
                1,
                "sha256-" + backupId,
                snapshotBytes.length,
                locked,
                note,
                "world",
                1.0,
                64.0,
                1.0
        );
        return new BackupRecord(meta, snapshotBytes);
    }
}
