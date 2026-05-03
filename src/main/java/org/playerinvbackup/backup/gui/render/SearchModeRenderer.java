package org.playerinvbackup.backup.gui.render;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.holder.SearchModeHolder;
import org.playerinvbackup.backup.text.Lang;

/**
 * 搜索方式选择界面渲染器
 */
public final class SearchModeRenderer {
    public static final int SLOT_SEARCH_BY_ID = 11;
    public static final int SLOT_SEARCH_BY_TIME = 15;
    public static final int SLOT_BACK = 22;

    private static final int SIZE = 27;

    private final PlayerInvBackupPlugin plugin;
    private final GuiItemFactory itemFactory;

    public SearchModeRenderer(PlayerInvBackupPlugin plugin, GuiItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public Component title() {
        return plugin.lang().msgNoPrefix("gui.search-mode.title");
    }

    public Inventory create(SearchModeHolder holder, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inventory);
        render(inventory);
        return inventory;
    }

    public void render(Inventory inventory) {
        if (inventory == null) {
            return;
        }

        inventory.clear();
        Lang lang = plugin.lang();
        inventory.setItem(SLOT_SEARCH_BY_ID, itemFactory.namedItem(
                Material.SPYGLASS,
                lang.msg("gui.search-mode.by-id.name"),
                lang.msgList("gui.search-mode.by-id.lore")
        ));
        inventory.setItem(SLOT_SEARCH_BY_TIME, itemFactory.namedItem(
                Material.CLOCK,
                lang.msg("gui.search-mode.by-time.name"),
                lang.msgList("gui.search-mode.by-time.lore")
        ));
        inventory.setItem(SLOT_BACK, itemFactory.namedItem(
                Material.OAK_DOOR,
                lang.msg("gui.search-mode.back.name"),
                List.of()
        ));
    }
}
