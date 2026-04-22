package org.playerinvbackup.backup.gui.render;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI 物品工厂, 统一收口常用按钮和预览槽位物品构建
 */
public final class GuiItemFactory {
    private final PlayerInvBackupPlugin plugin;

    public GuiItemFactory(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack namedItem(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore == null || lore.isEmpty() ? null : lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public ItemStack loadingItem(Component label) {
        Lang lang = plugin.lang();
        Component name = label == null ? lang.msg("gui.loading.item-name") : label;
        return namedItem(Material.CLOCK, name, lang.msgList("gui.loading.item-lore"));
    }

    public ItemStack processingItem() {
        return namedItem(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                plugin.lang().msg("gui.backup-view.processing.name"),
                List.of()
        );
    }

    public ItemStack previewSlotItem(BackupViewHolder holder, SlotType slotType, int slotIndex, byte[] itemBytes) {
        Lang lang = plugin.lang();
        if (slotType == SlotType.INV && holder.claimedInv()[slotIndex]) {
            return namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-view.claimed.name"),
                    lang.msgList("gui.backup-view.claimed.lore")
            );
        }
        if (slotType == SlotType.ENDER && holder.claimedEnder()[slotIndex]) {
            return namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-view.claimed.name"),
                    lang.msgList("gui.backup-view.claimed.lore")
            );
        }
        if (itemBytes == null || itemBytes.length == 0) {
            return null;
        }
        if (holder.incompatibleClaimBlocksWholeBackup()) {
            return namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-view.incompatible-backup.name"),
                    lang.msgList("gui.backup-view.incompatible-backup.lore")
            );
        }
        boolean incompatible = slotType == SlotType.INV
                ? holder.incompatibleInv()[slotIndex]
                : holder.incompatibleEnder()[slotIndex];
        if (incompatible) {
            return namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-view.incompatible.name"),
                    lang.msgList("gui.backup-view.incompatible.lore")
            );
        }
        try {
            return ItemStack.deserializeBytes(itemBytes);
        } catch (Exception ignored) {
            return null;
        }
    }
}
