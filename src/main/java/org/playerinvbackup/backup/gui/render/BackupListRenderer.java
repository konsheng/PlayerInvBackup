package org.playerinvbackup.backup.gui.render;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiTimeFilterOption;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.text.Lang;
import org.playerinvbackup.backup.util.BackupLocationFormatter;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;

/**
 * 列表页渲染器, 只负责把 holder 状态画成 inventory
 */
public final class BackupListRenderer {
    public static final int SLOT_LIST_PREV = 45;
    public static final int SLOT_LIST_TIME_FILTER = 46;
    public static final int SLOT_LIST_TRIGGER_FILTER = 47;
    public static final int SLOT_LIST_SEARCH = 48;
    public static final int SLOT_LIST_CLEAR_FILTERS = 49;
    public static final int SLOT_LIST_JUMP_BACK = 50;
    public static final int SLOT_LIST_JUMP_FORWARD = 51;
    public static final int SLOT_LIST_REFRESH = 52;
    public static final int SLOT_LIST_NEXT = 53;

    private static final DateTimeFormatter DEFAULT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PlayerInvBackupPlugin plugin;
    private final GuiItemFactory itemFactory;

    public BackupListRenderer(PlayerInvBackupPlugin plugin, GuiItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
    }

    public Component title(String targetName, int page, int totalPages) {
        Lang lang = plugin.lang();
        String safeTarget = targetName == null ? "-" : targetName;
        int safeCurrentPage = Math.max(0, page) + 1;
        int safeTotalPages = Math.max(1, totalPages);
        return lang.msgNoPrefix(
                "gui.backup-list.title",
                Placeholder.unparsed("target", safeTarget),
                Placeholder.unparsed("page", String.valueOf(safeCurrentPage)),
                Placeholder.unparsed("current_page", String.valueOf(safeCurrentPage)),
                Placeholder.unparsed("total_pages", String.valueOf(safeTotalPages))
        );
    }

    public void render(Inventory inventory, BackupListHolder holder, boolean hasNextPage) {
        if (inventory == null || holder == null) {
            return;
        }

        Lang lang = plugin.lang();
        List<BackupMeta> backups = holder.backups();
        BackupQuery safeQuery = holder.query() == null ? BackupQuery.all() : holder.query();

        for (int i = 0; i < 45; i++) {
            inventory.setItem(i, null);
        }

        if (backups.isEmpty()) {
            inventory.setItem(22, itemFactory.namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-list.empty.name"),
                    lang.msgList("gui.backup-list.empty.lore")
            ));
        }

        for (int i = 0; i < backups.size() && i < 45; i++) {
            BackupMeta meta = backups.get(i);
            String time = timeFormatter().format(Instant.ofEpochMilli(meta.createdAtMillis()));
            Material icon = meta.locked() ? Material.ENCHANTED_BOOK : Material.PAPER;
            String lockedText = lang.raw(meta.locked() ? "common.yes_text" : "common.no_text");
            String noteText = meta.note() == null || meta.note().isBlank()
                    ? lang.raw("common.none")
                    : meta.note();
            String serverText = displayServerName(meta.serverId());
            List<Component> lore = new ArrayList<>(lang.msgList(
                    "gui.backup-list.entry.lore",
                    Placeholder.unparsed("id", meta.backupId()),
                    Placeholder.unparsed("trigger", lang.raw(meta.trigger().langKey())),
                    Placeholder.unparsed("server", serverText),
                    Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                    Placeholder.unparsed("world", BackupLocationFormatter.displayWorld(plugin, meta.worldName(), meta.targetWorldName())),
                    Placeholder.unparsed("position", BackupLocationFormatter.displayPosition(
                            plugin,
                            meta.locationX(),
                            meta.locationY(),
                            meta.locationZ(),
                            meta.targetLocationX(),
                            meta.targetLocationY(),
                            meta.targetLocationZ()
                    )),
                    Placeholder.unparsed("locked", lockedText),
                    Placeholder.unparsed("note", noteText)
            ));
            if (meta.hasPlayerKiller()) {
                lore.add(Math.min(2, lore.size()), lang.msg(
                        "gui.backup-list.entry.killer-line",
                        Placeholder.unparsed("killer", meta.killerPlayerName())
                ));
            }
            inventory.setItem(i, itemFactory.namedItem(
                    icon,
                    lang.msg("gui.backup-list.entry.name", Placeholder.unparsed("time", time)),
                    lore
            ));
        }

        String timeFilterValue = timeFilterDisplayValue(lang, safeQuery);
        String triggerFilterValue = safeQuery.trigger() == null
                ? lang.raw("gui.backup-list.filter-trigger.value.all")
                : lang.raw(safeQuery.trigger().langKey());

