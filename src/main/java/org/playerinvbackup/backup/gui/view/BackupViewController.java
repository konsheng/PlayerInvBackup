package org.playerinvbackup.backup.gui.view;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.GuiView;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.list.BackupListController;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.preview.PreviewSnapshotData;
import org.playerinvbackup.backup.gui.preview.PreviewSnapshotService;
import org.playerinvbackup.backup.gui.render.BackupViewRenderer;
import org.playerinvbackup.backup.gui.render.LoadingScreenRenderer;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 详情页控制器, 负责读取备份记录, 组装 holder, 驱动 loading -> view 状态切换
 */
public final class BackupViewController {
    private static final int GUI_SIZE = 54;
    private static final String MAIN_LABEL = "pib";
    private static final long DEFAULT_LOADING_INDICATOR_DELAY_SECONDS = 3L;

    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final BackupListController listController;
    private final LoadingScreenRenderer loadingRenderer;
    private final BackupViewRenderer viewRenderer;
    private final PreviewSnapshotService previewSnapshotService;

    public BackupViewController(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            BackupListController listController,
            LoadingScreenRenderer loadingRenderer,
            BackupViewRenderer viewRenderer,
            PreviewSnapshotService previewSnapshotService
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.listController = listController;
        this.loadingRenderer = loadingRenderer;
        this.viewRenderer = viewRenderer;
        this.previewSnapshotService = previewSnapshotService;
    }

    public void openBackupView(
            Player admin,
            UUID targetUuid,
            String targetName,
            int listPage,
            BackupQuery listQuery,
            String backupId,
            GuiView view
    ) {
        openBackupView(admin, targetUuid, targetName, listPage, listQuery, backupId, view, BackupGuiMode.MANAGE);
    }

    public void openBackupView(
            Player admin,
            UUID targetUuid,
            String targetName,
            int listPage,
            BackupQuery listQuery,
            String backupId,
            GuiView view,
            BackupGuiMode guiMode
    ) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        UUID adminUuid = admin.getUniqueId();
        String adminName = admin.getName();
        int safeListPage = Math.max(0, listPage);
        BackupQuery safeQuery = listQuery == null ? BackupQuery.all() : listQuery;
        GuiView safeView = view == null ? GuiView.INVENTORY : view;
        BackupGuiMode safeMode = guiMode == null ? BackupGuiMode.MANAGE : guiMode;
        String safeTargetName = targetName == null ? String.valueOf(targetUuid) : targetName;

        plugin.auditService().log(
                "OPEN_VIEW",
                admin,
                targetUuid,
                targetName,
                backupId,
                "mode=" + safeMode.name()
                        + " scope=" + scope(admin, targetUuid)
                        + " view=" + safeView.name()
                        + " listPage=" + safeListPage
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
        );

        Component title = viewRenderer.title(safeTargetName);
        Component loadingLabel = plugin.lang().msgNoPrefix("gui.backup-view.loading-title");

