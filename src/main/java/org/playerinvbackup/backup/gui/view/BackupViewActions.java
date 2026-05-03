package org.playerinvbackup.backup.gui.view;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiSoundAction;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.action.PendingDeliveryService;
import org.playerinvbackup.backup.gui.action.ShulkerExportService;
import org.playerinvbackup.backup.gui.action.SlotClaimService;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.list.BackupListController;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.render.BackupViewRenderer;
import org.playerinvbackup.backup.gui.render.RestoreConfirmRenderer;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 详情页点击动作
 */
public final class BackupViewActions {
    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final BackupListController listController;
    private final BackupViewController viewController;
    private final BackupViewRenderer viewRenderer;
    private final RestoreConfirmRenderer restoreConfirmRenderer;
    private final PendingDeliveryService pendingDeliveryService;
    private final SlotClaimService slotClaimService;
    private final ShulkerExportService shulkerExportService;

    public BackupViewActions(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            BackupListController listController,
            BackupViewController viewController,
            BackupViewRenderer viewRenderer,
            RestoreConfirmRenderer restoreConfirmRenderer,
            PendingDeliveryService pendingDeliveryService,
            SlotClaimService slotClaimService,
            ShulkerExportService shulkerExportService
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.listController = listController;
        this.viewController = viewController;
        this.viewRenderer = viewRenderer;
        this.restoreConfirmRenderer = restoreConfirmRenderer;
        this.pendingDeliveryService = pendingDeliveryService;
        this.slotClaimService = slotClaimService;
        this.shulkerExportService = shulkerExportService;
    }

