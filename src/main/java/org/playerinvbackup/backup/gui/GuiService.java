package org.playerinvbackup.backup.gui;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.codec.SnapshotCodec;
import org.playerinvbackup.backup.config.GuiSoundAction;
import org.playerinvbackup.backup.config.GuiTimeFilterOption;
import org.playerinvbackup.backup.domain.BackupMeta;
import org.playerinvbackup.backup.domain.BackupRecord;
import org.playerinvbackup.backup.domain.SlotClaim;
import org.playerinvbackup.backup.domain.SlotType;
import org.playerinvbackup.backup.domain.SnapshotParts;
import org.playerinvbackup.backup.domain.TriggerType;
import org.playerinvbackup.backup.domain.UndeliveredClaim;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.BackupViewHolder;
import org.playerinvbackup.backup.gui.holder.LoadingHolder;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.packet.PacketGuiManager;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.store.BackupStore;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI 服务
 *
 * <p>负责:
 * 1) 构建备份列表/备份预览/恢复确认等界面
 * 2) 处理按钮点击并进行原地刷新(仅在需要改标题时重开 GUI)
 * 3) 协调 PacketGuiManager 或原生 GUI 的打开/刷新/关闭
 */
public final class GuiService {
    private static final DateTimeFormatter DEFAULT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String MAIN_LABEL = "pib";

    private static final int GUI_SIZE = 54;

    private static final int SLOT_LIST_PREV = 45;
    private static final int SLOT_LIST_TIME_FILTER = 46;
    private static final int SLOT_LIST_TRIGGER_FILTER = 47;
    private static final int SLOT_LIST_SEARCH = 48;
    private static final int SLOT_LIST_CLEAR_FILTERS = 49;
    private static final int SLOT_LIST_JUMP_BACK = 50;
    private static final int SLOT_LIST_JUMP_FORWARD = 51;
    private static final int SLOT_LIST_REFRESH = 52;
    private static final int SLOT_LIST_NEXT = 53;

    private static final int SLOT_VIEW_BACK = 45;
    private static final int SLOT_VIEW_TOGGLE = 46;
    private static final int SLOT_VIEW_RESTORE = 47;
    private static final int SLOT_VIEW_EXPERIENCE = 48;
    private static final int SLOT_VIEW_LOCK = 52;
    private static final int SLOT_VIEW_PENDING = 53;

    private static final int CONFIRM_GUI_SIZE = 27;
    private static final int CONFIRM_OK = 11;
    private static final int CONFIRM_INFO = 13;
    private static final int CONFIRM_CANCEL = 15;

    private final PlayerInvBackupPlugin plugin;
    private final RestoreService restoreService;
    private PacketGuiManager packetGuiManager;
    private final Map<UUID, BackupIdSearchSession> backupIdSearchSessions = new ConcurrentHashMap<>();

    public GuiService(PlayerInvBackupPlugin plugin, RestoreService restoreService) {
        this.plugin = plugin;
        this.restoreService = restoreService;
    }

    private DateTimeFormatter timeFormatter() {
        var config = plugin.pluginConfig();
        return config == null ? DEFAULT_TIME_FORMAT : config.backupTimeFormatter();
    }

    public void setPacketGuiManager(PacketGuiManager packetGuiManager) {
        this.packetGuiManager = packetGuiManager;
    }

    private record BackupIdSearchSession(UUID targetUuid, String targetName, int page, BackupQuery query) {
    }

    private BackupStore resolveStoreOrError(Player player, boolean closeMenu) {
        BackupStore store = plugin.store();
        if (store != null && plugin.isStoreReady()) {
            return store;
        }
        if (player != null) {
            if (closeMenu) {
                closeMenu(player);
            }
            Chat.error(player, "errors.store-unavailable", Placeholder.unparsed("label", MAIN_LABEL));
        }
        return null;
    }

