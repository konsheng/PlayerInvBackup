package org.playerinvbackup.backup.gui.list;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.render.BackupListRenderer;
import org.playerinvbackup.backup.gui.render.LoadingScreenRenderer;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 备份列表控制器, 负责列表页数据加载和 loading -> loaded 状态切换
 */
public final class BackupListController {
    private static final int GUI_SIZE = 54;
    private static final String MAIN_LABEL = "pib";
    private static final long DEFAULT_LOADING_INDICATOR_DELAY_SECONDS = 3L;

    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final LoadingScreenRenderer loadingRenderer;
    private final BackupListRenderer listRenderer;

    public BackupListController(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            LoadingScreenRenderer loadingRenderer,
            BackupListRenderer listRenderer
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.loadingRenderer = loadingRenderer;
        this.listRenderer = listRenderer;
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page) {
        openBackupList(admin, targetUuid, targetName, page, BackupQuery.all(), BackupGuiMode.MANAGE);
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page, BackupQuery query) {
        openBackupList(admin, targetUuid, targetName, page, query, BackupGuiMode.MANAGE);
    }

    public void openBackupList(
            Player admin,
            UUID targetUuid,
            String targetName,
            int page,
            BackupQuery query,
            BackupGuiMode guiMode
    ) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        int safePage = Math.max(0, page);
        BackupQuery safeQuery = query == null ? BackupQuery.all() : query;
        BackupGuiMode safeMode = guiMode == null ? BackupGuiMode.MANAGE : guiMode;
        Component loadingLabel = plugin.lang().msgNoPrefix("gui.backup-list.loading-title");

        plugin.auditService().log(
                safeMode.viewOnly() ? "OPEN_VIEW_LIST" : "OPEN_LIST",
                admin,
                targetUuid,
                targetName,
                null,
                "mode=" + safeMode.name()
                        + " scope=" + scope(admin, targetUuid)
                        + " page=" + safePage
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
                        + " before=" + safeQuery.createdBeforeMillis()
        );

