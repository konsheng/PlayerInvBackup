package org.playerinvbackup.backup.gui.action;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.text.Chat;

/**
 * 将当前预览视图导出为潜影盒物品
 */
public final class ShulkerExportService {
    private static final int SHULKER_SIZE = 27;

    private final PlayerInvBackupPlugin plugin;

    public ShulkerExportService(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    public void exportCurrentView(Player admin, BackupViewHolder holder) {
        if (admin == null || holder == null || !admin.isOnline()) {
            return;
        }

        ExportBuildResult result = holder.view() == GuiView.ENDER_CHEST
                ? buildEnderExport(holder)
                : buildInventoryExport(holder);

        if (result.failure() != null) {
            handleBuildFailure(admin, holder, result);
            return;
        }

        if (result.boxes().isEmpty()) {
            Chat.warn(admin, "errors.export-empty");
            audit(admin, holder, result, false, "empty");
            return;
        }

        if (!hasEnoughStorageSlots(admin.getInventory(), result.boxes().size())) {
            Chat.warn(
                    admin,
                    "errors.export-inventory-full",
                    Placeholder.unparsed("boxes", String.valueOf(result.boxes().size()))
            );
            audit(admin, holder, result, false, "inventory_full");
            return;
        }

        insertBoxes(admin.getInventory(), result.boxes());
        admin.updateInventory();
        Chat.success(
                admin,
                "success.export-shulker",
                Placeholder.unparsed("boxes", String.valueOf(result.boxes().size())),
                Placeholder.unparsed("items", String.valueOf(result.itemCount()))
        );
        audit(admin, holder, result, true, "ok");
    }

    private ExportBuildResult buildInventoryExport(BackupViewHolder holder) {
        ExportAccumulator accumulator = new ExportAccumulator();
        ItemStack[] first = new ItemStack[SHULKER_SIZE];
        ItemStack[] second = new ItemStack[SHULKER_SIZE];

        for (int sourceSlot = 0; sourceSlot <= 26; sourceSlot++) {
            copySlot(holder, SlotType.INV, sourceSlot, first, sourceSlot, accumulator);
            if (accumulator.failed()) {
                return accumulator.toFailure();
            }
        }

        for (int sourceSlot = 27; sourceSlot <= 40; sourceSlot++) {
            int targetSlot = sourceSlot - 27;
            copySlot(holder, SlotType.INV, sourceSlot, second, targetSlot, accumulator);
            if (accumulator.failed()) {
                return accumulator.toFailure();
            }
        }

        List<ItemStack> boxes = new ArrayList<>(2);
        Material material = shulkerMaterial();
        if (hasAnyItem(first)) {
            ItemStack box = createShulkerBox(
                    material,
                    inventoryBoxName(holder, 1, 2),
                    first
            );
            if (box == null) {
                return accumulator.failure(BuildFailure.BOX_CREATE_FAILED);
            }
            boxes.add(box);
        }
        if (hasAnyItem(second)) {
            ItemStack box = createShulkerBox(
                    material,
                    inventoryBoxName(holder, 2, 2),
                    second
            );
            if (box == null) {
                return accumulator.failure(BuildFailure.BOX_CREATE_FAILED);
            }
            boxes.add(box);
        }

        return accumulator.success(boxes);
    }

    private ExportBuildResult buildEnderExport(BackupViewHolder holder) {
        ExportAccumulator accumulator = new ExportAccumulator();
        ItemStack[] contents = new ItemStack[SHULKER_SIZE];

        for (int sourceSlot = 0; sourceSlot < SHULKER_SIZE; sourceSlot++) {
            copySlot(holder, SlotType.ENDER, sourceSlot, contents, sourceSlot, accumulator);
            if (accumulator.failed()) {
                return accumulator.toFailure();
            }
        }

        List<ItemStack> boxes = new ArrayList<>(1);
        if (hasAnyItem(contents)) {
            ItemStack box = createShulkerBox(
                    shulkerMaterial(),
                    enderBoxName(holder),
                    contents
            );
            if (box == null) {
                return accumulator.failure(BuildFailure.BOX_CREATE_FAILED);
            }
            boxes.add(box);
        }

        return accumulator.success(boxes);
    }

    private void copySlot(
            BackupViewHolder holder,
            SlotType slotType,
            int sourceSlot,
            ItemStack[] targetContents,
            int targetSlot,
            ExportAccumulator accumulator
    ) {
        byte[] itemBytes = itemBytes(holder, slotType, sourceSlot);
        if (itemBytes == null || itemBytes.length == 0) {
            return;
        }
        if (isClaimed(holder, slotType, sourceSlot)) {
            accumulator.skippedClaimed++;
            return;
        }
        if (isIncompatible(holder, slotType, sourceSlot)) {
            accumulator.failure = BuildFailure.INCOMPATIBLE_ITEM;
            return;
        }

        try {
            targetContents[targetSlot] = ItemStack.deserializeBytes(itemBytes);
            accumulator.itemCount++;
        } catch (RuntimeException e) {
            accumulator.failure = BuildFailure.INCOMPATIBLE_ITEM;
        }
    }

    private byte[] itemBytes(BackupViewHolder holder, SlotType slotType, int sourceSlot) {
        if (slotType == SlotType.INV) {
            if (sourceSlot < 0 || sourceSlot >= holder.parts().inventorySlotBytes().length) {
                return null;
            }
            return holder.parts().inventorySlotBytes()[sourceSlot];
        }
        if (sourceSlot < 0 || sourceSlot >= holder.parts().enderChestSlotBytes().length) {
            return null;
        }
        return holder.parts().enderChestSlotBytes()[sourceSlot];
    }

    private boolean isClaimed(BackupViewHolder holder, SlotType slotType, int slot) {
        if (slotType == SlotType.INV) {
            return slot >= 0 && slot < holder.claimRecordInv().length && holder.claimRecordInv()[slot];
        }
        return slot >= 0 && slot < holder.claimRecordEnder().length && holder.claimRecordEnder()[slot];
    }

    private boolean isIncompatible(BackupViewHolder holder, SlotType slotType, int slot) {
        if (slotType == SlotType.INV) {
            return slot >= 0 && slot < holder.incompatibleInv().length && holder.incompatibleInv()[slot];
        }
        return slot >= 0 && slot < holder.incompatibleEnder().length && holder.incompatibleEnder()[slot];
    }

    private ItemStack createShulkerBox(Material material, Component name, ItemStack[] contents) {
        ItemStack box = new ItemStack(material);
        if (!(box.getItemMeta() instanceof BlockStateMeta meta)) {
            return null;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return null;
        }

        shulkerBox.getInventory().setContents(contents);
        meta.setBlockState(shulkerBox);
        meta.displayName(name);
        box.setItemMeta(meta);
        return box;
    }

    private Material shulkerMaterial() {
        var config = plugin.pluginConfig();
        if (config == null || config.guiExport() == null || config.guiExport().shulkerBoxMaterial() == null) {
            return Material.SHULKER_BOX;
        }
        return config.guiExport().shulkerBoxMaterial();
    }

    private Component inventoryBoxName(BackupViewHolder holder, int part, int total) {
        return plugin.lang().msgNoPrefix(
                "gui.backup-view.export.inventory-box-name",
                Placeholder.unparsed("target", safeTargetName(holder)),
                Placeholder.unparsed("part", String.valueOf(part)),
                Placeholder.unparsed("total", String.valueOf(total))
        );
    }

    private Component enderBoxName(BackupViewHolder holder) {
        return plugin.lang().msgNoPrefix(
                "gui.backup-view.export.ender-box-name",
                Placeholder.unparsed("target", safeTargetName(holder))
        );
    }

    private String safeTargetName(BackupViewHolder holder) {
        if (holder.targetName() != null && !holder.targetName().isBlank()) {
            return holder.targetName();
        }
        return holder.targetUuid() == null ? "-" : holder.targetUuid().toString();
    }

    private static boolean hasAnyItem(ItemStack[] contents) {
        if (contents == null) {
            return false;
        }
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnoughStorageSlots(PlayerInventory inventory, int neededSlots) {
        if (inventory == null || neededSlots <= 0) {
            return neededSlots <= 0;
        }
        int empty = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                empty++;
                if (empty >= neededSlots) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void insertBoxes(PlayerInventory inventory, List<ItemStack> boxes) {
        ItemStack[] contents = cloneStorage(inventory.getStorageContents());
        int boxIndex = 0;
        for (int i = 0; i < contents.length && boxIndex < boxes.size(); i++) {
            ItemStack existing = contents[i];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            contents[i] = boxes.get(boxIndex++);
        }
        inventory.setStorageContents(contents);
    }

    private static ItemStack[] cloneStorage(ItemStack[] storageContents) {
        ItemStack[] copy = new ItemStack[storageContents.length];
        for (int i = 0; i < storageContents.length; i++) {
            ItemStack item = storageContents[i];
            copy[i] = item == null || item.getType().isAir() ? null : item.clone();
        }
        return copy;
    }

    private void handleBuildFailure(Player admin, BackupViewHolder holder, ExportBuildResult result) {
        if (result.failure() == BuildFailure.INCOMPATIBLE_ITEM) {
            Chat.error(admin, "errors.export-incompatible");
            audit(admin, holder, result, false, "incompatible_item");
            return;
        }

        plugin.getLogger().warning(plugin.lang().plain(
                "console.gui.export-failed",
                Placeholder.unparsed("actor", admin.getName()),
                Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                Placeholder.unparsed("backup_id", holder.backupId()),
                Placeholder.unparsed("reason", "box_create_failed")
        ));
        Chat.error(admin, "errors.export-failed");
        audit(admin, holder, result, false, "box_create_failed");
    }

    private void audit(Player admin, BackupViewHolder holder, ExportBuildResult result, boolean success, String reason) {
        plugin.auditService().log(
                "EXPORT_SHULKER",
                admin,
                holder.targetUuid(),
                holder.targetName(),
                holder.backupId(),
                "view=" + holder.view().name()
                        + " boxes=" + result.boxes().size()
                        + " items=" + result.itemCount()
                        + " skippedClaimed=" + result.skippedClaimed()
                        + " success=" + success
                        + " reason=" + reason
        );
    }

    private enum BuildFailure {
        INCOMPATIBLE_ITEM,
        BOX_CREATE_FAILED
    }

    private record ExportBuildResult(
            List<ItemStack> boxes,
            int itemCount,
            int skippedClaimed,
            BuildFailure failure
    ) {
    }

    private static final class ExportAccumulator {
        private int itemCount;
        private int skippedClaimed;
        private BuildFailure failure;

        private boolean failed() {
            return failure != null;
        }

        private ExportBuildResult success(List<ItemStack> boxes) {
            return new ExportBuildResult(List.copyOf(boxes), itemCount, skippedClaimed, null);
        }

        private ExportBuildResult failure(BuildFailure failure) {
            this.failure = failure;
            return toFailure();
        }

        private ExportBuildResult toFailure() {
            return new ExportBuildResult(List.of(), itemCount, skippedClaimed, failure);
        }
    }
}
