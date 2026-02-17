package org.baymc.backup.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.baymc.backup.domain.SnapshotParts;

/**
 * 背包快照编解码器
 *
 * <p>将背包与末影箱槽位序列化为二进制, 并使用 GZIP 压缩保存
 * 采用自定义 header (magic + schemaVersion + slotCounts) 便于做兼容校验
 */
public final class SnapshotCodec {
    public static final int SCHEMA_VERSION = 1;

    public static final int INVENTORY_SLOT_COUNT = 41;
    public static final int ENDER_CHEST_SLOT_COUNT = 27;

    private static final int MAGIC = 0x424D4255; // BMBU (插件标识)

    private SnapshotCodec() {
    }

    public static byte[] encodeGzip(SnapshotParts parts) throws IOException {
        if (parts.inventorySlotBytes().length != INVENTORY_SLOT_COUNT) {
            throw new IllegalArgumentException("背包槽位数量不合法: " + parts.inventorySlotBytes().length);
        }
        if (parts.enderChestSlotBytes().length != ENDER_CHEST_SLOT_COUNT) {
            throw new IllegalArgumentException("末影箱槽位数量不合法: " + parts.enderChestSlotBytes().length);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeInt(MAGIC);
            data.writeInt(SCHEMA_VERSION);
            data.writeInt(INVENTORY_SLOT_COUNT);
            data.writeInt(ENDER_CHEST_SLOT_COUNT);
            writeSlots(data, parts.inventorySlotBytes());
            writeSlots(data, parts.enderChestSlotBytes());
        }
        return out.toByteArray();
    }

    public static SnapshotParts decodeGzip(byte[] snapshotBytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(snapshotBytes));
             DataInputStream data = new DataInputStream(gzip)) {
            int magic = data.readInt();
            if (magic != MAGIC) {
                throw new IOException("备份快照标识不匹配: " + Integer.toHexString(magic));
            }
            int schemaVersion = data.readInt();
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IOException("不支持的快照版本: " + schemaVersion);
            }
            int invCount = data.readInt();
            int enderCount = data.readInt();
            if (invCount != INVENTORY_SLOT_COUNT || enderCount != ENDER_CHEST_SLOT_COUNT) {
                throw new IOException("不支持的槽位数量: inv=" + invCount + ", ender=" + enderCount);
            }
            byte[][] inv = readSlots(data, invCount);
            byte[][] ender = readSlots(data, enderCount);
            return new SnapshotParts(inv, ender);
        }
    }

    private static void writeSlots(DataOutputStream data, byte[][] slots) throws IOException {
        for (byte[] slot : slots) {
            if (slot == null || slot.length == 0) {
                data.writeInt(-1);
            } else {
                data.writeInt(slot.length);
                data.write(slot);
            }
        }
    }

    private static byte[][] readSlots(DataInputStream data, int count) throws IOException {
        byte[][] slots = new byte[count][];
        for (int i = 0; i < count; i++) {
            int len = data.readInt();
            if (len < 0) {
                slots[i] = null;
                continue;
            }
            if (len > 10_000_000) {
                throw new IOException("槽位数据过大: " + len);
            }
            byte[] buf = new byte[len];
            data.readFully(buf);
            slots[i] = buf;
        }
        return slots;
    }
}