        inventory.setItem(SLOT_LIST_PREV, holder.page() > 0
                ? itemFactory.namedItem(Material.ARROW, lang.msg("gui.backup-list.prev.name"), List.of())
                : itemFactory.namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.prev-disabled.name"),
                        lang.msgList("gui.backup-list.prev-disabled.lore")
                ));
        inventory.setItem(SLOT_LIST_TIME_FILTER, itemFactory.namedItem(
                Material.CLOCK,
                lang.msg("gui.backup-list.filter-time.name"),
                lang.msgList("gui.backup-list.filter-time.lore", Placeholder.unparsed("value", timeFilterValue))
        ));
        inventory.setItem(SLOT_LIST_TRIGGER_FILTER, itemFactory.namedItem(
                Material.COMPARATOR,
                lang.msg("gui.backup-list.filter-trigger.name"),
                lang.msgList("gui.backup-list.filter-trigger.lore", Placeholder.unparsed("value", triggerFilterValue))
        ));
        inventory.setItem(SLOT_LIST_SEARCH, itemFactory.namedItem(
                Material.SPYGLASS,
                lang.msg("gui.backup-list.search.name"),
                lang.msgList("gui.backup-list.search.lore", Placeholder.unparsed("cancel", cancelKeywordDisplay()))
        ));
        inventory.setItem(SLOT_LIST_CLEAR_FILTERS, itemFactory.namedItem(
                Material.MILK_BUCKET,
                lang.msg("gui.backup-list.clear.name"),
                lang.msgList("gui.backup-list.clear.lore")
        ));
        inventory.setItem(SLOT_LIST_JUMP_BACK, itemFactory.namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-back.name"),
                lang.msgList("gui.backup-list.jump-back.lore")
        ));
        inventory.setItem(SLOT_LIST_JUMP_FORWARD, itemFactory.namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-forward.name"),
                lang.msgList("gui.backup-list.jump-forward.lore")
        ));
        inventory.setItem(SLOT_LIST_REFRESH, itemFactory.namedItem(
                Material.SUNFLOWER,
                lang.msg("gui.backup-list.refresh.name"),
                lang.msgList("gui.backup-list.refresh.lore", Placeholder.unparsed("page", String.valueOf(holder.page() + 1)))
        ));
        inventory.setItem(SLOT_LIST_NEXT, hasNextPage
                ? itemFactory.namedItem(Material.ARROW, lang.msg("gui.backup-list.next.name"), List.of())
                : itemFactory.namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.next-disabled.name"),
                        lang.msgList("gui.backup-list.next-disabled.lore")
                ));
    }

    private DateTimeFormatter timeFormatter() {
        var config = plugin.pluginConfig();
        return config == null ? DEFAULT_TIME_FORMAT : config.backupTimeFormatter();
    }

    private String timeFilterDisplayValue(Lang lang, BackupQuery query) {
        if (query != null && query.createdBeforeMillis() > 0) {
            return timeFormatter().format(Instant.ofEpochMilli(query.createdAfterMillis()))
                    + " ~ "
                    + timeFormatter().format(Instant.ofEpochMilli(query.createdBeforeMillis()));
        }
        GuiTimeFilterOption window = resolveTimeFilterWindow(query);
        return window.displayText(lang);
    }

    private GuiTimeFilterOption resolveTimeFilterWindow(BackupQuery query) {
        List<GuiTimeFilterOption> filters = timeFilters();
        GuiTimeFilterOption allOption = filters.get(0);
        if (query == null || query.createdBeforeMillis() > 0 || query.createdAfterMillis() <= 0) {
            return allOption;
        }

        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - query.createdAfterMillis());
        GuiTimeFilterOption best = null;
        long bestDelta = Long.MAX_VALUE;

        for (GuiTimeFilterOption window : filters) {
            if (window.all()) {
                continue;
            }
            long delta = Math.abs(diff - window.duration().toMillis());
            if (best == null || delta < bestDelta) {
                best = window;
                bestDelta = delta;
            }
        }
        return best == null ? allOption : best;
    }

    private List<GuiTimeFilterOption> timeFilters() {
        var config = plugin.pluginConfig();
        if (config == null || config.guiTimeFilters().isEmpty()) {
            return GuiTimeFilterOption.defaults();
        }
        return config.guiTimeFilters();
    }

    private String cancelKeywordDisplay() {
        Lang lang = plugin.lang();
        for (String keyword : lang.rawList("common.cancel_keywords")) {
            if (keyword != null && !keyword.isBlank()) {
                return keyword.trim();
            }
        }
        return "cancel";
    }

    private String displayServerName(String serverId) {
        var config = plugin.pluginConfig();
        if (config == null) {
            return serverId == null || serverId.isBlank() ? "default" : serverId;
        }
        return config.displayServerName(serverId);
    }
}
