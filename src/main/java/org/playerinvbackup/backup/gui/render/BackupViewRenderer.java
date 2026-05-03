package org.playerinvbackup.backup.gui.render;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.text.Lang;
import org.playerinvbackup.backup.util.BackupLocationFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;

/**
 * 详情页渲染器
 */
public final class BackupViewRenderer {
    public static final int SLOT_VIEW_BACK = 45;
    public static final int SLOT_VIEW_TOGGLE = 46;
    public static final int SLOT_VIEW_RESTORE = 47;
    public static final int SLOT_VIEW_EXPERIENCE = 48;
    public static final int SLOT_VIEW_LOCK = 51;
    public static final int SLOT_VIEW_EXPORT = 52;
    public static final int SLOT_VIEW_PENDING = 53;

    private final PlayerInvBackupPlugin plugin;
    private final GuiItemFactory itemFactory;

    public BackupViewRenderer(PlayerInvBackupPlugin plugin, GuiItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public Component title(String targetName) {
        String safeTarget = targetName == null ? "-" : targetName;
        return plugin.lang().msgNoPrefix("gui.backup-view.title", Placeholder.unparsed("target", safeTarget));
    }

    public void renderScreen(Inventory inventory, BackupViewHolder holder) {
        if (inventory == null || holder == null) {
            return;
        }

        int size = inventory.getSize();
        for (int i = 45; i < size && i < 54; i++) {
            inventory.setItem(i, null);
        }

        renderInventory(inventory, holder);

        Lang lang = plugin.lang();
        inventory.setItem(SLOT_VIEW_BACK, itemFactory.namedItem(Material.OAK_DOOR, lang.msg("gui.backup-view.back.name"), List.of()));
        inventory.setItem(SLOT_VIEW_TOGGLE, itemFactory.namedItem(
                Material.ENDER_CHEST,
                lang.msg("gui.backup-view.toggle.name"),
                lang.msgList("gui.backup-view.toggle.lore")
        ));
        boolean online = Bukkit.getPlayer(holder.targetUuid()) != null;
        inventory.setItem(SLOT_VIEW_RESTORE, online
                ? itemFactory.namedItem(Material.REDSTONE_BLOCK, lang.msg("gui.backup-view.restore.name"), lang.msgList("gui.backup-view.restore.lore"))
                : itemFactory.namedItem(Material.BARRIER, lang.msg("gui.backup-view.restore-offline.name"), lang.msgList("gui.backup-view.restore-offline.lore")));
        renderExperienceItem(inventory, holder);
        inventory.setItem(SLOT_VIEW_PENDING, itemFactory.namedItem(
                Material.CHEST,
                lang.msg("gui.backup-view.pending.name"),
                lang.msgList("gui.backup-view.pending.lore")
        ));
        inventory.setItem(SLOT_VIEW_EXPORT, itemFactory.namedItem(
                exportIconMaterial(),
                lang.msg("gui.backup-view.export.name"),
                lang.msgList("gui.backup-view.export.lore")
        ));
        renderLockItem(inventory, holder);
    }

    public void renderLockItem(Inventory inventory, BackupViewHolder holder) {
        if (inventory == null || holder == null) {
            return;
        }
        Lang lang = plugin.lang();
        String lockedText = lang.raw(holder.locked() ? "common.yes_text" : "common.no_text");
        String noteText = holder.note() == null || holder.note().isBlank()
                ? lang.raw("common.none")
                : holder.note();
        String serverText = displayServerName(holder.serverId());
        List<Component> lore = new ArrayList<>(lang.msgList(
                "gui.backup-view.lock.lore",
                Placeholder.unparsed("locked", lockedText),
                Placeholder.unparsed("note", noteText),
                Placeholder.unparsed("server", serverText),
                Placeholder.unparsed("world", BackupLocationFormatter.displayWorld(
                        plugin,
                        holder.worldName(),
                        holder.targetWorldName()
                )),
                Placeholder.unparsed("position", BackupLocationFormatter.displayPosition(
                        plugin,
                        holder.locationX(),
                        holder.locationY(),
                        holder.locationZ(),
                        holder.targetLocationX(),
                        holder.targetLocationY(),
                        holder.targetLocationZ()
                ))
        ));
        if (holder.killerPlayerName() != null && !holder.killerPlayerName().isBlank()) {
            lore.add(Math.min(4, lore.size()), lang.msg(
                    "gui.backup-view.lock.killer-line",
                    Placeholder.unparsed("killer", holder.killerPlayerName())
            ));
        }
        inventory.setItem(
                SLOT_VIEW_LOCK,
                itemFactory.namedItem(
                        Material.TRIPWIRE_HOOK,
                        lang.msg("gui.backup-view.lock.name"),
                        lore
                )
        );
    }

    public void renderInventory(Inventory inventory, BackupViewHolder holder) {
        if (inventory == null || holder == null) {
            return;
        }

        int clearEndExclusive = Math.min(45, inventory.getSize());
        for (int i = 0; i < clearEndExclusive; i++) {
            inventory.setItem(i, null);
        }

        if (holder.view() == GuiView.INVENTORY) {
            for (int i = 0; i < SnapshotCodec.INVENTORY_SLOT_COUNT && i < inventory.getSize(); i++) {
                byte[] itemBytes = holder.parts().inventorySlotBytes()[i];
                inventory.setItem(i, itemFactory.previewSlotItem(holder, org.playerinvbackup.backup.domain.SlotType.INV, i, itemBytes));
            }
            return;
        }

        for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT && i < inventory.getSize(); i++) {
            byte[] itemBytes = holder.parts().enderChestSlotBytes()[i];
            inventory.setItem(i, itemFactory.previewSlotItem(holder, org.playerinvbackup.backup.domain.SlotType.ENDER, i, itemBytes));
        }
    }

    public void renderExperienceItem(Inventory inventory, BackupViewHolder holder) {
        if (inventory == null || holder == null) {
            return;
        }

        Lang lang = plugin.lang();
        if (!holder.parts().hasExperienceData()) {
            inventory.setItem(
                    SLOT_VIEW_EXPERIENCE,
                    itemFactory.namedItem(
                            Material.GLASS_BOTTLE,
                            lang.msg("gui.backup-view.experience-unavailable.name"),
                            lang.msgList("gui.backup-view.experience-unavailable.lore")
                    )
            );
            return;
        }

        inventory.setItem(
                SLOT_VIEW_EXPERIENCE,
                itemFactory.namedItem(
                        Material.EXPERIENCE_BOTTLE,
                        lang.msg("gui.backup-view.experience.name"),
                        lang.msgList(
                                "gui.backup-view.experience.lore",
                                Placeholder.unparsed("level", String.valueOf(holder.parts().experienceLevel())),
                                Placeholder.unparsed("progress", displayExperienceProgress(holder.parts().experienceProgress())),
                                Placeholder.unparsed("total", String.valueOf(holder.parts().totalExperience()))
                        )
                )
        );
    }

    private String displayExperienceProgress(float progress) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", Math.max(0.0f, progress) * 100.0f);
    }

    private String displayServerName(String serverId) {
        var config = plugin.pluginConfig();
        if (config == null) {
            return serverId == null || serverId.isBlank() ? "default" : serverId;
        }
        return config.displayServerName(serverId);
    }

    private Material exportIconMaterial() {
        var config = plugin.pluginConfig();
        if (config == null || config.guiExport() == null || config.guiExport().shulkerBoxMaterial() == null) {
            return Material.SHULKER_BOX;
        }
        return config.guiExport().shulkerBoxMaterial();
    }
}
