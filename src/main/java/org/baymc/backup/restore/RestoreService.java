package org.baymc.backup.restore;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.codec.SnapshotCodec;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SlotClaim;
import org.baymc.backup.domain.SlotType;
import org.baymc.backup.domain.SnapshotParts;
import org.baymc.backup.domain.TriggerType;
import org.baymc.backup.text.Chat;
import org.baymc.backup.util.Hashing;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 恢复服务
 *
 * <p>将备份快照恢复到目标玩家(仅在线), 并根据领取记录扣除已领取格子, 防止复制
 * 恢复前会校验 sha256, 避免将损坏数据写入玩家背包
 */
public final class RestoreService {
    private final BayMcBackUpPlugin plugin;

    public RestoreService(BayMcBackUpPlugin plugin) {
        this.plugin = plugin;
    }

    public void restoreToPlayer(CommandSender actor, Player target, String backupId) {
        if (!plugin.isStoreReady()) {
            runOnActor(actor, () -> Chat.error(actor, "errors.store-unavailable", Placeholder.unparsed("label", "bmbackup")));
            return;
        }

        String actorName = actor == null ? "-" : actor.getName();
        String actorDetails = actor instanceof Player p ? actorName + "(" + p.getUniqueId() + ")" : actorName;
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName();
        runOnActor(actor, () -> Chat.info(actor, "info.restoring-loading"));

        var store = plugin.store();
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            BackupRecord record;
            List<SlotClaim> claims;
            try {
                record = store.loadBackup(targetUuid, backupId).orElse(null);
                if (record == null) {
                    runOnActor(actor, () -> Chat.error(actor, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                    return;
                }
                claims = store.listClaims(targetUuid, backupId);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.restore.read-failed",
                                Placeholder.unparsed("actor", actorDetails),
                                Placeholder.unparsed("target", targetName),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("backup_id", backupId)
                        ),
                        e
                );
                runOnActor(actor, () -> Chat.error(actor, "errors.read-failed"));
                return;
            }

            String expectedSha256 = record.meta().sha256Hex();
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actualSha256 = Hashing.sha256Hex(record.snapshotBytes());
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    plugin.getLogger().severe(plugin.lang().plain(
                            "console.restore.sha-mismatch",
                            Placeholder.unparsed("actor", actorDetails),
                            Placeholder.unparsed("target", targetName),
                            Placeholder.unparsed("target_uuid", targetUuid.toString()),
                            Placeholder.unparsed("backup_id", backupId),
                            Placeholder.unparsed("expected", expectedSha256),
                            Placeholder.unparsed("actual", actualSha256)
                    ));
                    runOnActor(actor, () -> Chat.error(
                            actor,
                            "errors.snapshot-hash-mismatch",
                            Placeholder.unparsed("backup_id", backupId),
                            Placeholder.unparsed("expected", expectedSha256),
                            Placeholder.unparsed("actual", actualSha256)
                    ));
                    return;
                }
            }

            SnapshotParts parts;
            try {
                parts = SnapshotCodec.decodeGzip(record.snapshotBytes());
            } catch (IOException e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.restore.snapshot-invalid",
                                Placeholder.unparsed("actor", actorDetails),
                                Placeholder.unparsed("target", targetName),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("backup_id", backupId)
                        ),
                        e
                );
                runOnActor(actor, () -> Chat.error(actor, "errors.snapshot-invalid"));
                return;
            }

            Set<String> claimed = new HashSet<>();
            for (SlotClaim claim : claims) {
                claimed.add(claim.slotType().name() + ":" + claim.slotIndex());
            }

            runOnPlayer(target, () -> {
                if (!target.isOnline()) {
                    runOnActor(actor, () -> Chat.error(actor, "errors.target-offline"));
                    return;
                }

                var backupService = plugin.backupService();
                if (backupService != null) {
                    boolean queued = backupService.requestBackup(target, TriggerType.MANUAL);
                    if (!queued) {
                        runOnActor(actor, () -> Chat.warn(actor, "warn.auto-backup-before-restore-failed"));
                    }
                }

                try {
                    applySnapshot(target, parts, claimed);
                } catch (Exception e) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.restore.apply-failed",
                                    Placeholder.unparsed("actor", actorDetails),
                                    Placeholder.unparsed("target", targetName),
                                    Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                    Placeholder.unparsed("backup_id", backupId)
                            ),
                            e
                    );
                    runOnActor(actor, () -> Chat.error(actor, "errors.restore-failed"));
                    return;
                }

                runOnActor(actor, () -> Chat.success(actor, "success.restore-success"));
                Chat.warn(target, "warn.restored-notify-target");

                plugin.auditService().log(
                        "RESTORE",
                        actor,
                        target.getUniqueId(),
                        target.getName(),
                        backupId,
                        "claimedSlots=" + claimed.size()
                );
            });
        });
    }

    private static void applySnapshot(Player target, SnapshotParts parts, Set<String> claimedKeys) {
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            if (claimedKeys.contains(SlotType.INV.name() + ":" + i)) {
                storage[i] = null;
                continue;
            }
            byte[] bytes = parts.inventorySlotBytes()[i];
            storage[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            int idx = 36 + i;
            if (claimedKeys.contains(SlotType.INV.name() + ":" + idx)) {
                armor[i] = null;
                continue;
            }
            byte[] bytes = parts.inventorySlotBytes()[idx];
            armor[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        ItemStack offhand;
        if (claimedKeys.contains(SlotType.INV.name() + ":" + 40)) {
            offhand = null;
        } else {
            byte[] bytes = parts.inventorySlotBytes()[40];
            offhand = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        ItemStack[] ender = new ItemStack[SnapshotCodec.ENDER_CHEST_SLOT_COUNT];
        for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT; i++) {
            if (claimedKeys.contains(SlotType.ENDER.name() + ":" + i)) {
                ender[i] = null;
                continue;
            }
            byte[] bytes = parts.enderChestSlotBytes()[i];
            ender[i] = bytes == null ? null : ItemStack.deserializeBytes(bytes);
        }

        target.closeInventory();
        target.getInventory().setStorageContents(storage);
        target.getInventory().setArmorContents(armor);
        target.getInventory().setItemInOffHand(offhand);
        target.getEnderChest().setContents(ender);
        target.updateInventory();
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }

    private void runOnActor(CommandSender actor, Runnable runnable) {
        if (actor == null) {
            return;
        }
        if (actor instanceof Player player) {
            runOnPlayer(player, runnable);
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }
}
