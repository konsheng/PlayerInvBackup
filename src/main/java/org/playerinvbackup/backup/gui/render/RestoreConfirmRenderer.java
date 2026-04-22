package org.playerinvbackup.backup.gui.render;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;

/**
 * 恢复确认页渲染器
 */
public final class RestoreConfirmRenderer {
    public static final int GUI_SIZE = 27;
    public static final int CONFIRM_OK = 11;
    public static final int CONFIRM_INFO = 13;
    public static final int CONFIRM_CANCEL = 15;

    private final PlayerInvBackupPlugin plugin;
    private final GuiItemFactory itemFactory;

    public RestoreConfirmRenderer(PlayerInvBackupPlugin plugin, GuiItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public Component title(BackupViewHolder holder, RestoreConfirmHolder.RestoreKind kind) {
        Lang lang = plugin.lang();
        String titleName = holder.targetName() == null ? holder.targetUuid().toString() : holder.targetName();
        String titleKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                ? "gui.restore-confirm.experience.title"
                : "gui.restore-confirm.title";
        return lang.msgNoPrefix(titleKey, Placeholder.unparsed("target", titleName));
    }

    public Inventory create(BackupViewHolder sourceHolder, RestoreConfirmHolder.RestoreKind kind, Component title) {
        String titleName = sourceHolder.targetName() == null ? sourceHolder.targetUuid().toString() : sourceHolder.targetName();
        Lang lang = plugin.lang();
        RestoreConfirmHolder confirmHolder = new RestoreConfirmHolder(
                sourceHolder.targetUuid(),
                titleName,
                sourceHolder.backupId(),
                sourceHolder.listPage(),
                sourceHolder.listQuery(),
                sourceHolder.view(),
                kind,
                sourceHolder.worldName(),
                sourceHolder.locationX(),
                sourceHolder.locationY(),
                sourceHolder.locationZ(),
                sourceHolder.parts().experienceLevel(),
                sourceHolder.parts().experienceProgress(),
                sourceHolder.parts().totalExperience()
        );

        Inventory inventory = Bukkit.createInventory(confirmHolder, GUI_SIZE, title);
        confirmHolder.setInventory(inventory);

        String okNameKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                ? "gui.restore-confirm.experience.ok.name"
                : "gui.restore-confirm.ok.name";
        String okLoreKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                ? "gui.restore-confirm.experience.ok.lore"
                : "gui.restore-confirm.ok.lore";
        String infoNameKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                ? "gui.restore-confirm.experience.info.name"
                : "gui.restore-confirm.info.name";
        String infoLoreKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                ? "gui.restore-confirm.experience.info.lore"
                : "gui.restore-confirm.info.lore";

        inventory.setItem(CONFIRM_OK, itemFactory.namedItem(
                Material.GREEN_CONCRETE,
                lang.msg(okNameKey),
                lang.msgList(okLoreKey)
        ));
        inventory.setItem(CONFIRM_INFO, itemFactory.namedItem(
                Material.PAPER,
                lang.msg(infoNameKey),
                lang.msgList(
                        infoLoreKey,
                        Placeholder.unparsed("target", titleName),
                        Placeholder.unparsed("id", sourceHolder.backupId()),
                        Placeholder.unparsed("world", displayWorld(sourceHolder.worldName())),
                        Placeholder.unparsed("position", displayPosition(sourceHolder.locationX(), sourceHolder.locationY(), sourceHolder.locationZ())),
                        Placeholder.unparsed("level", String.valueOf(sourceHolder.parts().experienceLevel())),
                        Placeholder.unparsed("progress", displayExperienceProgress(sourceHolder.parts().experienceProgress())),
                        Placeholder.unparsed("total", String.valueOf(sourceHolder.parts().totalExperience()))
                )
        ));
        inventory.setItem(CONFIRM_CANCEL, itemFactory.namedItem(
                Material.RED_CONCRETE,
                lang.msg("gui.restore-confirm.cancel.name"),
                lang.msgList("gui.restore-confirm.cancel.lore")
        ));
        return inventory;
    }

    private String displayWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return plugin.lang().raw("common.none");
        }
        var config = plugin.pluginConfig();
        return config == null ? worldName : config.displayWorldName(worldName);
    }

    private String displayPosition(Double x, Double y, Double z) {
        if (x == null || y == null || z == null) {
            return plugin.lang().raw("common.none");
        }
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", x, y, z);
    }

    private String displayExperienceProgress(float progress) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", Math.max(0.0f, progress) * 100.0f);
    }
}
