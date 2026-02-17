package org.baymc.backup.app;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.codec.SnapshotCodec;
import org.baymc.backup.config.PluginConfig;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SnapshotParts;
import org.baymc.backup.domain.TriggerType;
import org.baymc.backup.store.BackupStore;
import org.baymc.backup.util.Hashing;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 备份服务
 *
 * <p>负责从玩家身上抓取背包与末影箱快照, 编码压缩后写入存储
 * 写入过程通过 {@link IoDispatcher} 异步执行, 避免阻塞 Folia 的 Region 线程
 */
public final class BackupService {
    private final BayMcBackUpPlugin plugin;
    private final PluginConfig config;
    private final BackupStore store;
    private final IoDispatcher ioDispatcher;

    public BackupService(BayMcBackUpPlugin plugin, PluginConfig config, BackupStore store, IoDispatcher ioDispatcher) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.ioDispatcher = ioDispatcher;
    }

    public boolean requestBackup(Player player, TriggerType triggerType) {
        if (!ioDispatcher.hasCapacity()) {
            return false;
        }

        SnapshotParts parts = captureSnapshot(player);
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        long now = System.currentTimeMillis();
        String backupId = BackupIdGenerator.newId(now);

        return ioDispatcher.submitWrite(() -> {
            try {
                byte[] snapshotBytes = SnapshotCodec.encodeGzip(parts);
                String sha256 = Hashing.sha256Hex(snapshotBytes);
                BackupMeta meta = new BackupMeta(
                        backupId,
                        playerUuid,
                        now,
                        triggerType,
                        SnapshotCodec.SCHEMA_VERSION,
                        sha256,
                        snapshotBytes.length,
                        false,
                        ""
                );
                store.saveBackup(new BackupRecord(meta, snapshotBytes));
                long keepAfterMillis = 0L;
                Duration keepDuration = config.keepDuration();
                if (keepDuration != null) {
                    long millis = keepDuration.toMillis();
                    if (millis > 0) {
                        keepAfterMillis = Math.max(0L, now - millis);
                    }
                }
                store.purgeBackups(playerUuid, config.keepPerPlayer(), keepAfterMillis);
            } catch (IOException e) {
                plugin.getLogger().severe(plugin.lang().plain(
                        "console.backup.encode-failed",
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("uuid", playerUuid.toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                ));
            } catch (Exception e) {
                plugin.getLogger().severe(plugin.lang().plain(
                        "console.backup.write-failed",
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("uuid", playerUuid.toString()),
                        Placeholder.unparsed("backup_id", backupId),
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                ));
            }
        });
    }

    private static SnapshotParts captureSnapshot(Player player) {
        PlayerInventory inv = player.getInventory();

        byte[][] invSlots = new byte[SnapshotCodec.INVENTORY_SLOT_COUNT][];

        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < 36; i++) {
            invSlots[i] = toBytes(storage[i]);
        }

        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < 4; i++) {
            invSlots[36 + i] = toBytes(armor[i]);
        }

        invSlots[40] = toBytes(inv.getItemInOffHand());

        byte[][] enderSlots = new byte[SnapshotCodec.ENDER_CHEST_SLOT_COUNT][];
        ItemStack[] ender = player.getEnderChest().getContents();
        for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT; i++) {
            enderSlots[i] = toBytes(ender[i]);
        }

        return new SnapshotParts(invSlots, enderSlots);
    }

    private static byte[] toBytes(ItemStack item) {
        if (item == null) {
            return null;
        }
        Material type = item.getType();
        if (type == null || type.isAir()) {
            return null;
        }
        return item.serializeAsBytes();
    }
}