    public void handleClick(Player admin, BackupViewHolder holder, int slot) {
        if (slot == BackupViewRenderer.SLOT_VIEW_BACK) {
            playGuiSound(admin, GuiSoundAction.VIEW_BACK);
            listController.openBackupList(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery());
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_TOGGLE) {
            playGuiSound(admin, GuiSoundAction.VIEW_TOGGLE);
            GuiView next = holder.view() == GuiView.INVENTORY ? GuiView.ENDER_CHEST : GuiView.INVENTORY;
            runOnPlayer(admin, () -> {
                Inventory top = holder.getInventory();
                if (top == null || !platformBridge.isViewing(admin, top)) {
                    return;
                }
                holder.setView(next);
                viewRenderer.renderInventory(top, holder);
                platformBridge.syncIfViewing(admin, top);
            });
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_PENDING) {
            playGuiSound(admin, GuiSoundAction.VIEW_PENDING);
            if (!Permissions.has(admin, Permissions.PENDING)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.PENDING));
                return;
            }
            pendingDeliveryService.deliverPending(admin);
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_EXPORT) {
            playGuiSound(admin, GuiSoundAction.VIEW_EXPORT);
            if (!Permissions.has(admin, Permissions.EXPORT)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.EXPORT));
                return;
            }
            shulkerExportService.exportCurrentView(admin, holder);
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_RESTORE) {
            playGuiSound(admin, GuiSoundAction.VIEW_RESTORE);
            if (!Permissions.has(admin, Permissions.RESTORE)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
                return;
            }
            Player target = Bukkit.getPlayer(holder.targetUuid());
            if (target == null) {
                Chat.error(admin, "errors.restore-target-offline");
                return;
            }
            openRestoreConfirm(admin, holder, RestoreConfirmHolder.RestoreKind.ITEMS);
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_EXPERIENCE) {
            playGuiSound(admin, GuiSoundAction.VIEW_RESTORE);
            if (!holder.parts().hasExperienceData()) {
                Chat.error(admin, "errors.backup-experience-unavailable");
                return;
            }
            if (!Permissions.has(admin, Permissions.RESTORE)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
                return;
            }
            Player target = Bukkit.getPlayer(holder.targetUuid());
            if (target == null) {
                Chat.error(admin, "errors.restore-target-offline");
                return;
            }
            openRestoreConfirm(admin, holder, RestoreConfirmHolder.RestoreKind.EXPERIENCE);
            return;
        }
        if (slot == BackupViewRenderer.SLOT_VIEW_LOCK) {
            playGuiSound(admin, GuiSoundAction.VIEW_LOCK);
            if (!Permissions.has(admin, Permissions.LOCK)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.LOCK));
                return;
            }
            toggleLock(admin, holder);
            return;
        }

        if (holder.view() == GuiView.INVENTORY) {
            handleInventorySlotClick(admin, holder, slot);
            return;
        }
        handleEnderSlotClick(admin, holder, slot);
    }

    private void handleInventorySlotClick(Player admin, BackupViewHolder holder, int slot) {
        if (slot < 0 || slot >= holder.parts().inventorySlotBytes().length) {
            return;
        }
        if (holder.claimOnce() && holder.claimedInv()[slot]) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            return;
        }
        byte[] itemBytes = holder.parts().inventorySlotBytes()[slot];
        if (itemBytes == null || itemBytes.length == 0) {
            return;
        }
        if (holder.incompatibleClaimBlocksWholeBackup()) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            Chat.error(admin, "errors.claim-incompatible-backup");
            logIncompatibleClaimBlocked(admin, holder, SlotType.INV, slot, "whole_backup_blocked");
            return;
        }
        if (holder.incompatibleInv()[slot]) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            Chat.error(admin, "errors.claim-incompatible");
            logIncompatibleClaimBlocked(admin, holder, SlotType.INV, slot, "slot_incompatible");
            return;
        }
        playGuiSound(admin, GuiSoundAction.VIEW_CLAIM_SLOT);
        if (holder.claimOnce()) {
            slotClaimService.claimSlot(admin, holder, SlotType.INV, slot, itemBytes);
            return;
        }
        slotClaimService.copySlot(admin, holder, SlotType.INV, slot, itemBytes);
    }

    private void handleEnderSlotClick(Player admin, BackupViewHolder holder, int slot) {
        if (slot < 0 || slot >= holder.parts().enderChestSlotBytes().length) {
            return;
        }
        if (holder.claimOnce() && holder.claimedEnder()[slot]) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            return;
        }
        byte[] itemBytes = holder.parts().enderChestSlotBytes()[slot];
        if (itemBytes == null || itemBytes.length == 0) {
            return;
        }
        if (holder.incompatibleClaimBlocksWholeBackup()) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            Chat.error(admin, "errors.claim-incompatible-backup");
            logIncompatibleClaimBlocked(admin, holder, SlotType.ENDER, slot, "whole_backup_blocked");
            return;
        }
        if (holder.incompatibleEnder()[slot]) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            Chat.error(admin, "errors.claim-incompatible");
            logIncompatibleClaimBlocked(admin, holder, SlotType.ENDER, slot, "slot_incompatible");
            return;
        }
        playGuiSound(admin, GuiSoundAction.VIEW_CLAIM_SLOT);
        if (holder.claimOnce()) {
            slotClaimService.claimSlot(admin, holder, SlotType.ENDER, slot, itemBytes);
            return;
        }
        slotClaimService.copySlot(admin, holder, SlotType.ENDER, slot, itemBytes);
    }

    private void toggleLock(Player admin, BackupViewHolder holder) {
        BackupStore store = resolveStoreOrError(admin, true);
        if (store == null) {
            return;
        }
        boolean nextLocked = !holder.locked();
        UUID targetUuid = holder.targetUuid();
        String targetName = holder.targetName();
        String backupId = holder.backupId();

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            boolean ok;
            try {
                ok = store.setBackupLocked(targetUuid, backupId, nextLocked);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.lock-update-failed",
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("backup_id", backupId)
                        ),
                        e
                );
                runOnPlayer(admin, () -> Chat.error(admin, "errors.read-failed"));
                return;
            }

            if (!ok) {
                runOnPlayer(admin, () -> Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                return;
            }

            plugin.auditService().log(
                    nextLocked ? "LOCK_BACKUP" : "UNLOCK_BACKUP",
                    admin,
                    targetUuid,
                    targetName,
                    backupId,
                    null
            );

            runOnPlayer(admin, () -> {
                Inventory top = holder.getInventory();
                if (top == null || !platformBridge.isViewing(admin, top)) {
                    return;
                }
                holder.setLocked(nextLocked);
                viewRenderer.renderLockItem(top, holder);
                platformBridge.syncIfViewing(admin, top);
            });
        });
    }

    private void openRestoreConfirm(Player admin, BackupViewHolder holder, RestoreConfirmHolder.RestoreKind kind) {
        runOnPlayer(admin, () -> {
            Component title = restoreConfirmRenderer.title(holder, kind);
            Inventory inventory = restoreConfirmRenderer.create(holder, kind, title);
            platformBridge.openMenu(admin, inventory, title);
        });
    }

    private void logIncompatibleClaimBlocked(
            Player admin,
            BackupViewHolder holder,
            SlotType slotType,
            int slotIndex,
            String mode
    ) {
        plugin.getLogger().warning(plugin.lang().plain(
                "console.gui.claim-incompatible",
                Placeholder.unparsed("actor", admin.getName()),
                Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                Placeholder.unparsed("backup_id", holder.backupId()),
                Placeholder.unparsed("slot", slotType + ":" + slotIndex),
                Placeholder.unparsed("mode", mode),
                Placeholder.unparsed("reason", "deserialize_failed")
        ));
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
            Chat.error(player, "errors.store-unavailable", Placeholder.unparsed("label", "pib"));
        }
        return null;
    }

    private void playGuiSound(Player player, GuiSoundAction action) {
        var config = plugin.pluginConfig();
        if (config == null || !config.guiSoundsEnabled()) {
            return;
        }
        var effect = config.guiButtonSounds().effectFor(action);
        if (effect == null || !effect.enabled()) {
            return;
        }
        runOnPlayer(player, () -> player.playSound(player.getLocation(), effect.sound(), effect.volume(), effect.pitch()));
    }

    private void playBarrierSlotSoundIfPresent(Player player, Inventory inventory, int slot) {
        if (inventory == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.BARRIER) {
            return;
        }
        playGuiSound(player, GuiSoundAction.BARRIER_SLOT);
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }
}