        runOnPlayer(admin, () -> {
            BackupListHolder listHolder = listController.findOpenBackupListHolder(admin, targetUuid);
            if (listHolder == null) {
                listHolder = new BackupListHolder(targetUuid, safeTargetName, safeListPage, safeQuery, List.of(), safeMode);
                Inventory inventory = Bukkit.createInventory(listHolder, GUI_SIZE, title);
                listHolder.setInventory(inventory);
                listHolder.setScreen(BackupListHolder.Screen.VIEW_LOADING);
                listHolder.setListLoaded(false);
                listHolder.setViewHolder(null);

                platformBridge.openMenu(admin, inventory, title);
            } else {
                Inventory inventory = listHolder.getInventory();
                if (inventory == null) {
                    return;
                }

                listHolder.nextRefreshSeq();
                listHolder.setPage(safeListPage);
                listHolder.setQuery(safeQuery);
                listHolder.setGuiMode(safeMode);
                listHolder.setViewHolder(null);
                listHolder.setScreen(BackupListHolder.Screen.VIEW_LOADING);
            }

            long viewSeq = listHolder.nextViewRefreshSeq();
            BackupListHolder finalListHolder = listHolder;
            // 先进入 VIEW_LOADING 状态阻止重复点击, 但暂时保留旧界面
            // 只有真实时间超过 3 秒仍未完成, 才显示 loading 画面
            scheduleDelayedViewLoading(admin, finalListHolder, viewSeq, loadingLabel);

            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                BackupRecord record;
                List<org.playerinvbackup.backup.domain.SlotClaim> claims;
                try {
                    record = store.loadBackup(targetUuid, backupId).orElse(null);
                    if (record == null) {
                        runOnPlayer(admin, () -> {
                            if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                                return;
                            }
                            Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                            listController.openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery, safeMode);
                        });
                        return;
                    }
                    claims = store.listClaims(targetUuid, backupId);
                } catch (Exception e) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.gui.backup-load-failed",
                                    Placeholder.unparsed("actor", adminName),
                                    Placeholder.unparsed("actor_uuid", adminUuid.toString()),
                                    Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                    Placeholder.unparsed("backup_id", backupId)
                            ),
                            e
                    );
                    runOnPlayer(admin, () -> {
                        if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                            return;
                        }
                        Chat.error(admin, "errors.load-failed");
                        listController.openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery, safeMode);
                    });
                    return;
                }

                SnapshotParts parts;
                try {
                    parts = SnapshotCodec.decodeGzip(record.snapshotBytes());
                } catch (IOException e) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.gui.snapshot-invalid",
                                    Placeholder.unparsed("actor", adminName),
                                    Placeholder.unparsed("actor_uuid", adminUuid.toString()),
                                    Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                    Placeholder.unparsed("backup_id", backupId)
                            ),
                            e
                    );
                    runOnPlayer(admin, () -> {
                        if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                            return;
                        }
                        Chat.error(admin, "errors.snapshot-invalid");
                        listController.openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery, safeMode);
                    });
                    return;
                }

                boolean claimOnce = plugin.pluginConfig() != null
                        && plugin.pluginConfig().guiPreview().claimOnce();
                boolean blockWhole = plugin.pluginConfig() != null
                        && plugin.pluginConfig().guiBackupViewBlockWholeBackupClaimOnIncompatible();
                PreviewSnapshotData previewData = previewSnapshotService.build(parts, claims, blockWhole, claimOnce);

                runOnPlayer(admin, () -> {
                    if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                        return;
                    }
                    Inventory inventory = finalListHolder.getInventory();
                    if (inventory == null) {
                        return;
                    }

                    BackupViewHolder viewHolder = new BackupViewHolder(
                            targetUuid,
                            safeTargetName,
                            backupId,
                            record.meta().serverId(),
                            safeListPage,
                            safeQuery,
                            safeMode,
                            safeView,
                            parts,
                            claimOnce,
                            previewData.claimedInv(),
                            previewData.claimedEnder(),
                            previewData.claimRecordInv(),
                            previewData.claimRecordEnder(),
                            previewData.incompatibleInv(),
                            previewData.incompatibleEnder(),
                            previewData.incompatibleClaimBlocksWholeBackup(),
                            record.meta().worldName(),
                            record.meta().locationX(),
                            record.meta().locationY(),
                            record.meta().locationZ(),
                            record.meta().targetWorldName(),
                            record.meta().targetLocationX(),
                            record.meta().targetLocationY(),
                            record.meta().targetLocationZ(),
                            record.meta().killerPlayerUuid(),
                            record.meta().killerPlayerName(),
                            record.meta().locked(),
                            record.meta().note()
                    );
                    viewHolder.setInventory(inventory);
                    finalListHolder.setViewHolder(viewHolder);
                    finalListHolder.setScreen(BackupListHolder.Screen.VIEW);

                    viewRenderer.renderScreen(inventory, viewHolder);
                    platformBridge.syncIfViewing(admin, inventory);
                });
            });
        });
    }

    public void openBackupView(Player admin, UUID targetUuid, String targetName, int listPage, String backupId, GuiView view) {
        openBackupView(admin, targetUuid, targetName, listPage, BackupQuery.all(), backupId, view);
    }

    public void openBackupView(
            Player admin,
            UUID targetUuid,
            String targetName,
            int listPage,
            String backupId,
            GuiView view,
            BackupGuiMode guiMode
    ) {
        openBackupView(admin, targetUuid, targetName, listPage, BackupQuery.all(), backupId, view, guiMode);
    }

    private void scheduleDelayedViewLoading(
            Player admin,
            BackupListHolder holder,
            long viewSeq,
            Component loadingLabel
    ) {
        if (admin == null || holder == null) {
            return;
        }

        long delaySeconds = loadingIndicatorDelaySeconds();
        Runnable renderTask = () -> runOnPlayer(admin, () -> {
            if (!holder.isViewRefreshSeqCurrent(viewSeq)) {
                return;
            }
            if (holder.screen() != BackupListHolder.Screen.VIEW_LOADING) {
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
