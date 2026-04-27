package org.playerinvbackup.backup.gui.action;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.UndeliveredClaim;
import org.playerinvbackup.backup.gui.InventoryUtil;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 待投递领取服务, 从 GUI 外壳中抽出待投递业务流程
 */
public final class PendingDeliveryService {
    private static final String MAIN_LABEL = "pib";

    private final PlayerInvBackupPlugin plugin;

    public PendingDeliveryService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    public void deliverPending(Player admin) {
        BackupStore store = resolveStoreOrError(admin);
        if (store == null) {
            return;
        }
        UUID actorUuid = admin.getUniqueId();
        String actorName = admin.getName();

        runOnPlayer(admin, () -> Chat.info(admin, "info.checking-pending"));
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<UndeliveredClaim> pending;
            try {
                pending = store.listUndelivered(actorUuid, 100);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.pending-load-failed",
                                Placeholder.unparsed("actor", actorName),
                                Placeholder.unparsed("actor_uuid", actorUuid.toString())
                        ),
                        e
                );
                runOnPlayer(admin, () -> Chat.error(admin, "errors.read-failed"));
                return;
            }

            if (pending.isEmpty()) {
                runOnPlayer(admin, () -> Chat.success(admin, "success.pending-none"));
                return;
        }

        runOnPlayer(admin, () -> deliverPendingSequential(admin, store, pending, 0, 0, 0));
    });
}

private void deliverPendingSequential(
        Player admin,
        BackupStore store,
        List<UndeliveredClaim> pending,
        int index,
        int deliveredCount,
        int skippedIncompatibleCount
) {
    if (admin == null || !admin.isOnline()) {
        return;
    }
    if (index >= pending.size()) {
        if (deliveredCount > 0) {
            Chat.success(admin, "success.pending-delivered", Placeholder.unparsed("count", String.valueOf(deliveredCount)));
        }
        notifySkippedIncompatible(admin, skippedIncompatibleCount);
        plugin.auditService().log(
                "DELIVER_PENDING",
                admin,
                null,
                null,
                null,
                "delivered=" + deliveredCount
                        + " remaining=" + skippedIncompatibleCount
                        + " skipped_incompatible=" + skippedIncompatibleCount
        );
        return;
    }

    UndeliveredClaim claim = pending.get(index);
    final ItemStack item;
    try {
        item = ItemStack.deserializeBytes(claim.itemBytes());
    } catch (Exception e) {
        plugin.getLogger().warning(
                plugin.lang().plain(
                        "console.gui.pending-incompatible",
                        Placeholder.unparsed("actor", admin.getName()),
                        Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                        Placeholder.unparsed("target_uuid", claim.playerUuid().toString()),
                        Placeholder.unparsed("backup_id", claim.backupId()),
                        Placeholder.unparsed("slot", claim.slotType() + ":" + claim.slotIndex()),
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                )
        );
        plugin.auditService().log(
                "DELIVER_PENDING",
                admin,
                claim.playerUuid(),
                null,
                claim.backupId(),
                "slot=" + claim.slotType().name() + ":" + claim.slotIndex()
                        + " delivered=false skipped=true reason=incompatible_item"
        );
        deliverPendingSequential(admin, store, pending, index + 1, deliveredCount, skippedIncompatibleCount + 1);
        return;
    }
    ItemStack[] before = cloneStorage(admin.getInventory().getStorageContents());

    boolean ok = InventoryUtil.tryInsertIntoStorage(admin.getInventory(), item);
    if (!ok) {
        int remaining = Math.max(0, pending.size() - deliveredCount);
        Chat.error(admin, "errors.deliver-inventory-full", Placeholder.unparsed("remaining", String.valueOf(remaining)));
        notifySkippedIncompatible(admin, skippedIncompatibleCount);
        plugin.auditService().log(
                "DELIVER_PENDING",
                admin,
                null,
                null,
                null,
                "delivered=" + deliveredCount
                        + " remaining=" + remaining
                        + " skipped_incompatible=" + skippedIncompatibleCount
                        + " stopped=inventory_full"
        );
        return;
    }

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            boolean marked;
            try {
                marked = store.markDelivered(
                        admin.getUniqueId(),
                        claim.playerUuid(),
                        claim.backupId(),
                        claim.slotType(),
                        claim.slotIndex(),
                        System.currentTimeMillis()
                );
            } catch (Exception e) {
                marked = false;
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.pending-mark-delivered-failed",
                                Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                        ),
                        e
                );
            }

            if (!marked) {
                runOnPlayer(admin, () -> {
                    admin.getInventory().setStorageContents(before);
                    admin.updateInventory();
                    Chat.error(admin, "errors.deliver-mark-failed-rollback");
                    notifySkippedIncompatible(admin, skippedIncompatibleCount);
                });
                return;
            }

            runOnPlayer(admin, () -> deliverPendingSequential(
                    admin,
                    store,
                    pending,
                    index + 1,
                    deliveredCount + 1,
                    skippedIncompatibleCount
            ));
        });
    }

    private void notifySkippedIncompatible(Player admin, int skippedIncompatibleCount) {
        if (admin == null || skippedIncompatibleCount <= 0) {
            return;
        }
        Chat.warn(
                admin,
                "warn.pending-incompatible-skipped",
                Placeholder.unparsed("count", String.valueOf(skippedIncompatibleCount))
        );
    }

    private BackupStore resolveStoreOrError(Player player) {
        BackupStore store = plugin.store();
        if (store != null && plugin.isStoreReady()) {
            return store;
        }
        if (player != null) {
            Chat.error(player, "errors.store-unavailable", Placeholder.unparsed("label", MAIN_LABEL));
        }
        return null;
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }

    private static ItemStack[] cloneStorage(ItemStack[] storageContents) {
        ItemStack[] copy = new ItemStack[storageContents.length];
        for (int i = 0; i < storageContents.length; i++) {
            ItemStack item = storageContents[i];
            copy[i] = item == null ? null : item.clone();
        }
        return copy;
    }
}
