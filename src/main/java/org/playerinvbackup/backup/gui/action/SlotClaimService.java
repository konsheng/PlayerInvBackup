package org.playerinvbackup.backup.gui.action;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.gui.InventoryUtil;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.render.GuiItemFactory;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 单槽位领取服务
 */
public final class SlotClaimService {
    private static final String MAIN_LABEL = "pib";

    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final GuiItemFactory itemFactory;

    public SlotClaimService(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            GuiItemFactory itemFactory
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.itemFactory = itemFactory;
    }

    public void claimSlot(
            Player admin,
            BackupViewHolder holder,
            SlotType slotType,
            int slotIndex,
            byte[] itemBytes
    ) {
        if (itemBytes == null || itemBytes.length == 0) {
            return;
        }

        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }

        BackupStore store = resolveStoreOrError(admin, true);
        if (store == null) {
            return;
        }

        inventory.setItem(slotIndex, itemFactory.processingItem());
        platformBridge.syncIfViewing(admin, inventory);

        UUID actorUuid = admin.getUniqueId();
        String actorName = admin.getName();
        long now = System.currentTimeMillis();

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            boolean claimed;
            try {
                claimed = store.tryClaimSlot(
                        holder.targetUuid(),
                        holder.backupId(),
                        slotType,
                        slotIndex,
                        actorUuid,
                        actorName,
                        now,
                        itemBytes
                );
            } catch (Exception e) {
                runOnPlayer(admin, () -> {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.gui.claim-failed",
                                    Placeholder.unparsed("actor", actorName),
                                    Placeholder.unparsed("actor_uuid", actorUuid.toString()),
                                    Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                                    Placeholder.unparsed("backup_id", holder.backupId()),
                                    Placeholder.unparsed("slot", slotType + ":" + slotIndex)
                            ),
                            e
                    );
                    Chat.error(admin, "errors.claim-failed");
                    restoreOriginalSlot(admin, holder, slotType, slotIndex);
                });
                return;
            }

            if (!claimed) {
                runOnPlayer(admin, () -> {
                    Chat.warn(admin, "errors.already-claimed");
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                });
                return;
            }

            runOnPlayer(admin, () -> {
                final ItemStack item;
                try {
                    item = ItemStack.deserializeBytes(itemBytes);
                } catch (Exception e) {
                    plugin.getLogger().warning(
                            plugin.lang().plain(
                                    "console.gui.claim-incompatible",
                                    Placeholder.unparsed("actor", actorName),
                                    Placeholder.unparsed("actor_uuid", actorUuid.toString()),
                                    Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                                    Placeholder.unparsed("backup_id", holder.backupId()),
                                    Placeholder.unparsed("slot", slotType + ":" + slotIndex),
                                    Placeholder.unparsed("mode", "runtime_deserialize_failed"),
                                    Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                            )
                    );
                    Chat.error(admin, "errors.claim-incompatible");
                    restoreOriginalSlot(admin, holder, slotType, slotIndex);
                    return;
                }

                ItemStack[] before = cloneStorage(admin.getInventory().getStorageContents());
                boolean delivered = InventoryUtil.tryInsertIntoStorage(admin.getInventory(), item);
                if (delivered) {
                    Chat.success(admin, "success.claim-success", Placeholder.unparsed("amount", String.valueOf(item.getAmount())));
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                    Bukkit.getAsyncScheduler().runNow(plugin, ignored2 -> {
                        boolean marked;
                        try {
                            marked = store.markDelivered(
                                    actorUuid,
                                    holder.targetUuid(),
                                    holder.backupId(),
                                    slotType,
                                    slotIndex,
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
                                Chat.error(admin, "errors.claim-mark-failed-rollback", Placeholder.unparsed("label", MAIN_LABEL));
                            });
                            plugin.auditService().log(
                                    "CLAIM_SLOT",
                                    admin,
                                    holder.targetUuid(),
                                    holder.targetName(),
                                    holder.backupId(),
                                    "slot=" + slotType.name() + ":" + slotIndex
                                            + " delivered=false reason=db_mark_failed"
                                            + " item=" + item.getType().name()
                                            + " x" + item.getAmount()
                            );
                            return;
                        }

                        plugin.auditService().log(
                                "CLAIM_SLOT",
                                admin,
                                holder.targetUuid(),
                                holder.targetName(),
                                holder.backupId(),
                                "slot=" + slotType.name() + ":" + slotIndex
                                        + " delivered=true"
                                        + " item=" + item.getType().name()
                                        + " x" + item.getAmount()
                        );
                    });
                } else {
                    Chat.error(admin, "errors.inventory-full-pending", Placeholder.unparsed("label", MAIN_LABEL));
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                    plugin.auditService().log(
                            "CLAIM_SLOT",
                            admin,
                            holder.targetUuid(),
                            holder.targetName(),
                            holder.backupId(),
                            "slot=" + slotType.name() + ":" + slotIndex
                                    + " delivered=false reason=inventory_full"
                                    + " item=" + item.getType().name()
                                    + " x" + item.getAmount()
                    );
                }
            });
        });
    }

    public void copySlot(
            Player admin,
            BackupViewHolder holder,
            SlotType slotType,
            int slotIndex,
            byte[] itemBytes
    ) {
        if (admin == null || itemBytes == null || itemBytes.length == 0) {
            return;
        }

        final ItemStack item;
        try {
            item = ItemStack.deserializeBytes(itemBytes);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    plugin.lang().plain(
                            "console.gui.claim-incompatible",
                            Placeholder.unparsed("actor", admin.getName()),
                            Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                            Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                            Placeholder.unparsed("backup_id", holder.backupId()),
                            Placeholder.unparsed("slot", slotType + ":" + slotIndex),
                            Placeholder.unparsed("mode", "runtime_deserialize_failed"),
                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                    )
            );
            Chat.error(admin, "errors.claim-incompatible");
            return;
        }

        ItemStack copy = item.clone();
        boolean delivered = InventoryUtil.tryInsertIntoStorage(admin.getInventory(), copy);
        if (!delivered) {
            Chat.warn(admin, "errors.preview-claim-inventory-full");
            plugin.auditService().log(
                    "CLAIM_SLOT",
                    admin,
                    holder.targetUuid(),
                    holder.targetName(),
                    holder.backupId(),
                    "slot=" + slotType.name() + ":" + slotIndex
                            + " mode=infinite delivered=false reason=inventory_full"
                            + " item=" + item.getType().name()
                            + " x" + item.getAmount()
            );
            return;
        }

        Chat.success(admin, "success.preview-claim-success", Placeholder.unparsed("amount", String.valueOf(item.getAmount())));
        plugin.auditService().log(
                "CLAIM_SLOT",
                admin,
                holder.targetUuid(),
                holder.targetName(),
                holder.backupId(),
                "slot=" + slotType.name() + ":" + slotIndex
                        + " mode=infinite delivered=true"
                        + " item=" + item.getType().name()
                        + " x" + item.getAmount()
        );
    }

    private void refreshSingleSlot(Player admin, BackupViewHolder holder, SlotType slotType, int slotIndex, boolean claimed) {
        Inventory inventory = holder.getInventory();
        if (inventory == null || !platformBridge.isViewing(admin, inventory)) {
            return;
        }
        if (slotType == SlotType.INV && slotIndex >= 0 && slotIndex < holder.claimedInv().length) {
            holder.claimedInv()[slotIndex] = claimed;
        } else if (slotType == SlotType.ENDER && slotIndex >= 0 && slotIndex < holder.claimedEnder().length) {
            holder.claimedEnder()[slotIndex] = claimed;
        }
        inventory.setItem(
                slotIndex,
                itemFactory.namedItem(
                        Material.BARRIER,
                        plugin.lang().msg("gui.backup-view.claimed.name"),
                        plugin.lang().msgList("gui.backup-view.claimed.lore")
                )
        );
        platformBridge.syncIfViewing(admin, inventory);
    }

    private void restoreOriginalSlot(Player admin, BackupViewHolder holder, SlotType slotType, int slotIndex) {
        Inventory inventory = holder.getInventory();
        if (inventory == null || !platformBridge.isViewing(admin, inventory)) {
            return;
        }

        if (slotType == SlotType.INV) {
            if (slotIndex < 0 || slotIndex >= holder.parts().inventorySlotBytes().length) {
                return;
            }
            byte[] itemBytes = holder.parts().inventorySlotBytes()[slotIndex];
            inventory.setItem(slotIndex, itemFactory.previewSlotItem(holder, SlotType.INV, slotIndex, itemBytes));
            platformBridge.syncIfViewing(admin, inventory);
            return;
        }

        if (slotIndex < 0 || slotIndex >= holder.parts().enderChestSlotBytes().length) {
            return;
        }
        byte[] itemBytes = holder.parts().enderChestSlotBytes()[slotIndex];
        inventory.setItem(slotIndex, itemFactory.previewSlotItem(holder, SlotType.ENDER, slotIndex, itemBytes));
        platformBridge.syncIfViewing(admin, inventory);
    }

    private BackupStore resolveStoreOrError(Player player, boolean closeMenu) {
        BackupStore store = plugin.store();
        if (store != null && plugin.isStoreReady()) {
            return store;
        }
        if (player != null) {
            if (closeMenu) {
                platformBridge.closeMenu(player);
            }
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
