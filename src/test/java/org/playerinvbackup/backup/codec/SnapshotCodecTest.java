package org.playerinvbackup.backup.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.playerinvbackup.backup.domain.SnapshotParts;

class SnapshotCodecTest {
    private static final int MAGIC = 0x424D4255;

    @Test
    void encodedSnapshotUsesSchemaLessHeaderAndPreservesExperienceData() throws Exception {
        SnapshotParts parts = sampleSnapshotParts(SnapshotCodec.ENDER_CHEST_SLOT_COUNT);

        byte[] encoded = SnapshotCodec.encodeGzip(parts);

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded));
             DataInputStream data = new DataInputStream(gzip)) {
            assertEquals(MAGIC, data.readInt());
            assertEquals(SnapshotCodec.INVENTORY_SLOT_COUNT, data.readInt());
            assertEquals(SnapshotCodec.ENDER_CHEST_SLOT_COUNT, data.readInt());
        }

        SnapshotParts decoded = SnapshotCodec.decodeGzip(encoded);
        assertArrayEquals(parts.inventorySlotBytes()[0], decoded.inventorySlotBytes()[0]);
        assertArrayEquals(parts.inventorySlotBytes()[40], decoded.inventorySlotBytes()[40]);
        assertArrayEquals(parts.enderChestSlotBytes()[26], decoded.enderChestSlotBytes()[26]);
        assertTrue(decoded.hasExperienceData());
        assertEquals(parts.experienceLevel(), decoded.experienceLevel());
        assertEquals(parts.experienceProgress(), decoded.experienceProgress());
        assertEquals(parts.totalExperience(), decoded.totalExperience());
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 18, 27, 36, 45, 54})
    void roundTripSupportsDynamicEnderSlotCounts(int enderSlotCount) throws Exception {
        SnapshotParts parts = sampleSnapshotParts(enderSlotCount);

        SnapshotParts decoded = SnapshotCodec.decodeGzip(SnapshotCodec.encodeGzip(parts));

        assertEquals(enderSlotCount, decoded.enderChestSlotBytes().length);
        assertArrayEquals(
                parts.enderChestSlotBytes()[enderSlotCount - 1],
                decoded.enderChestSlotBytes()[enderSlotCount - 1]
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, 10, 55, 63})
    void invalidEnderSlotCountsAreRejectedOnEncode(int enderSlotCount) {
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encodeGzip(emptySnapshotParts(enderSlotCount)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, 10, 55, 63})
    void invalidEnderSlotCountsAreRejectedOnDecode(int enderSlotCount) {
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeHeaderOnlySnapshot(enderSlotCount)));
    }

    @Test
    void legacySchemaVersionedSnapshotsAreRejected() {
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeLegacyVersionedHeader(1)));
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeLegacyVersionedHeader(2)));
    }

    @Test
    void invalidMagicIsRejected() {
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeInvalidMagicSnapshot()));
    }

    private static SnapshotParts sampleSnapshotParts(int enderSlotCount) {
        SnapshotParts parts = emptySnapshotParts(enderSlotCount);
        parts.inventorySlotBytes()[0] = new byte[]{1, 2, 3};
        parts.inventorySlotBytes()[40] = new byte[]{9, 8};
        if (enderSlotCount > 26) {
            parts.enderChestSlotBytes()[26] = new byte[]{4, 5, 6};
        }
        parts.enderChestSlotBytes()[enderSlotCount - 1] = new byte[]{7, 6, 5};
        return parts;
    }

    private static SnapshotParts emptySnapshotParts(int enderSlotCount) {
        return new SnapshotParts(
                new byte[SnapshotCodec.INVENTORY_SLOT_COUNT][],
                new byte[enderSlotCount][],
                true,
                32,
                0.75f,
                1234
        );
    }

    private static byte[] encodeLegacyVersionedHeader(int schemaVersion) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeInt(MAGIC);
            data.writeInt(schemaVersion);
            data.writeInt(SnapshotCodec.INVENTORY_SLOT_COUNT);
            data.writeInt(SnapshotCodec.ENDER_CHEST_SLOT_COUNT);
        }
        return out.toByteArray();
    }

    private static byte[] encodeInvalidMagicSnapshot() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeInt(0x12345678);
            data.writeInt(SnapshotCodec.INVENTORY_SLOT_COUNT);
            data.writeInt(SnapshotCodec.ENDER_CHEST_SLOT_COUNT);
        }
        return out.toByteArray();
    }

    private static byte[] encodeHeaderOnlySnapshot(int enderSlotCount) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeInt(MAGIC);
            data.writeInt(SnapshotCodec.INVENTORY_SLOT_COUNT);
            data.writeInt(enderSlotCount);
        }
        return out.toByteArray();
    }
}