        runOnPlayer(admin, () -> {
            BackupListHolder existing = findOpenBackupListHolder(admin, targetUuid);
            if (existing != null) {
                existing.nextViewRefreshSeq();
                existing.setViewHolder(null);
                existing.setGuiMode(safeMode);

                boolean same = safePage == existing.page() && safeQuery.equals(existing.query());
                int limit = plugin.pluginConfig().guiListPageSize();
                if (same && existing.isListLoaded()) {
                    existing.setScreen(BackupListHolder.Screen.LIST);
                    platformBridge.retitleIfViewing(
                            admin,
                            existing.getInventory(),
                            listRenderer.title(existing.targetName(), safePage, existing.totalPages())
                    );
                    listRenderer.render(existing.getInventory(), existing, existing.backups().size() >= limit);
                    platformBridge.syncIfViewing(admin, existing.getInventory());
                    return;
                }

                existing.setScreen(BackupListHolder.Screen.LIST_LOADING);
                existing.setListLoaded(false);
                refreshBackupList(admin, existing, safePage, safeQuery, false, true, loadingLabel);
                return;
            }

            String name = targetName == null ? String.valueOf(targetUuid) : targetName;
            Component title = listRenderer.title(name, safePage, 1);
            BackupListHolder holder = new BackupListHolder(targetUuid, name, safePage, safeQuery, List.of(), safeMode);
            holder.setScreen(BackupListHolder.Screen.LIST_LOADING);
            Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
            holder.setInventory(inventory);

            platformBridge.openMenu(admin, inventory, title);
            refreshBackupList(admin, holder, safePage, safeQuery, false, true, loadingLabel);
        });
    }

    public void refreshBackupList(Player admin, BackupListHolder holder, int page, BackupQuery query) {
        refreshBackupList(admin, holder, page, query, false, false, null);
    }

    public BackupListHolder findOpenBackupListHolder(Player player, UUID targetUuid) {
        if (player == null || targetUuid == null || !player.isOnline()) {
            return null;
        }

        Inventory top = platformBridge.currentTop(player);
        if (top == null) {
            return null;
        }
        Object holder = top.getHolder();
        if (!(holder instanceof BackupListHolder listHolder)) {
            return null;
        }
        if (listHolder.targetUuid() == null || !listHolder.targetUuid().equals(targetUuid)) {
            return null;
        }
        if (listHolder.getInventory() == null) {
            listHolder.setInventory(top);
        }
        return listHolder;
    }

    private void refreshBackupList(
            Player admin,
            BackupListHolder holder,
            int page,
            BackupQuery query,
            boolean fallbackWarned,
            boolean scheduleDelayedLoading,
            Component loadingLabel
    ) {
        if (admin == null || holder == null) {
            return;
        }
        BackupStore store = resolveStoreOrError(admin, true);
        if (store == null) {
            return;
        }

        int limit = plugin.pluginConfig().guiListPageSize();
        int safePage = Math.max(0, page);
        BackupQuery safeQuery = query == null ? BackupQuery.all() : query;

        long refreshSeq = holder.nextRefreshSeq();
        if (scheduleDelayedLoading) {
            // 先进入 LIST_LOADING 状态阻止重复点击, 但暂时保留旧界面
            // 只有真实时间超过 3 秒仍未完成, 才显示 loading 画面
            scheduleDelayedListLoading(admin, holder, refreshSeq, loadingLabel);
        }
        int offset = safePage * limit;
        UUID targetUuid = holder.targetUuid();
        String targetName = holder.targetName();

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<BackupMeta> backups;
            int totalCount;
            try {
                totalCount = store.countBackups(targetUuid, safeQuery);
                backups = store.listBackups(targetUuid, safeQuery, offset, limit);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.list-load-failed-query",
                                Placeholder.unparsed("actor", admin.getName()),
                                Placeholder.unparsed("actor_uuid", admin.getUniqueId().toString()),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("page", String.valueOf(safePage)),
                                Placeholder.unparsed("query", String.valueOf(safeQuery))
                        ),
                        e
                );
                runOnPlayer(admin, () -> Chat.error(admin, "errors.load-failed"));
                return;
            }

            if (backups.isEmpty() && safePage > 0) {
                runOnPlayer(admin, () -> {
                    if (!holder.isRefreshSeqCurrent(refreshSeq)) {
                        return;
                    }
                    BackupListHolder.Screen screen = holder.screen();
                    if (screen != BackupListHolder.Screen.LIST && screen != BackupListHolder.Screen.LIST_LOADING) {
                        return;
                    }
                    if (!fallbackWarned) {
                        Chat.warn(admin, "warn.no-more-backups-back");
                    }
                    refreshBackupList(admin, holder, safePage - 1, safeQuery, true, false, null);
                });
                return;
            }

            boolean hasNextPage = backups.size() >= limit;
            int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / limit));
            runOnPlayer(admin, () -> {
                Inventory top = holder.getInventory();
                if (top == null) {
                    return;
                }
                if (!holder.isRefreshSeqCurrent(refreshSeq)) {
                    return;
                }
                BackupListHolder.Screen screen = holder.screen();
                if (screen != BackupListHolder.Screen.LIST && screen != BackupListHolder.Screen.LIST_LOADING) {
                    return;
                }

                holder.setPage(safePage);
                holder.setTotalPages(totalPages);
                holder.setQuery(safeQuery);
                holder.setBackups(backups);
                holder.setListLoaded(true);
                holder.setScreen(BackupListHolder.Screen.LIST);

                platformBridge.retitleIfViewing(admin, top, listRenderer.title(targetName, safePage, totalPages));
                listRenderer.render(top, holder, hasNextPage);
                platformBridge.syncIfViewing(admin, top);
            });
        });
    }

    private void scheduleDelayedListLoading(
            Player admin,
            BackupListHolder holder,
            long refreshSeq,
            Component loadingLabel
    ) {
        if (admin == null || holder == null) {
            return;
        }

        long delaySeconds = loadingIndicatorDelaySeconds();
        Runnable renderTask = () -> runOnPlayer(admin, () -> {
            if (!holder.isRefreshSeqCurrent(refreshSeq)) {
                return;
            }
            if (holder.screen() != BackupListHolder.Screen.LIST_LOADING) {
                return;
            }

            Inventory inventory = holder.getInventory();
            if (inventory == null) {
                return;
            }

            loadingRenderer.render(inventory, loadingLabel);
            platformBridge.syncIfViewing(admin, inventory);
        });

        // 用异步调度器按真实时间计时, 到期后再切回玩家线程更新 GUI
        if (delaySeconds <= 0L) {
            renderTask.run();
            return;
        }
        Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> renderTask.run(), delaySeconds, TimeUnit.SECONDS);
    }

    private long loadingIndicatorDelaySeconds() {
        var config = plugin.pluginConfig();
        if (config == null || config.guiLoadingIndicatorDelay() == null) {
            return DEFAULT_LOADING_INDICATOR_DELAY_SECONDS;
        }
        return Math.max(0L, config.guiLoadingIndicatorDelay().toSeconds());
    }

    private static String scope(Player admin, UUID targetUuid) {
        if (admin == null || targetUuid == null) {
            return "unknown";
        }
        return admin.getUniqueId().equals(targetUuid) ? "self" : "others";
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
}
