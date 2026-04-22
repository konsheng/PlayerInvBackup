package org.playerinvbackup.backup.gui.list;

import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiSoundAction;
import org.playerinvbackup.backup.config.GuiTimeFilterOption;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.session.BackupIdSearchSessionService;
import org.playerinvbackup.backup.gui.view.BackupViewActions;
import org.playerinvbackup.backup.gui.view.BackupViewController;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 列表页点击动作
 */
public final class BackupListActions {
    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final BackupListController listController;
    private final BackupViewController viewController;
    private final BackupIdSearchSessionService searchSessionService;
    private BackupViewActions viewActions;

    public BackupListActions(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            BackupListController listController,
            BackupViewController viewController,
            BackupIdSearchSessionService searchSessionService
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.listController = listController;
        this.viewController = viewController;
        this.searchSessionService = searchSessionService;
    }

    public void setViewActions(BackupViewActions viewActions) {
        this.viewActions = viewActions;
    }

    public void handleClick(Player admin, BackupListHolder holder, int slot) {
        if (slot < 0) {
            return;
        }
        BackupListHolder.Screen screen = holder.screen();
        if (screen == BackupListHolder.Screen.VIEW_LOADING || screen == BackupListHolder.Screen.LIST_LOADING) {
            return;
        }
        if (screen == BackupListHolder.Screen.VIEW) {
            BackupViewHolder viewHolder = holder.viewHolder();
            if (viewHolder != null && viewActions != null) {
                viewActions.handleClick(admin, viewHolder, slot);
            }
            return;
        }

        BackupQuery query = holder.query();

        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_PREV) {
            if (holder.page() <= 0) {
                playGuiSound(admin, GuiSoundAction.LIST_PAGE_DISABLED);
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            playGuiSound(admin, GuiSoundAction.LIST_PREV);
            listController.refreshBackupList(admin, holder, holder.page() - 1, query);
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_NEXT) {
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                playGuiSound(admin, GuiSoundAction.LIST_PAGE_DISABLED);
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            playGuiSound(admin, GuiSoundAction.LIST_NEXT);
            listController.refreshBackupList(admin, holder, holder.page() + 1, query);
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_TIME_FILTER) {
            playGuiSound(admin, GuiSoundAction.LIST_FILTER_TIME);
            listController.refreshBackupList(admin, holder, 0, nextTimeFilterQuery(query));
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_TRIGGER_FILTER) {
            playGuiSound(admin, GuiSoundAction.LIST_FILTER_TRIGGER);
            listController.refreshBackupList(admin, holder, 0, nextTriggerFilterQuery(query));
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_CLEAR_FILTERS) {
            playGuiSound(admin, GuiSoundAction.LIST_CLEAR_FILTERS);
            listController.refreshBackupList(admin, holder, 0, BackupQuery.all());
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_SEARCH) {
            playGuiSound(admin, GuiSoundAction.LIST_SEARCH);
            searchSessionService.beginSearch(admin, holder);
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_JUMP_BACK) {
            playGuiSound(admin, GuiSoundAction.LIST_JUMP_BACK);
            int nextPage = Math.max(0, holder.page() - 5);
            if (nextPage == holder.page()) {
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            listController.refreshBackupList(admin, holder, nextPage, query);
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_JUMP_FORWARD) {
            playGuiSound(admin, GuiSoundAction.LIST_JUMP_FORWARD);
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            listController.refreshBackupList(admin, holder, holder.page() + 5, query);
            return;
        }
        if (slot == org.playerinvbackup.backup.gui.render.BackupListRenderer.SLOT_LIST_REFRESH) {
            playGuiSound(admin, GuiSoundAction.LIST_REFRESH);
            listController.refreshBackupList(admin, holder, holder.page(), query);
            return;
        }
        if (slot >= holder.backups().size()) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            return;
        }

        playGuiSound(admin, GuiSoundAction.LIST_ENTRY);
        BackupMeta meta = holder.backups().get(slot);
        viewController.openBackupView(
                admin,
                holder.targetUuid(),
                holder.targetName(),
                holder.page(),
                query,
                meta.backupId(),
                GuiView.INVENTORY
        );
    }

    private BackupQuery nextTimeFilterQuery(BackupQuery current) {
        BackupQuery safe = current == null ? BackupQuery.all() : current;
        List<GuiTimeFilterOption> filters = timeFilters();
        GuiTimeFilterOption window = resolveTimeFilterWindow(safe, filters);
        int index = filters.indexOf(window);
        if (index < 0) {
            index = 0;
        }
        GuiTimeFilterOption next = filters.get((index + 1) % filters.size());
        long after = next.createdAfterMillis(System.currentTimeMillis());
        return new BackupQuery(safe.trigger(), after);
    }

    private BackupQuery nextTriggerFilterQuery(BackupQuery current) {
        BackupQuery safe = current == null ? BackupQuery.all() : current;
        TriggerType[] types = TriggerType.values();
        TriggerType currentTrigger = safe.trigger();

        TriggerType next;
        if (types.length == 0) {
            next = null;
        } else if (currentTrigger == null) {
            next = types[0];
        } else {
            int idx = -1;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == currentTrigger) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                next = types[0];
            } else if (idx >= types.length - 1) {
                next = null;
            } else {
                next = types[idx + 1];
            }
        }
        return new BackupQuery(next, safe.createdAfterMillis());
    }

    private GuiTimeFilterOption resolveTimeFilterWindow(BackupQuery query, List<GuiTimeFilterOption> filters) {
        List<GuiTimeFilterOption> safeFilters = filters == null || filters.isEmpty()
                ? GuiTimeFilterOption.defaults()
                : filters;
        GuiTimeFilterOption allOption = safeFilters.get(0);
        if (query == null || query.createdAfterMillis() <= 0) {
            return allOption;
        }

        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - query.createdAfterMillis());
        GuiTimeFilterOption best = null;
        long bestDelta = Long.MAX_VALUE;
        for (GuiTimeFilterOption window : safeFilters) {
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
