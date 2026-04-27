package org.playerinvbackup.backup.app;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.config.PluginConfig;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.util.Hashing;
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
    private static final int PURGE_TRIGGER_THRESHOLD = 10;

    @FunctionalInterface
    public interface BackupCompletion {
        void complete(boolean success, String backupId);
    }

    private final PlayerInvBackupPlugin plugin;
    private final PluginConfig config;
    private final BackupStore store;
    private final IoDispatcher ioDispatcher;
    private final ConcurrentHashMap<UUID, AtomicInteger> pendingPurgeCounts = new ConcurrentHashMap<>();

    public BackupService(PlayerInvBackupPlugin plugin, PluginConfig config, BackupStore store, IoDispatcher ioDispatcher) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.ioDispatcher = ioDispatcher;
    }

    public boolean requestBackup(Player player, TriggerType triggerType) {
        return requestBackup(player, triggerType, BackupLocationContext.fromCurrentLocation(player.getLocation()), null);
    }

    public boolean requestBackup(Player player, TriggerType triggerType, BackupCompletion completion) {
        return requestBackup(player, triggerType, BackupLocationContext.fromCurrentLocation(player.getLocation()), completion);
    }

    public boolean requestBackup(Player player, TriggerType triggerType, BackupLocationContext locationContext) {
        return requestBackup(player, triggerType, locationContext, null);
    }

    public boolean requestBackup(Player player, TriggerType triggerType, BackupLocationContext locationContext, BackupCompletion completion) {
        if (!ioDispatcher.hasCapacity()) {
            return false;
        }

        BackupLocationContext safeLocationContext = locationContext == null
                ? BackupLocationContext.fromCurrentLocation(player.getLocation())
                : locationContext;
        SnapshotParts parts = captureSnapshot(player);
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        long now = System.currentTimeMillis();
        String backupId = BackupIdGenerator.newId(now);

        return ioDispatcher.submitWrite(() -> {
            boolean success = false;
            boolean completionDelivered = false;
            try {
                byte[] snapshotBytes = SnapshotCodec.encodeGzip(parts);
                String sha256 = Hashing.sha256Hex(snapshotBytes);
                BackupMeta meta = new BackupMeta(
                        backupId,
                        playerUuid,
                        now,
                        triggerType,
                        sha256,
                        snapshotBytes.length,
                        false,
                        "",
                        safeLocationContext.worldName(),
                        safeLocationContext.locationX(),
                        safeLocationContext.locationY(),
                        safeLocationContext.locationZ(),
                        safeLocationContext.targetWorldName(),
                        safeLocationContext.targetLocationX(),
                        safeLocationContext.targetLocationY(),
                        safeLocationContext.targetLocationZ(),
                        safeLocationContext.killerPlayerUuid(),
                        safeLocationContext.killerPlayerName()
                );
                store.saveBackup(new BackupRecord(meta, snapshotBytes));
                success = true;
                notifyCompletion(completion, true, backupId);
                completionDelivered = true;
                maybePurgeBackups(playerUuid, playerName, now, backupId);
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
            } finally {
                if (!completionDelivered) {
                    notifyCompletion(completion, success, backupId);
                }
            }
        });
    }

    private void maybePurgeBackups(UUID playerUuid, String playerName, long createdAtMillis, String backupId) {
        if (!isPurgeEnabled()) {
            return;
        }

        AtomicInteger counter = pendingPurgeCounts.computeIfAbsent(playerUuid, ignored -> new AtomicInteger());
        int current = counter.incrementAndGet();
        if (current < PURGE_TRIGGER_THRESHOLD) {
            return;
        }

        long keepAfterMillis = computeKeepAfterMillis(createdAtMillis);
        try {
            store.purgeBackups(playerUuid, config.keepPerPlayer(), keepAfterMillis);
            counter.set(0);
        } catch (Exception e) {
            counter.set(PURGE_TRIGGER_THRESHOLD - 1);
            plugin.getLogger().log(
                    Level.WARNING,
                    plugin.lang().plain(
                            "console.backup.purge-failed",
                            Placeholder.unparsed("player", playerName),
                            Placeholder.unparsed("uuid", playerUuid.toString()),
                            Placeholder.unparsed("backup_id", backupId),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    ),
                    e
            );
        }
    }

    private boolean isPurgeEnabled() {
        return config.keepPerPlayer() > 0 || computeKeepAfterMillis(System.currentTimeMillis()) > 0;
    }

    private long computeKeepAfterMillis(long referenceMillis) {
        Duration keepDuration = config.keepDuration();
        if (keepDuration == null) {
            return 0L;
        }
        long millis = keepDuration.toMillis();
        if (millis <= 0L) {
            return 0L;
        }
        return Math.max(0L, referenceMillis - millis);
    }

    private void notifyCompletion(BackupCompletion completion, boolean success, String backupId) {
        if (completion == null) {
            return;
        }
        try {
            completion.complete(success, backupId);
        } catch (Exception e) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Backup completion callback failed: backupId=" + backupId + ", success=" + success,
                    e
            );
        }
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

        return new SnapshotParts(
                invSlots,
                enderSlots,
                true,
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience()
        );
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
