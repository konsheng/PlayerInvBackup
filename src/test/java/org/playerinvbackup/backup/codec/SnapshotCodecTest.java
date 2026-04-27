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
import org.playerinvbackup.backup.domain.SnapshotParts;

/**
 * 该测试文件用于验证快照编解码器已经完全切换到固定的新格式
 * 覆盖新格式头部写入 经验数据往返保留和旧版本头部拒绝场景
 * 确保当前实现不再写入或读取 schemaVersion
 */
class SnapshotCodecTest {
    private static final int MAGIC = 0x424D4255;

    @Test
    // 验证新快照头部在 magic 之后直接写入背包和末影箱槽位数量
    // 同时确认经验数据在编码和解码后保持完整
    void encodedSnapshotUsesSchemaLessHeaderAndPreservesExperienceData() throws Exception {
        SnapshotParts parts = sampleSnapshotParts();

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

    @Test
    // 验证旧的带 schemaVersion 头部的快照数据
    // 在当前固定格式下会被直接判定为无效
    void legacySchemaVersionedSnapshotsAreRejected() {
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeLegacyVersionedHeader(1)));
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeLegacyVersionedHeader(2)));
    }

    @Test
    // 验证 magic 不匹配的快照数据
    // 会在解码开始阶段直接抛出格式错误
    void invalidMagicIsRejected() {
        assertThrows(IOException.class, () -> SnapshotCodec.decodeGzip(encodeInvalidMagicSnapshot()));
    }

    private static SnapshotParts sampleSnapshotParts() {
        byte[][] inventory = new byte[SnapshotCodec.INVENTORY_SLOT_COUNT][];
        inventory[0] = new byte[]{1, 2, 3};
        inventory[40] = new byte[]{9, 8};

        byte[][] ender = new byte[SnapshotCodec.ENDER_CHEST_SLOT_COUNT][];
        ender[26] = new byte[]{7, 6, 5};

        return new SnapshotParts(
                inventory,
                ender,
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
}
