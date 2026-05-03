package org.playerinvbackup.backup.gui;

import java.util.UUID;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.action.PendingDeliveryService;
import org.playerinvbackup.backup.gui.action.ShulkerExportService;
import org.playerinvbackup.backup.gui.action.SlotClaimService;
import org.playerinvbackup.backup.gui.confirm.RestoreConfirmActions;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.list.BackupListActions;
import org.playerinvbackup.backup.gui.list.BackupListController;
import org.playerinvbackup.backup.gui.platform.DefaultGuiPlatformBridge;
import org.playerinvbackup.backup.gui.preview.PreviewSnapshotService;
import org.playerinvbackup.backup.gui.render.BackupListRenderer;
import org.playerinvbackup.backup.gui.render.BackupViewRenderer;
import org.playerinvbackup.backup.gui.render.GuiItemFactory;
import org.playerinvbackup.backup.gui.render.LoadingScreenRenderer;
import org.playerinvbackup.backup.gui.render.RestoreConfirmRenderer;
import org.playerinvbackup.backup.gui.session.BackupIdSearchSessionService;
import org.playerinvbackup.backup.gui.view.BackupViewActions;
import org.playerinvbackup.backup.gui.view.BackupViewController;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.store.BackupQuery;
import org.bukkit.entity.Player;

/**
 * GUI 外部入口 facade
 *
 * <p>这个类只保留对外公开方法和高层委派, 具体的数据加载, 渲染, 点击动作, 搜索会话
 * claim 和 pending 业务流程都下沉到协作者
 */
public final class GuiService {
    private final DefaultGuiPlatformBridge platformBridge;
    private final BackupListController backupListController;
    private final BackupViewController backupViewController;
    private final PendingDeliveryService pendingDeliveryService;
    private final BackupIdSearchSessionService backupIdSearchSessionService;
    private final BackupListActions backupListActions;
    private final BackupViewActions backupViewActions;
    private final RestoreConfirmActions restoreConfirmActions;

    public GuiService(PlayerInvBackupPlugin plugin, RestoreService restoreService) {
        this.platformBridge = new DefaultGuiPlatformBridge(plugin);

        GuiItemFactory itemFactory = new GuiItemFactory(plugin);
        LoadingScreenRenderer loadingScreenRenderer = new LoadingScreenRenderer(itemFactory);
        BackupListRenderer backupListRenderer = new BackupListRenderer(plugin, itemFactory);
        BackupViewRenderer backupViewRenderer = new BackupViewRenderer(plugin, itemFactory);
        RestoreConfirmRenderer restoreConfirmRenderer = new RestoreConfirmRenderer(plugin, itemFactory);
        PreviewSnapshotService previewSnapshotService = new PreviewSnapshotService();

        this.backupListController = new BackupListController(
                plugin,
                platformBridge,
                loadingScreenRenderer,
                backupListRenderer
        );
        this.backupViewController = new BackupViewController(
                plugin,
                platformBridge,
                backupListController,
                loadingScreenRenderer,
                backupViewRenderer,
                previewSnapshotService
        );

        this.pendingDeliveryService = new PendingDeliveryService(plugin);
        SlotClaimService slotClaimService = new SlotClaimService(plugin, platformBridge, itemFactory);
        ShulkerExportService shulkerExportService = new ShulkerExportService(plugin);

        this.backupIdSearchSessionService = new BackupIdSearchSessionService(
                plugin,
                platformBridge,
                backupListController,
                backupViewController
        );

        this.restoreConfirmActions = new RestoreConfirmActions(
                plugin,
                restoreService,
                backupViewController,
                platformBridge
        );
        this.backupViewActions = new BackupViewActions(
                plugin,
                platformBridge,
                backupListController,
                backupViewController,
                backupViewRenderer,
                restoreConfirmRenderer,
                pendingDeliveryService,
                slotClaimService,
                shulkerExportService
        );
        this.backupListActions = new BackupListActions(
                plugin,
                platformBridge,
                backupListController,
                backupViewController,
                backupIdSearchSessionService
        );
        this.backupListActions.setViewActions(backupViewActions);
    }

    public void setPacketGuiManager(PacketGuiManager packetGuiManager) {
        platformBridge.setPacketGuiManager(packetGuiManager);
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page) {
        backupListController.openBackupList(admin, targetUuid, targetName, page);
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page, BackupQuery query) {
        backupListController.openBackupList(admin, targetUuid, targetName, page, query);
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
        backupViewController.openBackupView(admin, targetUuid, targetName, listPage, listQuery, backupId, view);
    }

    public void openBackupView(Player admin, UUID targetUuid, String targetName, int listPage, String backupId, GuiView view) {
        backupViewController.openBackupView(admin, targetUuid, targetName, listPage, backupId, view);
    }

    public void deliverPending(Player admin) {
        pendingDeliveryService.deliverPending(admin);
    }

    public void handleListClick(Player admin, BackupListHolder holder, int slot) {
        backupListActions.handleClick(admin, holder, slot);
    }

    public void handleViewClick(Player admin, BackupViewHolder holder, int slot) {
        backupViewActions.handleClick(admin, holder, slot);
    }

    public void handleRestoreConfirmClick(Player admin, RestoreConfirmHolder holder, int slot) {
        restoreConfirmActions.handleClick(admin, holder, slot);
    }

    public boolean handleBackupIdSearchChat(Player admin, String message) {
        return backupIdSearchSessionService.handleChat(admin, message);
    }

    public void cancelBackupIdSearch(UUID adminUuid) {
        backupIdSearchSessionService.cancel(adminUuid);
    }
}
