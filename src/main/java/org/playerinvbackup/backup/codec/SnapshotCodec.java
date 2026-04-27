package org.playerinvbackup.backup.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.playerinvbackup.backup.domain.SnapshotParts;

/**
 * 背包快照编解码器
 *
 * <p>将背包与末影箱槽位序列化为二进制, 并使用 GZIP 压缩保存
 * 快照格式固定为当前最新结构, 仅保留 magic 与槽位数量校验
 */
public final class SnapshotCodec {
    public static final int INVENTORY_SLOT_COUNT = 41;
    public static final int ENDER_CHEST_SLOT_COUNT = 27;

    private static final int MAGIC = 0x424D4255; // BMBU

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
            data.writeInt(INVENTORY_SLOT_COUNT);
            data.writeInt(ENDER_CHEST_SLOT_COUNT);
            writeSlots(data, parts.inventorySlotBytes());
            writeSlots(data, parts.enderChestSlotBytes());
            data.writeBoolean(parts.hasExperienceData());
            data.writeInt(parts.experienceLevel());
            data.writeFloat(parts.experienceProgress());
            data.writeInt(parts.totalExperience());
        }
        return out.toByteArray();
    }

    public static SnapshotParts decodeGzip(byte[] snapshotBytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(snapshotBytes));
             DataInputStream data = new DataInputStream(gzip)) {
            int magic = data.readInt();
            if (magic != MAGIC) {
                throw new IOException("快照数据格式无效: magic=" + Integer.toHexString(magic));
            }
            int invCount = data.readInt();
            int enderCount = data.readInt();
            if (invCount != INVENTORY_SLOT_COUNT || enderCount != ENDER_CHEST_SLOT_COUNT) {
                throw new IOException("快照数据格式无效: inv=" + invCount + ", ender=" + enderCount);
            }

            byte[][] inv = readSlots(data, invCount);
            byte[][] ender = readSlots(data, enderCount);
            boolean hasExperienceData = data.readBoolean();
            int experienceLevel = data.readInt();
            float experienceProgress = data.readFloat();
            int totalExperience = data.readInt();

            return new SnapshotParts(
                    inv,
                    ender,
                    hasExperienceData,
                    experienceLevel,
                    experienceProgress,
                    totalExperience
            );
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