    private BackupListHolder findOpenBackupListHolder(Player player, UUID targetUuid) {
        if (player == null || targetUuid == null) {
            return null;
        }
        if (!player.isOnline()) {
            return null;
        }

        Inventory top = null;
        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            top = manager.currentTop(player);
        } else {
            try {
                InventoryView view = player.getOpenInventory();
                top = view == null ? null : view.getTopInventory();
            } catch (Exception ignored) {
                top = null;
            }
        }

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

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page) {
        openBackupList(admin, targetUuid, targetName, page, BackupQuery.all());
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page, BackupQuery query) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        int safePage = Math.max(0, page);
        BackupQuery safeQuery = query == null ? BackupQuery.all() : query;

        plugin.auditService().log(
                "OPEN_LIST",
                admin,
                targetUuid,
                targetName,
                null,
                "page=" + safePage
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
        );

        runOnPlayer(admin, () -> {
            BackupListHolder existing = findOpenBackupListHolder(admin, targetUuid);
            if (existing != null) {
                existing.nextViewRefreshSeq(); // 使正在进行的预览加载失效(避免异步结果覆盖当前界面)
                existing.setViewHolder(null);

                boolean same = safePage == existing.page() && safeQuery.equals(existing.query());
                int limit = plugin.pluginConfig().guiListPageSize();
                if (same && existing.isListLoaded()) {
                    existing.setScreen(BackupListHolder.Screen.LIST);
                    renderBackupListInventory(existing.getInventory(), existing, existing.backups().size() >= limit);
                    syncIfViewing(admin, existing.getInventory());
                    return;
                }

                existing.setScreen(BackupListHolder.Screen.LIST_LOADING);
                existing.setListLoaded(false);
                renderLoadingInventory(existing.getInventory(), plugin.lang().msgNoPrefix("gui.backup-list.loading-title"));
                syncIfViewing(admin, existing.getInventory());
                refreshBackupList(admin, existing, safePage, safeQuery);
                return;
            }

            String name = targetName == null ? String.valueOf(targetUuid) : targetName;
            Lang lang = plugin.lang();
            Component title = backupListTitle(lang, name, safePage);

            BackupListHolder holder = new BackupListHolder(targetUuid, name, safePage, safeQuery, List.of());
            holder.setScreen(BackupListHolder.Screen.LIST_LOADING);
            Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, title);
            holder.setInventory(inv);

            renderLoadingInventory(inv, lang.msgNoPrefix("gui.backup-list.loading-title"));
            openMenu(admin, inv, title);
            refreshBackupList(admin, holder, safePage, safeQuery);
        });
    }

    // 原地刷新备份列表. 只有当标题需要变化时才重新打开 GUI
    private void refreshBackupList(Player admin, BackupListHolder holder, int page, BackupQuery query) {
        refreshBackupList(admin, holder, page, query, false);
    }

    private void refreshBackupList(Player admin, BackupListHolder holder, int page, BackupQuery query, boolean fallbackWarned) {
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
        int offset = safePage * limit;
        UUID targetUuid = holder.targetUuid();
        String targetName = holder.targetName();

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<BackupMeta> backups;
            try {
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
                    refreshBackupList(admin, holder, safePage - 1, safeQuery, true);
                });
                return;
            }

            boolean hasNextPage = backups.size() >= limit;
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
                holder.setQuery(safeQuery);
                holder.setBackups(backups);
                holder.setListLoaded(true);

                holder.setScreen(BackupListHolder.Screen.LIST);
                renderBackupListInventory(top, holder, hasNextPage);
                syncIfViewing(admin, top);
            });
        });
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
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        UUID adminUuid = admin.getUniqueId();
        String adminName = admin.getName();
        int safeListPage = Math.max(0, listPage);
        BackupQuery safeQuery = listQuery == null ? BackupQuery.all() : listQuery;
        GuiView safeView = view == null ? GuiView.INVENTORY : view;
        String safeTargetName = targetName == null ? String.valueOf(targetUuid) : targetName;
        Lang lang = plugin.lang();

        plugin.auditService().log(
                "OPEN_VIEW",
                admin,
                targetUuid,
                targetName,
                backupId,
                "view=" + safeView.name() + " listPage=" + safeListPage
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
        );

        Component title = backupViewTitle(lang, safeTargetName);
        Component loadingLabel = lang.msgNoPrefix("gui.backup-view.loading-title");

        runOnPlayer(admin, () -> {
            BackupListHolder listHolder = findOpenBackupListHolder(admin, targetUuid);
            if (listHolder == null) {
                listHolder = new BackupListHolder(targetUuid, safeTargetName, safeListPage, safeQuery, List.of());
                Inventory inv = Bukkit.createInventory(listHolder, GUI_SIZE, title);
                listHolder.setInventory(inv);
                listHolder.setScreen(BackupListHolder.Screen.VIEW_LOADING);
                listHolder.setListLoaded(false);
                listHolder.setViewHolder(null);

                renderLoadingInventory(inv, loadingLabel);
                openMenu(admin, inv, title);
            } else {
                Inventory inv = listHolder.getInventory();
                if (inv == null) {
                    return;
                }

                listHolder.nextRefreshSeq(); // 使正在进行的列表加载失效(避免异步结果覆盖当前界面)
                listHolder.setPage(safeListPage);
                listHolder.setQuery(safeQuery);
                listHolder.setViewHolder(null);
                listHolder.setScreen(BackupListHolder.Screen.VIEW_LOADING);

                renderLoadingInventory(inv, loadingLabel);
                syncIfViewing(admin, inv);
            }

            long viewSeq = listHolder.nextViewRefreshSeq();
            BackupListHolder finalListHolder = listHolder;

            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                BackupRecord record;
                List<SlotClaim> claims;
                try {
                    record = store.loadBackup(targetUuid, backupId).orElse(null);
                    if (record == null) {
                        runOnPlayer(admin, () -> {
                            if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                                return;
                            }
                            Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                            openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery);
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
                        openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery);
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
                        openBackupList(admin, targetUuid, safeTargetName, safeListPage, safeQuery);
                    });
                    return;
                }

                boolean[] claimedInv = new boolean[SnapshotCodec.INVENTORY_SLOT_COUNT];
                boolean[] claimedEnder = new boolean[SnapshotCodec.ENDER_CHEST_SLOT_COUNT];
                for (SlotClaim claim : claims) {
                    if (claim.slotType() == SlotType.INV && claim.slotIndex() >= 0 && claim.slotIndex() < claimedInv.length) {
                        claimedInv[claim.slotIndex()] = true;
                    } else if (claim.slotType() == SlotType.ENDER && claim.slotIndex() >= 0 && claim.slotIndex() < claimedEnder.length) {
                        claimedEnder[claim.slotIndex()] = true;
                    }
                }

                runOnPlayer(admin, () -> {
                    if (!finalListHolder.isViewRefreshSeqCurrent(viewSeq)) {
                        return;
                    }
                    Inventory inv = finalListHolder.getInventory();
                    if (inv == null) {
                        return;
                    }

                    BackupViewHolder viewHolder = new BackupViewHolder(
                            targetUuid,
                            safeTargetName,
                            backupId,
                            safeListPage,
                            safeQuery,
                            safeView,
                            parts,
                            claimedInv,
                            claimedEnder,
                            record.meta().worldName(),
                            record.meta().locationX(),
                            record.meta().locationY(),
                            record.meta().locationZ(),
                            record.meta().locked(),
                            record.meta().note()
                    );
                    viewHolder.setInventory(inv);
                    finalListHolder.setViewHolder(viewHolder);
                    finalListHolder.setScreen(BackupListHolder.Screen.VIEW);

                    renderBackupViewScreen(inv, viewHolder);
                    syncIfViewing(admin, inv);
                });
            });
        });
    }

    public void openBackupView(Player admin, UUID targetUuid, String targetName, int listPage, String backupId, GuiView view) {
        openBackupView(admin, targetUuid, targetName, listPage, BackupQuery.all(), backupId, view);
    }

    public void deliverPending(Player admin) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }
        UUID actorUuid = admin.getUniqueId();
        String actorName = admin.getName();
        runOnPlayer(admin, () -> Chat.info(admin, "info.checking-pending"));
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<UndeliveredClaim> pending;
            try {
                pending = store.listUndelivered(actorUuid, 100);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.pending-load-failed",
                                Placeholder.unparsed("actor", actorName),
                                Placeholder.unparsed("actor_uuid", actorUuid.toString())
                        ),
                        e
                );
                runOnPlayer(admin, () -> Chat.error(admin, "errors.read-failed"));
                return;
            }

            if (pending.isEmpty()) {
                runOnPlayer(admin, () -> Chat.success(admin, "success.pending-none"));
                return;
            }

            runOnPlayer(admin, () -> deliverPendingSequential(admin, store, pending, 0, 0));
        });
    }

    private void deliverPendingSequential(Player admin, BackupStore store, List<UndeliveredClaim> pending, int index, int deliveredCount) {
        if (admin == null || !admin.isOnline()) {
            return;
        }
        if (index >= pending.size()) {
            Chat.success(admin, "success.pending-delivered", Placeholder.unparsed("count", String.valueOf(deliveredCount)));
            plugin.auditService().log(
                    "DELIVER_PENDING",
                    admin,
                    null,
                    null,
                    null,
                    "delivered=" + deliveredCount + " remaining=0"
            );
            return;
        }

        UndeliveredClaim claim = pending.get(index);
        ItemStack item = ItemStack.deserializeBytes(claim.itemBytes());
        ItemStack[] before = cloneStorage(admin.getInventory().getStorageContents());

        boolean ok = InventoryUtil.tryInsertIntoStorage(admin.getInventory(), item);
        if (!ok) {
            int remaining = Math.max(0, pending.size() - deliveredCount);
            Chat.error(admin, "errors.deliver-inventory-full", Placeholder.unparsed("remaining", String.valueOf(remaining)));
            plugin.auditService().log(
                    "DELIVER_PENDING",
                    admin,
                    null,
                    null,
                    null,
                    "delivered=" + deliveredCount + " remaining=" + remaining + " stopped=inventory_full"
            );
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            boolean marked;
            try {
                marked = store.markDelivered(
                        admin.getUniqueId(),
                        claim.playerUuid(),
                        claim.backupId(),
                        claim.slotType(),
                        claim.slotIndex(),
                        System.currentTimeMillis()
                );
            } catch (Exception e) {
                marked = false;
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.pending-mark-delivered-failed",
                                Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                        ),
                        e
                );
            }

            if (!marked) {
                runOnPlayer(admin, () -> {
                    admin.getInventory().setStorageContents(before);
                    admin.updateInventory();
                    Chat.error(admin, "errors.deliver-mark-failed-rollback");
                });
                return;
            }

            runOnPlayer(admin, () -> deliverPendingSequential(admin, store, pending, index + 1, deliveredCount + 1));
        });
    }

    public void handleListClick(Player admin, BackupListHolder holder, int slot) {
        if (slot < 0) {
            return;
        }
        BackupListHolder.Screen screen = holder.screen();
        if (screen == BackupListHolder.Screen.VIEW_LOADING || screen == BackupListHolder.Screen.LIST_LOADING) {
            return;
        }
        if (screen == BackupListHolder.Screen.VIEW) {
            BackupViewHolder viewHolder = holder.viewHolder();
            if (viewHolder == null) {
                return;
            }
            handleViewClick(admin, viewHolder, slot);
            return;
        }
        BackupQuery query = holder.query();

        if (slot == SLOT_LIST_PREV) {
            if (holder.page() <= 0) {
                playGuiSound(admin, GuiSoundAction.LIST_PAGE_DISABLED);
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            playGuiSound(admin, GuiSoundAction.LIST_PREV);
            refreshBackupList(admin, holder, holder.page() - 1, query);
            return;
        }
        if (slot == SLOT_LIST_NEXT) {
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                playGuiSound(admin, GuiSoundAction.LIST_PAGE_DISABLED);
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            playGuiSound(admin, GuiSoundAction.LIST_NEXT);
            refreshBackupList(admin, holder, holder.page() + 1, query);
            return;
        }

        if (slot == SLOT_LIST_TIME_FILTER) {
            playGuiSound(admin, GuiSoundAction.LIST_FILTER_TIME);
            BackupQuery next = nextTimeFilterQuery(query);
            refreshBackupList(admin, holder, 0, next);
            return;
        }

        if (slot == SLOT_LIST_TRIGGER_FILTER) {
            playGuiSound(admin, GuiSoundAction.LIST_FILTER_TRIGGER);
            BackupQuery next = nextTriggerFilterQuery(query);
            refreshBackupList(admin, holder, 0, next);
            return;
        }

        if (slot == SLOT_LIST_CLEAR_FILTERS) {
            playGuiSound(admin, GuiSoundAction.LIST_CLEAR_FILTERS);
            refreshBackupList(admin, holder, 0, BackupQuery.all());
            return;
        }

        if (slot == SLOT_LIST_SEARCH) {
            playGuiSound(admin, GuiSoundAction.LIST_SEARCH);
            beginBackupIdSearch(admin, holder);
            return;
        }

        if (slot == SLOT_LIST_JUMP_BACK) {
            playGuiSound(admin, GuiSoundAction.LIST_JUMP_BACK);
            int nextPage = Math.max(0, holder.page() - 5);
            if (nextPage == holder.page()) {
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            refreshBackupList(admin, holder, nextPage, query);
            return;
        }

        if (slot == SLOT_LIST_JUMP_FORWARD) {
            playGuiSound(admin, GuiSoundAction.LIST_JUMP_FORWARD);
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            refreshBackupList(admin, holder, holder.page() + 5, query);
            return;
        }

        if (slot == SLOT_LIST_REFRESH) {
            playGuiSound(admin, GuiSoundAction.LIST_REFRESH);
            refreshBackupList(admin, holder, holder.page(), query);
            return;
        }

        if (slot >= holder.backups().size()) {
            playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
            return;
        }
        playGuiSound(admin, GuiSoundAction.LIST_ENTRY);
        BackupMeta meta = holder.backups().get(slot);
        openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.page(), query, meta.backupId(), GuiView.INVENTORY);
    }

    public void handleViewClick(Player admin, BackupViewHolder holder, int slot) {
        if (slot == SLOT_VIEW_BACK) {
            playGuiSound(admin, GuiSoundAction.VIEW_BACK);
            openBackupList(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery());
            return;
        }
        if (slot == SLOT_VIEW_TOGGLE) {
            playGuiSound(admin, GuiSoundAction.VIEW_TOGGLE);
            GuiView next = holder.view() == GuiView.INVENTORY ? GuiView.ENDER_CHEST : GuiView.INVENTORY;
            runOnPlayer(admin, () -> {
                Inventory top = holder.getInventory();
                if (top == null || !isViewing(admin, top)) {
                    return;
                }
                holder.setView(next);
                renderBackupViewInventory(top, holder);
                syncIfViewing(admin, top);
            });
            return;
        }
        if (slot == SLOT_VIEW_PENDING) {
            playGuiSound(admin, GuiSoundAction.VIEW_PENDING);
            if (!Permissions.has(admin, Permissions.PENDING)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.PENDING));
                return;
            }
            deliverPending(admin);
            return;
        }
        if (slot == SLOT_VIEW_RESTORE) {
            playGuiSound(admin, GuiSoundAction.VIEW_RESTORE);
            if (!Permissions.has(admin, Permissions.RESTORE)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
                return;
            }
            Player target = Bukkit.getPlayer(holder.targetUuid());
            if (target == null) {
                Chat.error(admin, "errors.restore-target-offline");
                return;
            }
            openRestoreConfirm(admin, holder, RestoreConfirmHolder.RestoreKind.ITEMS);
            return;
        }
        if (slot == SLOT_VIEW_EXPERIENCE) {
            playGuiSound(admin, GuiSoundAction.VIEW_RESTORE);
            if (!holder.parts().hasExperienceData()) {
                Chat.error(admin, "errors.backup-experience-unavailable");
                return;
            }
            if (!Permissions.has(admin, Permissions.RESTORE)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
                return;
            }
            Player target = Bukkit.getPlayer(holder.targetUuid());
            if (target == null) {
                Chat.error(admin, "errors.restore-target-offline");
                return;
            }
            openRestoreConfirm(admin, holder, RestoreConfirmHolder.RestoreKind.EXPERIENCE);
            return;
        }
        if (slot == SLOT_VIEW_LOCK) {
            playGuiSound(admin, GuiSoundAction.VIEW_LOCK);
            if (!Permissions.has(admin, Permissions.LOCK)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.LOCK));
                return;
            }
            BackupStore store = resolveStoreOrError(admin, true);
            if (store == null) {
                return;
            }
            boolean nextLocked = !holder.locked();
            UUID targetUuid = holder.targetUuid();
            String targetName = holder.targetName();
            String backupId = holder.backupId();

            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
                boolean ok;
                try {
                    ok = store.setBackupLocked(targetUuid, backupId, nextLocked);
                } catch (Exception e) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.gui.lock-update-failed",
                                    Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                    Placeholder.unparsed("backup_id", backupId)
                            ),
                            e
                    );
                    runOnPlayer(admin, () -> Chat.error(admin, "errors.read-failed"));
                    return;
                }

                if (!ok) {
                    runOnPlayer(admin, () -> Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId)));
                    return;
                }

                plugin.auditService().log(
                        nextLocked ? "LOCK_BACKUP" : "UNLOCK_BACKUP",
                        admin,
                        targetUuid,
                        targetName,
                        backupId,
                        null
                );

                runOnPlayer(admin, () -> {
                    Inventory top = holder.getInventory();
                    if (top == null || !isViewing(admin, top)) {
                        return;
                    }
                    holder.setLocked(nextLocked);
                    renderBackupViewLockItem(top, holder);
                    syncIfViewing(admin, top);
                });
            });
            return;
        }

        if (holder.view() == GuiView.INVENTORY) {
            if (slot < 0 || slot >= SnapshotCodec.INVENTORY_SLOT_COUNT) {
                return;
            }
            if (holder.claimedInv()[slot]) {
                playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
                return;
            }
            byte[] itemBytes = holder.parts().inventorySlotBytes()[slot];
            if (itemBytes == null || itemBytes.length == 0) {
                return;
            }
            playGuiSound(admin, GuiSoundAction.VIEW_CLAIM_SLOT);
            tryClaimSlot(admin, holder, SlotType.INV, slot, itemBytes);
        } else {
            if (slot < 0 || slot >= SnapshotCodec.ENDER_CHEST_SLOT_COUNT) {
                return;
            }
            if (holder.claimedEnder()[slot]) {
                playBarrierSlotSoundIfPresent(admin, holder.getInventory(), slot);
                return;
            }
            byte[] itemBytes = holder.parts().enderChestSlotBytes()[slot];
            if (itemBytes == null || itemBytes.length == 0) {
                return;
            }
            playGuiSound(admin, GuiSoundAction.VIEW_CLAIM_SLOT);
            tryClaimSlot(admin, holder, SlotType.ENDER, slot, itemBytes);
        }
    }

    public void handleRestoreConfirmClick(Player admin, RestoreConfirmHolder holder, int slot) {
        if (slot == CONFIRM_CANCEL) {
            playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_CANCEL);
            openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery(), holder.backupId(), holder.returnView());
            return;
        }

        if (slot == CONFIRM_INFO) {
            playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_INFO);
            return;
        }

        if (slot != CONFIRM_OK) {
            return;
        }

        playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_OK);
        if (!Permissions.has(admin, Permissions.RESTORE)) {
            Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
            openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery(), holder.backupId(), holder.returnView());
            return;
        }

        Player target = Bukkit.getPlayer(holder.targetUuid());
        if (target == null) {
            Chat.error(admin, "errors.restore-target-offline");
            openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery(), holder.backupId(), holder.returnView());
            return;
        }

        closeMenu(admin);
        if (holder.kind() == RestoreConfirmHolder.RestoreKind.EXPERIENCE) {
            restoreService.restoreExperienceToPlayer(admin, target, holder.backupId());
            return;
        }
        restoreService.restoreToPlayer(admin, target, holder.backupId());
    }

    private void tryClaimSlot(
            Player admin,
            BackupViewHolder holder,
            SlotType slotType,
            int slotIndex,
            byte[] itemBytes
    ) {
        if (itemBytes == null || itemBytes.length == 0) {
            return;
        }

        Inventory inv = holder.getInventory();
        if (inv == null) {
            return;
        }
        BackupStore store = resolveStoreOrError(admin, true);
        if (store == null) {
            return;
        }
        inv.setItem(slotIndex, namedItem(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                plugin.lang().msg("gui.backup-view.processing.name"),
                List.of()
        ));
        syncIfViewing(admin, inv);

        UUID actorUuid = admin.getUniqueId();
        String actorName = admin.getName();
        long now = System.currentTimeMillis();

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            boolean claimed;
            try {
                claimed = store.tryClaimSlot(
                        holder.targetUuid(),
                        holder.backupId(),
                        slotType,
                        slotIndex,
                        actorUuid,
                        actorName,
                        now,
                        itemBytes
                );
            } catch (Exception e) {
                runOnPlayer(admin, () -> {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            plugin.lang().plain(
                                    "console.gui.claim-failed",
                                    Placeholder.unparsed("actor", actorName),
                                    Placeholder.unparsed("actor_uuid", actorUuid.toString()),
                                    Placeholder.unparsed("target_uuid", holder.targetUuid().toString()),
                                    Placeholder.unparsed("backup_id", holder.backupId()),
                                    Placeholder.unparsed("slot", slotType + ":" + slotIndex)
                            ),
                            e
                    );
                    Chat.error(admin, "errors.claim-failed");
                    restoreOriginalSlot(admin, holder, slotType, slotIndex);
                });
                return;
            }

            if (!claimed) {
                runOnPlayer(admin, () -> {
                    Chat.warn(admin, "errors.already-claimed");
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                });
                return;
            }

            runOnPlayer(admin, () -> {
                ItemStack item = ItemStack.deserializeBytes(itemBytes);
                ItemStack[] before = cloneStorage(admin.getInventory().getStorageContents());
                boolean delivered = InventoryUtil.tryInsertIntoStorage(admin.getInventory(), item);
                if (delivered) {
                    Chat.success(admin, "success.claim-success", Placeholder.unparsed("amount", String.valueOf(item.getAmount())));
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                    Bukkit.getAsyncScheduler().runNow(plugin, ignored2 -> {
                        boolean marked;
                        try {
                            marked = store.markDelivered(
                                    actorUuid,
                                    holder.targetUuid(),
                                    holder.backupId(),
                                    slotType,
                                    slotIndex,
                                    System.currentTimeMillis()
                            );
                        } catch (Exception e) {
                            marked = false;
                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    plugin.lang().plain(
                                            "console.gui.pending-mark-delivered-failed",
                                            Placeholder.unparsed("reason", String.valueOf(e.getMessage()))
                                    ),
                                    e
                            );
                        }

                        if (!marked) {
                            runOnPlayer(admin, () -> {
                                admin.getInventory().setStorageContents(before);
                                admin.updateInventory();
                                Chat.error(admin, "errors.claim-mark-failed-rollback", Placeholder.unparsed("label", MAIN_LABEL));
                            });
                            plugin.auditService().log(
                                    "CLAIM_SLOT",
                                    admin,
                                    holder.targetUuid(),
                                    holder.targetName(),
                                    holder.backupId(),
                                    "slot=" + slotType.name() + ":" + slotIndex
                                            + " delivered=false reason=db_mark_failed"
                                            + " item=" + item.getType().name()
                                            + " x" + item.getAmount()
                            );
                            return;
                        }

                        plugin.auditService().log(
                                "CLAIM_SLOT",
                                admin,
                                holder.targetUuid(),
                                holder.targetName(),
                                holder.backupId(),
                                "slot=" + slotType.name() + ":" + slotIndex
                                        + " delivered=true"
                                        + " item=" + item.getType().name()
                                        + " x" + item.getAmount()
                        );
                    });
                } else {
                    Chat.error(admin, "errors.inventory-full-pending", Placeholder.unparsed("label", MAIN_LABEL));
                    refreshSingleSlot(admin, holder, slotType, slotIndex, true);
                    plugin.auditService().log(
                            "CLAIM_SLOT",
                            admin,
                            holder.targetUuid(),
                            holder.targetName(),
                            holder.backupId(),
                            "slot=" + slotType.name() + ":" + slotIndex
                                    + " delivered=false reason=inventory_full"
                                    + " item=" + item.getType().name()
                                    + " x" + item.getAmount()
                    );
                }
            });
        });
    }

    private void refreshSingleSlot(Player admin, BackupViewHolder holder, SlotType slotType, int slotIndex, boolean claimed) {
        Inventory inv = holder.getInventory();
        if (inv == null || !isViewing(admin, inv)) {
            return;
        }
        if (slotType == SlotType.INV && slotIndex >= 0 && slotIndex < holder.claimedInv().length) {
            holder.claimedInv()[slotIndex] = claimed;
        } else if (slotType == SlotType.ENDER && slotIndex >= 0 && slotIndex < holder.claimedEnder().length) {
            holder.claimedEnder()[slotIndex] = claimed;
        }
        Lang lang = plugin.lang();
        inv.setItem(slotIndex, namedItem(
                Material.BARRIER,
                lang.msg("gui.backup-view.claimed.name"),
                lang.msgList("gui.backup-view.claimed.lore")
        ));
        syncIfViewing(admin, inv);
    }

    // 只重绘列表 GUI 的内容, 不重新打开 Inventory
    private void renderLoadingInventory(Inventory inv, Component label) {
        if (inv == null) {
            return;
        }

        Lang lang = plugin.lang();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, null);
        }

        int slot = Math.min(22, inv.getSize() - 1);
        if (slot < 0) {
            return;
        }
        Component name = label == null ? lang.msg("gui.loading.item-name") : label;
        inv.setItem(slot, namedItem(
                Material.CLOCK,
                name,
                lang.msgList("gui.loading.item-lore")
        ));
    }

    private void renderBackupListInventory(Inventory inv, BackupListHolder holder, boolean hasNextPage) {
        if (inv == null || holder == null) {
            return;
        }

        Lang lang = plugin.lang();
        List<BackupMeta> backups = holder.backups();
        BackupQuery safeQuery = holder.query() == null ? BackupQuery.all() : holder.query();

        for (int i = 0; i < 45; i++) {
            inv.setItem(i, null);
        }

        if (backups.isEmpty()) {
            inv.setItem(22, namedItem(
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
            inv.setItem(i, namedItem(icon,
                    lang.msg("gui.backup-list.entry.name", Placeholder.unparsed("time", time)),
                    lang.msgList(
                            "gui.backup-list.entry.lore",
                            Placeholder.unparsed("id", meta.backupId()),
                            Placeholder.unparsed("trigger", lang.raw(meta.trigger().langKey())),
                            Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                            Placeholder.unparsed("world", displayWorld(meta.worldName())),
                            Placeholder.unparsed("position", displayPosition(meta.locationX(), meta.locationY(), meta.locationZ())),
                            Placeholder.unparsed("locked", lockedText),
                            Placeholder.unparsed("note", noteText)
                    )));
        }

        String timeFilterValue = timeFilterDisplayValue(lang, safeQuery);
        String triggerFilterValue = safeQuery.trigger() == null
                ? lang.raw("gui.backup-list.filter-trigger.value.all")
                : lang.raw(safeQuery.trigger().langKey());

        inv.setItem(SLOT_LIST_PREV, holder.page() > 0
                ? namedItem(Material.ARROW, lang.msg("gui.backup-list.prev.name"), List.of())
                : namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.prev-disabled.name"),
                        lang.msgList("gui.backup-list.prev-disabled.lore")
                ));
        inv.setItem(SLOT_LIST_TIME_FILTER, namedItem(
                Material.CLOCK,
                lang.msg("gui.backup-list.filter-time.name"),
                lang.msgList("gui.backup-list.filter-time.lore", Placeholder.unparsed("value", timeFilterValue))
        ));
        inv.setItem(SLOT_LIST_TRIGGER_FILTER, namedItem(
                Material.COMPARATOR,
                lang.msg("gui.backup-list.filter-trigger.name"),
                lang.msgList("gui.backup-list.filter-trigger.lore", Placeholder.unparsed("value", triggerFilterValue))
        ));
        inv.setItem(SLOT_LIST_SEARCH, namedItem(
                Material.SPYGLASS,
                lang.msg("gui.backup-list.search.name"),
                lang.msgList(
                        "gui.backup-list.search.lore",
                        Placeholder.unparsed("cancel", cancelKeywordDisplay(lang))
                )
        ));
        inv.setItem(SLOT_LIST_CLEAR_FILTERS, namedItem(
                Material.MILK_BUCKET,
                lang.msg("gui.backup-list.clear.name"),
                lang.msgList("gui.backup-list.clear.lore")
        ));
        inv.setItem(SLOT_LIST_JUMP_BACK, namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-back.name"),
                lang.msgList("gui.backup-list.jump-back.lore")
        ));
        inv.setItem(SLOT_LIST_JUMP_FORWARD, namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-forward.name"),
                lang.msgList("gui.backup-list.jump-forward.lore")
        ));
        inv.setItem(SLOT_LIST_REFRESH, namedItem(
                Material.SUNFLOWER,
                lang.msg("gui.backup-list.refresh.name"),
                lang.msgList("gui.backup-list.refresh.lore", Placeholder.unparsed("page", String.valueOf(holder.page() + 1)))
        ));
        inv.setItem(SLOT_LIST_NEXT, hasNextPage
                ? namedItem(Material.ARROW, lang.msg("gui.backup-list.next.name"), List.of())
                : namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.next-disabled.name"),
                        lang.msgList("gui.backup-list.next-disabled.lore")
                ));
    }

    // 只更新 "锁定/置顶" 按钮, 避免因为点击而重新打开 GUI
    private void renderBackupViewScreen(Inventory inv, BackupViewHolder holder) {
        if (inv == null || holder == null) {
            return;
        }
        Lang lang = plugin.lang();

        int size = inv.getSize();
        for (int i = 45; i < size && i < 54; i++) {
            inv.setItem(i, null);
        }

        renderBackupViewInventory(inv, holder);

        inv.setItem(SLOT_VIEW_BACK, namedItem(Material.OAK_DOOR, lang.msg("gui.backup-view.back.name"), List.of()));
        inv.setItem(SLOT_VIEW_TOGGLE, namedItem(
                Material.ENDER_CHEST,
                lang.msg("gui.backup-view.toggle.name"),
                lang.msgList("gui.backup-view.toggle.lore")
        ));
        boolean online = Bukkit.getPlayer(holder.targetUuid()) != null;
        inv.setItem(SLOT_VIEW_RESTORE, online
                ? namedItem(Material.REDSTONE_BLOCK, lang.msg("gui.backup-view.restore.name"), lang.msgList("gui.backup-view.restore.lore"))
                : namedItem(Material.BARRIER, lang.msg("gui.backup-view.restore-offline.name"), lang.msgList("gui.backup-view.restore-offline.lore")));
        renderBackupViewExperienceItem(inv, holder);
        inv.setItem(SLOT_VIEW_PENDING, namedItem(Material.CHEST, lang.msg("gui.backup-view.pending.name"), lang.msgList("gui.backup-view.pending.lore")));

        renderBackupViewLockItem(inv, holder);
    }

    private void renderBackupViewLockItem(Inventory inv, BackupViewHolder holder) {
        if (inv == null || holder == null) {
            return;
        }
        Lang lang = plugin.lang();
        String lockedText = lang.raw(holder.locked() ? "common.yes_text" : "common.no_text");
        String noteText = holder.note() == null || holder.note().isBlank()
                ? lang.raw("common.none")
                : holder.note();
        inv.setItem(
                SLOT_VIEW_LOCK,
                namedItem(
                        Material.TRIPWIRE_HOOK,
                        lang.msg("gui.backup-view.lock.name"),
                        lang.msgList(
                                "gui.backup-view.lock.lore",
                                Placeholder.unparsed("locked", lockedText),
                                Placeholder.unparsed("note", noteText),
                                Placeholder.unparsed("world", displayWorld(holder.worldName())),
                                Placeholder.unparsed("position", displayPosition(holder.locationX(), holder.locationY(), holder.locationZ()))
                        )
                )
        );
    }

    private void renderBackupViewInventory(Inventory inv, BackupViewHolder holder) {
        if (inv == null || holder == null) {
            return;
        }
        Lang lang = plugin.lang();

        // 清空内容区域(不动底部按钮), 避免从背包视图切到末影箱后遗留旧物品
        int clearEndExclusive = Math.min(45, inv.getSize());
        for (int i = 0; i < clearEndExclusive; i++) {
            inv.setItem(i, null);
        }

        if (holder.view() == GuiView.INVENTORY) {
            for (int i = 0; i < SnapshotCodec.INVENTORY_SLOT_COUNT && i < inv.getSize(); i++) {
                if (holder.claimedInv()[i]) {
                    inv.setItem(i, namedItem(
                            Material.BARRIER,
                            lang.msg("gui.backup-view.claimed.name"),
                            lang.msgList("gui.backup-view.claimed.lore")
                    ));
                    continue;
                }
                byte[] itemBytes = holder.parts().inventorySlotBytes()[i];
                inv.setItem(i, toPreviewItem(itemBytes));
            }
            return;
        }

        for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT && i < inv.getSize(); i++) {
            if (holder.claimedEnder()[i]) {
                inv.setItem(i, namedItem(
                        Material.BARRIER,
                        lang.msg("gui.backup-view.claimed.name"),
                        lang.msgList("gui.backup-view.claimed.lore")
                ));
                continue;
            }
            byte[] itemBytes = holder.parts().enderChestSlotBytes()[i];
            inv.setItem(i, toPreviewItem(itemBytes));
        }
    }

    private Inventory createBackupListInventory(UUID targetUuid, String targetName, int page, List<BackupMeta> backups, boolean hasNextPage) {
        return createBackupListInventory(targetUuid, targetName, page, BackupQuery.all(), backups, hasNextPage);
    }

    private Inventory createBackupListInventory(
            UUID targetUuid,
            String targetName,
            int page,
            BackupQuery query,
            List<BackupMeta> backups,
            boolean hasNextPage
    ) {
        String name = targetName == null ? targetUuid.toString() : targetName;
        Lang lang = plugin.lang();
        BackupQuery safeQuery = query == null ? BackupQuery.all() : query;
        BackupListHolder holder = new BackupListHolder(targetUuid, name, page, safeQuery, backups);
        Inventory inv = Bukkit.createInventory(
                holder,
                GUI_SIZE,
                lang.msgNoPrefix(
                        "gui.backup-list.title",
                        Placeholder.unparsed("target", name),
                        Placeholder.unparsed("page", String.valueOf(page + 1))
                )
        );
        holder.setInventory(inv);

        if (backups.isEmpty()) {
            inv.setItem(22, namedItem(
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
            inv.setItem(i, namedItem(icon,
                    lang.msg("gui.backup-list.entry.name", Placeholder.unparsed("time", time)),
                    lang.msgList(
                            "gui.backup-list.entry.lore",
                            Placeholder.unparsed("id", meta.backupId()),
                            Placeholder.unparsed("trigger", lang.raw(meta.trigger().langKey())),
                            Placeholder.unparsed("size", String.valueOf(meta.snapshotSizeBytes())),
                            Placeholder.unparsed("world", displayWorld(meta.worldName())),
                            Placeholder.unparsed("position", displayPosition(meta.locationX(), meta.locationY(), meta.locationZ())),
                            Placeholder.unparsed("locked", lockedText),
                            Placeholder.unparsed("note", noteText)
                    )));
        }

        String timeFilterValue = timeFilterDisplayValue(lang, safeQuery);
        String triggerFilterValue = safeQuery.trigger() == null
                ? lang.raw("gui.backup-list.filter-trigger.value.all")
                : lang.raw(safeQuery.trigger().langKey());

        inv.setItem(SLOT_LIST_PREV, page > 0
                ? namedItem(Material.ARROW, lang.msg("gui.backup-list.prev.name"), List.of())
                : namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.prev-disabled.name"),
                        lang.msgList("gui.backup-list.prev-disabled.lore")
                ));
        inv.setItem(SLOT_LIST_TIME_FILTER, namedItem(
                Material.CLOCK,
                lang.msg("gui.backup-list.filter-time.name"),
                lang.msgList("gui.backup-list.filter-time.lore", Placeholder.unparsed("value", timeFilterValue))
        ));
        inv.setItem(SLOT_LIST_TRIGGER_FILTER, namedItem(
                Material.COMPARATOR,
                lang.msg("gui.backup-list.filter-trigger.name"),
                lang.msgList("gui.backup-list.filter-trigger.lore", Placeholder.unparsed("value", triggerFilterValue))
        ));
        inv.setItem(SLOT_LIST_SEARCH, namedItem(
                Material.SPYGLASS,
                lang.msg("gui.backup-list.search.name"),
                lang.msgList(
                        "gui.backup-list.search.lore",
                        Placeholder.unparsed("cancel", cancelKeywordDisplay(lang))
                )
        ));
        inv.setItem(SLOT_LIST_CLEAR_FILTERS, namedItem(
                Material.MILK_BUCKET,
                lang.msg("gui.backup-list.clear.name"),
                lang.msgList("gui.backup-list.clear.lore")
        ));
        inv.setItem(SLOT_LIST_JUMP_BACK, namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-back.name"),
                lang.msgList("gui.backup-list.jump-back.lore")
        ));
        inv.setItem(SLOT_LIST_JUMP_FORWARD, namedItem(
                Material.ARROW,
                lang.msg("gui.backup-list.jump-forward.name"),
                lang.msgList("gui.backup-list.jump-forward.lore")
        ));
        inv.setItem(SLOT_LIST_REFRESH, namedItem(
                Material.SUNFLOWER,
                lang.msg("gui.backup-list.refresh.name"),
                lang.msgList("gui.backup-list.refresh.lore", Placeholder.unparsed("page", String.valueOf(page + 1)))
        ));
        inv.setItem(SLOT_LIST_NEXT, hasNextPage
                ? namedItem(Material.ARROW, lang.msg("gui.backup-list.next.name"), List.of())
                : namedItem(
                        Material.GRAY_STAINED_GLASS_PANE,
                        lang.msg("gui.backup-list.next-disabled.name"),
                        lang.msgList("gui.backup-list.next-disabled.lore")
                ));
        return inv;
    }

    private Inventory createBackupViewInventory(
            UUID targetUuid,
            String targetName,
            int listPage,
            String backupId,
            GuiView view,
            SnapshotParts parts,
            boolean[] claimedInv,
            boolean[] claimedEnder,
            boolean locked,
            String note
    ) {
        return createBackupViewInventory(
                targetUuid,
                targetName,
                listPage,
                BackupQuery.all(),
                backupId,
                view,
                parts,
                claimedInv,
                claimedEnder,
                locked,
                note
        );
    }

    private Inventory createBackupViewInventory(
            UUID targetUuid,
            String targetName,
            int listPage,
            BackupQuery listQuery,
            String backupId,
            GuiView view,
            SnapshotParts parts,
            boolean[] claimedInv,
            boolean[] claimedEnder,
            boolean locked,
            String note
    ) {
        String name = targetName == null ? targetUuid.toString() : targetName;
        Lang lang = plugin.lang();
        BackupQuery safeQuery = listQuery == null ? BackupQuery.all() : listQuery;
        BackupViewHolder holder = new BackupViewHolder(
                targetUuid,
                name,
                backupId,
                listPage,
                safeQuery,
                view,
                parts,
                claimedInv,
                claimedEnder,
                null,
                null,
                null,
                null,
                locked,
                note
        );
        Inventory inv = Bukkit.createInventory(
                holder,
                GUI_SIZE,
                lang.msgNoPrefix("gui.backup-view.title", Placeholder.unparsed("target", name))
        );
        holder.setInventory(inv);

        if (view == GuiView.INVENTORY) {
            for (int i = 0; i < SnapshotCodec.INVENTORY_SLOT_COUNT; i++) {
                if (claimedInv[i]) {
                    inv.setItem(i, namedItem(
                            Material.BARRIER,
                            lang.msg("gui.backup-view.claimed.name"),
                            lang.msgList("gui.backup-view.claimed.lore")
                    ));
                    continue;
                }
                byte[] itemBytes = parts.inventorySlotBytes()[i];
                inv.setItem(i, toPreviewItem(itemBytes));
            }
        } else {
            for (int i = 0; i < SnapshotCodec.ENDER_CHEST_SLOT_COUNT; i++) {
                if (claimedEnder[i]) {
                    inv.setItem(i, namedItem(
                            Material.BARRIER,
                            lang.msg("gui.backup-view.claimed.name"),
                            lang.msgList("gui.backup-view.claimed.lore")
                    ));
                    continue;
                }
                byte[] itemBytes = parts.enderChestSlotBytes()[i];
                inv.setItem(i, toPreviewItem(itemBytes));
            }
        }

        inv.setItem(SLOT_VIEW_BACK, namedItem(Material.OAK_DOOR, lang.msg("gui.backup-view.back.name"), List.of()));
        inv.setItem(SLOT_VIEW_TOGGLE, namedItem(
                Material.ENDER_CHEST,
                lang.msg("gui.backup-view.toggle.name"),
                lang.msgList("gui.backup-view.toggle.lore")
        ));
        boolean online = Bukkit.getPlayer(targetUuid) != null;
        inv.setItem(SLOT_VIEW_RESTORE, online
                ? namedItem(Material.REDSTONE_BLOCK, lang.msg("gui.backup-view.restore.name"), lang.msgList("gui.backup-view.restore.lore"))
                : namedItem(Material.BARRIER, lang.msg("gui.backup-view.restore-offline.name"), lang.msgList("gui.backup-view.restore-offline.lore")));
        renderBackupViewExperienceItem(inv, holder);
        inv.setItem(SLOT_VIEW_PENDING, namedItem(Material.CHEST, lang.msg("gui.backup-view.pending.name"), lang.msgList("gui.backup-view.pending.lore")));
        String lockedText = lang.raw(locked ? "common.yes_text" : "common.no_text");
        String noteText = note == null || note.isBlank() ? lang.raw("common.none") : note;
        inv.setItem(
                SLOT_VIEW_LOCK,
                namedItem(
                        Material.TRIPWIRE_HOOK,
                        lang.msg("gui.backup-view.lock.name"),
                        lang.msgList(
                                "gui.backup-view.lock.lore",
                                Placeholder.unparsed("locked", lockedText),
                                Placeholder.unparsed("note", noteText),
                                Placeholder.unparsed("world", displayWorld(holder.worldName())),
                                Placeholder.unparsed("position", displayPosition(holder.locationX(), holder.locationY(), holder.locationZ()))
                        )
                )
        );
        return inv;
    }

    private void renderBackupViewExperienceItem(Inventory inv, BackupViewHolder holder) {
        if (inv == null || holder == null) {
            return;
        }

        Lang lang = plugin.lang();
        if (!holder.parts().hasExperienceData()) {
            inv.setItem(
                    SLOT_VIEW_EXPERIENCE,
                    namedItem(
                            Material.GLASS_BOTTLE,
                            lang.msg("gui.backup-view.experience-unavailable.name"),
                            lang.msgList("gui.backup-view.experience-unavailable.lore")
                    )
            );
            return;
        }

        inv.setItem(
                SLOT_VIEW_EXPERIENCE,
                namedItem(
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

    private void openRestoreConfirm(Player admin, BackupViewHolder holder, RestoreConfirmHolder.RestoreKind kind) {
        runOnPlayer(admin, () -> {
            String titleName = holder.targetName() == null ? holder.targetUuid().toString() : holder.targetName();
            Lang lang = plugin.lang();
            RestoreConfirmHolder confirmHolder = new RestoreConfirmHolder(
                    holder.targetUuid(),
                    titleName,
                    holder.backupId(),
                    holder.listPage(),
                    holder.listQuery(),
                    holder.view(),
                    kind,
                    holder.worldName(),
                    holder.locationX(),
                    holder.locationY(),
                    holder.locationZ(),
                    holder.parts().experienceLevel(),
                    holder.parts().experienceProgress(),
                    holder.parts().totalExperience()
            );
            String titleKey = kind == RestoreConfirmHolder.RestoreKind.EXPERIENCE
                    ? "gui.restore-confirm.experience.title"
                    : "gui.restore-confirm.title";
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
            Component title = lang.msgNoPrefix(titleKey, Placeholder.unparsed("target", titleName));
            Inventory inv = Bukkit.createInventory(
                    confirmHolder,
                    CONFIRM_GUI_SIZE,
                    title
            );
            confirmHolder.setInventory(inv);

            inv.setItem(CONFIRM_OK, namedItem(Material.GREEN_CONCRETE, lang.msg(okNameKey), lang.msgList(okLoreKey)));
            inv.setItem(CONFIRM_INFO, namedItem(
                    Material.PAPER,
                    lang.msg(infoNameKey),
                    lang.msgList(
                            infoLoreKey,
                            Placeholder.unparsed("target", titleName),
                            Placeholder.unparsed("id", holder.backupId()),
                            Placeholder.unparsed("world", displayWorld(holder.worldName())),
                            Placeholder.unparsed("position", displayPosition(holder.locationX(), holder.locationY(), holder.locationZ())),
                            Placeholder.unparsed("level", String.valueOf(holder.parts().experienceLevel())),
                            Placeholder.unparsed("progress", displayExperienceProgress(holder.parts().experienceProgress())),
                            Placeholder.unparsed("total", String.valueOf(holder.parts().totalExperience()))
                    )
            ));
            inv.setItem(CONFIRM_CANCEL, namedItem(Material.RED_CONCRETE, lang.msg("gui.restore-confirm.cancel.name"), lang.msgList("gui.restore-confirm.cancel.lore")));

            openMenu(admin, inv, title);
        });
    }

    private void restoreOriginalSlot(Player admin, BackupViewHolder holder, SlotType slotType, int slotIndex) {
        Inventory inv = holder.getInventory();
        if (inv == null || !isViewing(admin, inv)) {
            return;
        }

        if (slotType == SlotType.INV) {
            if (slotIndex < 0 || slotIndex >= SnapshotCodec.INVENTORY_SLOT_COUNT) {
                return;
            }
            if (holder.claimedInv()[slotIndex]) {
                Lang lang = plugin.lang();
                inv.setItem(slotIndex, namedItem(
                        Material.BARRIER,
                        lang.msg("gui.backup-view.claimed.name"),
                        lang.msgList("gui.backup-view.claimed.lore")
                ));
                syncIfViewing(admin, inv);
                return;
            }
            byte[] itemBytes = holder.parts().inventorySlotBytes()[slotIndex];
            inv.setItem(slotIndex, toPreviewItem(itemBytes));
            syncIfViewing(admin, inv);
            return;
        }

        if (slotIndex < 0 || slotIndex >= SnapshotCodec.ENDER_CHEST_SLOT_COUNT) {
            return;
        }
        if (holder.claimedEnder()[slotIndex]) {
            Lang lang = plugin.lang();
            inv.setItem(slotIndex, namedItem(
                    Material.BARRIER,
                    lang.msg("gui.backup-view.claimed.name"),
                    lang.msgList("gui.backup-view.claimed.lore")
            ));
            syncIfViewing(admin, inv);
            return;
        }
        byte[] itemBytes = holder.parts().enderChestSlotBytes()[slotIndex];
        inv.setItem(slotIndex, toPreviewItem(itemBytes));
        syncIfViewing(admin, inv);
    }

    /**
     * 预览用物品: 只用于 GUI 展示, 不影响真实投递/恢复的数据
     * 不对物品文本样式做任何强制处理, 保持物品本身的显示效果
     */
    private static ItemStack toPreviewItem(byte[] itemBytes) {
        if (itemBytes == null) {
            return null;
        }

        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(itemBytes);
        } catch (Exception ignored) {
            return null;
        }

        if (item == null || item.getType().isAir()) {
            return item;
        }

        return item;
    }

    private void beginBackupIdSearch(Player admin, BackupListHolder holder) {
        if (admin == null || holder == null) {
            return;
        }
        backupIdSearchSessions.put(
                admin.getUniqueId(),
                new BackupIdSearchSession(holder.targetUuid(), holder.targetName(), holder.page(), holder.query())
        );
        closeMenu(admin);
        Lang lang = plugin.lang();
        Chat.info(
                admin,
                "info.search-backup-id-prompt",
                Placeholder.unparsed("cancel", cancelKeywordDisplay(lang))
        );
    }

    public boolean handleBackupIdSearchChat(Player admin, String message) {
        if (admin == null) {
            return false;
        }
        BackupIdSearchSession session = backupIdSearchSessions.remove(admin.getUniqueId());
        if (session == null) {
            return false;
        }

        Lang lang = plugin.lang();
        String input = message == null ? "" : message.trim();
        if (isCancelInput(lang, input)) {
            runOnPlayer(admin, () -> openBackupList(admin, session.targetUuid(), session.targetName(), session.page(), session.query()));
            return true;
        }

        if (input.isBlank()) {
            runOnPlayer(admin, () -> {
                Chat.warn(
                        admin,
                        "warn.search-backup-id-empty",
                        Placeholder.unparsed("cancel", cancelKeywordDisplay(lang))
                );
                openBackupList(admin, session.targetUuid(), session.targetName(), session.page(), session.query());
            });
            return true;
        }

        runOnPlayer(admin, () -> openBackupView(
                admin,
                session.targetUuid(),
                session.targetName(),
                session.page(),
                session.query(),
                input,
                GuiView.INVENTORY
        ));
        return true;
    }

    public void cancelBackupIdSearch(UUID adminUuid) {
        if (adminUuid == null) {
            return;
        }
        backupIdSearchSessions.remove(adminUuid);
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

    private String timeFilterDisplayValue(Lang lang, BackupQuery query) {
        GuiTimeFilterOption window = resolveTimeFilterWindow(query, timeFilters());
        return window.displayText(lang);
    }

    private GuiTimeFilterOption resolveTimeFilterWindow(BackupQuery query) {
        return resolveTimeFilterWindow(query, timeFilters());
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
        long diff = now - query.createdAfterMillis();
        if (diff < 0) {
            diff = 0;
        }

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

    private static Component backupListTitle(Lang lang, String targetName, int page) {
        if (lang == null) {
            return Component.empty();
        }
        String safeTarget = targetName == null ? "-" : targetName;
        int safePage = Math.max(0, page);
        return lang.msgNoPrefix(
                "gui.backup-list.title",
                Placeholder.unparsed("target", safeTarget),
                Placeholder.unparsed("page", String.valueOf(safePage + 1))
        );
    }

    private static Component backupViewTitle(Lang lang, String targetName) {
        if (lang == null) {
            return Component.empty();
        }
        String safeTarget = targetName == null ? "-" : targetName;
        return lang.msgNoPrefix("gui.backup-view.title", Placeholder.unparsed("target", safeTarget));
    }

    private String displayWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return plugin.lang().raw("common.none");
        }
        var config = plugin.pluginConfig();
        if (config == null) {
            return worldName;
        }
        return config.displayWorldName(worldName);
    }

    private String displayPosition(Double x, Double y, Double z) {
        if (x == null || y == null || z == null) {
            return plugin.lang().raw("common.none");
        }
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", x, y, z);
    }

    private String displayExperienceProgress(float progress) {
        return String.format(Locale.ROOT, "%.1f%%", Math.max(0.0f, progress) * 100.0f);
    }

    private Inventory createLoading(Component title) {
        Lang lang = plugin.lang();
        LoadingHolder holder = new LoadingHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(13, namedItem(
                Material.CLOCK,
                lang.msg("gui.loading.item-name"),
                lang.msgList("gui.loading.item-lore")
        ));
        return inv;
    }

    private static ItemStack namedItem(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore == null || lore.isEmpty()) {
                meta.lore(null);
            } else {
                meta.lore(new ArrayList<>(lore));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String cancelKeywordDisplay(Lang lang) {
        if (lang != null) {
            for (String keyword : lang.rawList("common.cancel_keywords")) {
                if (keyword != null && !keyword.isBlank()) {
                    return keyword.trim();
                }
            }
        }
        return "cancel";
    }

    private static boolean isCancelInput(Lang lang, String input) {
        if (input == null) {
            return false;
        }
        String safe = input.trim();
        if (safe.isEmpty()) {
            return false;
        }

        List<String> keywords = lang == null ? List.of() : lang.rawList("common.cancel_keywords");
        if (keywords.isEmpty()) {
            return safe.equalsIgnoreCase("cancel") || safe.equalsIgnoreCase("取消");
        }

        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String k = keyword.trim();
            if (k.isEmpty()) {
                continue;
            }
            if (safe.equalsIgnoreCase(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuiHolder(Object holder) {
        return holder instanceof BackupListHolder
                || holder instanceof BackupViewHolder
                || holder instanceof RestoreConfirmHolder
                || holder instanceof LoadingHolder;
    }

    /**
     * 打开 GUI
     *
     * <p>优先使用 PacketGuiManager, 没有 ProtocolLib 时自动使用 Bukkit Inventory GUI
     */
    private void openMenu(Player player, Inventory inventory, Component title) {
        if (player == null || inventory == null || title == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.openMenu(player, inventory, title);
            return;
        }

        try {
            player.openInventory(inventory);
        } catch (Exception ignored) {
        }
    }

    /**
     * 关闭当前正在查看的插件 GUI
     *
     * <p>只会关闭本插件的 GUI, 避免误关玩家正在使用的其他容器
     */
    private void closeMenu(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.closeMenu(player);
            return;
        }

        try {
            InventoryView view = player.getOpenInventory();
            Inventory top = view == null ? null : view.getTopInventory();
            if (top == null || !isGuiHolder(top.getHolder())) {
                return;
            }
            player.closeInventory();
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断玩家是否正在查看指定 GUI 实例
     *
     * <p>Packet GUI 使用会话匹配, Bukkit GUI 使用当前打开的 TopInventory 引用匹配
     */
    private boolean isViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return false;
        }
        if (!player.isOnline()) {
            return false;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            return manager.isViewing(player, inventory);
        }

        try {
            InventoryView view = player.getOpenInventory();
            if (view == null) {
                return false;
            }
            return view.getTopInventory() == inventory;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 获取玩家当前 GUI 标题
     *
     * <p>用于原地刷新时判断标题是否需要变化, 标题变化时会重新打开 GUI
     */
    private Component currentTitle(Player player) {
        if (player == null) {
            return null;
        }
        if (!player.isOnline()) {
            return null;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            return manager.currentTitle(player);
        }

        try {
            InventoryView view = player.getOpenInventory();
            return view == null ? null : view.title();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 同步界面内容
     *
     * <p>Packet GUI 会重发窗口内容, Bukkit GUI 使用 updateInventory 刷新客户端显示
     */
    private void syncIfViewing(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }

        PacketGuiManager manager = packetGuiManager;
        if (manager != null) {
            manager.syncIfViewing(player, inventory);
            return;
        }

        if (!isViewing(player, inventory)) {
            return;
        }
        try {
            player.updateInventory();
        } catch (Exception ignored) {
        }
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
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

    private static ItemStack[] cloneStorage(ItemStack[] storageContents) {
        ItemStack[] copy = new ItemStack[storageContents.length];
        for (int i = 0; i < storageContents.length; i++) {
            ItemStack item = storageContents[i];
            copy[i] = item == null ? null : item.clone();
        }
        return copy;
    }
}
