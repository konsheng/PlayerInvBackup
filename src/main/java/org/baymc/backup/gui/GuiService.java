package org.baymc.backup.gui;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.baymc.backup.BayMcBackUpPlugin;
import org.baymc.backup.Permissions;
import org.baymc.backup.codec.SnapshotCodec;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.domain.BackupRecord;
import org.baymc.backup.domain.SlotClaim;
import org.baymc.backup.domain.SlotType;
import org.baymc.backup.domain.SnapshotParts;
import org.baymc.backup.domain.TriggerType;
import org.baymc.backup.domain.UndeliveredClaim;
import org.baymc.backup.gui.holder.BackupListHolder;
import org.baymc.backup.gui.holder.BackupViewHolder;
import org.baymc.backup.gui.holder.LoadingHolder;
import org.baymc.backup.gui.holder.RestoreConfirmHolder;
import org.baymc.backup.gui.packet.PacketGuiManager;
import org.baymc.backup.restore.RestoreService;
import org.baymc.backup.store.BackupQuery;
import org.baymc.backup.store.BackupStore;
import org.baymc.backup.text.Chat;
import org.baymc.backup.text.Lang;
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
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String MAIN_LABEL = "bmbackup";

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
    private static final int SLOT_VIEW_LOCK = 52;
    private static final int SLOT_VIEW_PENDING = 53;

    private static final int CONFIRM_GUI_SIZE = 27;
    private static final int CONFIRM_OK = 11;
    private static final int CONFIRM_INFO = 13;
    private static final int CONFIRM_CANCEL = 15;

    private final BayMcBackUpPlugin plugin;
    private final RestoreService restoreService;
    private PacketGuiManager packetGuiManager;
    private final Map<UUID, BackupIdSearchSession> backupIdSearchSessions = new ConcurrentHashMap<>();

    public GuiService(BayMcBackUpPlugin plugin, RestoreService restoreService) {
        this.plugin = plugin;
        this.restoreService = restoreService;
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

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        UUID adminUuid = admin.getUniqueId();
        String adminName = admin.getName();
        Lang lang = plugin.lang();
        plugin.auditService().log(
                "OPEN_LIST",
                admin,
                targetUuid,
                targetName,
                null,
                "page=" + page
        );
        Component loadingTitle = lang.msg("gui.backup-list.loading-title");
        runOnPlayer(admin, () -> {
            openMenu(admin, createLoading(loadingTitle), loadingTitle);
        });

        int limit = plugin.pluginConfig().guiListPageSize();
        int offset = Math.max(0, page) * limit;

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<BackupMeta> backups;
            try {
                backups = store.listBackups(targetUuid, offset, limit);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.list-load-failed",
                                Placeholder.unparsed("actor", adminName),
                                Placeholder.unparsed("actor_uuid", adminUuid.toString()),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("page", String.valueOf(page))
                        ),
                        e
                );
                runOnPlayer(admin, () -> {
                    closeMenu(admin);
                    Chat.error(admin, "errors.load-failed");
                });
                return;
            }

            int safePage = Math.max(0, page);
            if (backups.isEmpty() && safePage > 0) {
                runOnPlayer(admin, () -> {
                    Chat.warn(admin, "warn.no-more-backups-back");
                    openBackupList(admin, targetUuid, targetName, safePage - 1);
                });
                return;
            }

            boolean hasNextPage = backups.size() >= limit;
            runOnPlayer(admin, () -> {
                Inventory inv = createBackupListInventory(targetUuid, targetName, safePage, backups, hasNextPage);
                String name = targetName == null ? targetUuid.toString() : targetName;
                Component title = backupListTitle(plugin.lang(), name, safePage);
                openMenu(admin, inv, title);
            });
        });
    }

    public void openBackupList(Player admin, UUID targetUuid, String targetName, int page, BackupQuery query) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        UUID adminUuid = admin.getUniqueId();
        String adminName = admin.getName();
        Lang lang = plugin.lang();
        BackupQuery safeQuery = query == null ? BackupQuery.all() : query;

        plugin.auditService().log(
                "OPEN_LIST",
                admin,
                targetUuid,
                targetName,
                null,
                "page=" + page
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
        );
        Component loadingTitle = lang.msg("gui.backup-list.loading-title");
        runOnPlayer(admin, () -> {
            openMenu(admin, createLoading(loadingTitle), loadingTitle);
        });

        int limit = plugin.pluginConfig().guiListPageSize();
        int safePage = Math.max(0, page);
        int offset = safePage * limit;

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            List<BackupMeta> backups;
            try {
                backups = store.listBackups(targetUuid, safeQuery, offset, limit);
            } catch (Exception e) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        plugin.lang().plain(
                                "console.gui.list-load-failed-query",
                                Placeholder.unparsed("actor", adminName),
                                Placeholder.unparsed("actor_uuid", adminUuid.toString()),
                                Placeholder.unparsed("target_uuid", targetUuid.toString()),
                                Placeholder.unparsed("page", String.valueOf(page)),
                                Placeholder.unparsed("query", String.valueOf(safeQuery))
                        ),
                        e
                );
                runOnPlayer(admin, () -> {
                    closeMenu(admin);
                    Chat.error(admin, "errors.load-failed");
                });
                return;
            }

            if (backups.isEmpty() && safePage > 0) {
                runOnPlayer(admin, () -> {
                    Chat.warn(admin, "warn.no-more-backups-back");
                    openBackupList(admin, targetUuid, targetName, safePage - 1, safeQuery);
                });
                return;
            }

            boolean hasNextPage = backups.size() >= limit;
            runOnPlayer(admin, () -> {
                Inventory inv = createBackupListInventory(targetUuid, targetName, safePage, safeQuery, backups, hasNextPage);
                String name = targetName == null ? targetUuid.toString() : targetName;
                Component title = backupListTitle(plugin.lang(), name, safePage);
                openMenu(admin, inv, title);
            });
        });
    }

    // 原地刷新备份列表. 只有当标题需要变化时才重新打开 GUI
    private void refreshBackupList(Player admin, BackupListHolder holder, int page, BackupQuery query) {
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
                    Inventory top = holder.getInventory();
                    if (top == null || !isViewing(admin, top)) {
                        return;
                    }
                    if (!holder.isRefreshSeqCurrent(refreshSeq)) {
                        return;
                    }
                    Chat.warn(admin, "warn.no-more-backups-back");
                    refreshBackupList(admin, holder, safePage - 1, safeQuery);
                });
                return;
            }

            boolean hasNextPage = backups.size() >= limit;
            runOnPlayer(admin, () -> {
                Inventory top = holder.getInventory();
                if (top == null || !isViewing(admin, top)) {
                    return;
                }
                if (!holder.isRefreshSeqCurrent(refreshSeq)) {
                    return;
                }

                holder.setPage(safePage);
                holder.setQuery(safeQuery);
                holder.setBackups(backups);

                Component desiredTitle = backupListTitle(plugin.lang(), targetName, safePage);
                Component currentTitle = currentTitle(admin);
                if (!desiredTitle.equals(currentTitle)) {
                    Inventory nextInv = createBackupListInventory(targetUuid, targetName, safePage, safeQuery, backups, hasNextPage);
                    openMenu(admin, nextInv, desiredTitle);
                    return;
                }

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
        Lang lang = plugin.lang();
        BackupQuery safeQuery = listQuery == null ? BackupQuery.all() : listQuery;
        plugin.auditService().log(
                "OPEN_VIEW",
                admin,
                targetUuid,
                targetName,
                backupId,
                "view=" + view.name() + " listPage=" + listPage
                        + " trigger=" + (safeQuery.trigger() == null ? "-" : safeQuery.trigger().name())
                        + " after=" + safeQuery.createdAfterMillis()
        );
        Component loadingTitle = lang.msg("gui.backup-view.loading-title");
        runOnPlayer(admin, () -> {
            openMenu(admin, createLoading(loadingTitle), loadingTitle);
        });

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            BackupRecord record;
            List<SlotClaim> claims;
            try {
                record = store.loadBackup(targetUuid, backupId).orElse(null);
                if (record == null) {
                    runOnPlayer(admin, () -> {
                        Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        openBackupList(admin, targetUuid, targetName, Math.max(0, listPage), safeQuery);
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
                    closeMenu(admin);
                    Chat.error(admin, "errors.load-failed");
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
                    closeMenu(admin);
                    Chat.error(admin, "errors.snapshot-invalid");
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
                Inventory inv = createBackupViewInventory(
                        targetUuid,
                        targetName,
                        listPage,
                        safeQuery,
                        backupId,
                        view,
                        parts,
                        claimedInv,
                        claimedEnder,
                        record.meta().locked(),
                        record.meta().note()
                );
                String name = targetName == null ? targetUuid.toString() : targetName;
                Component title = backupViewTitle(plugin.lang(), name, view);
                openMenu(admin, inv, title);
            });
        });
    }

    public void openBackupView(Player admin, UUID targetUuid, String targetName, int listPage, String backupId, GuiView view) {
        BackupStore store = resolveStoreOrError(admin, false);
        if (store == null) {
            return;
        }

        UUID adminUuid = admin.getUniqueId();
        String adminName = admin.getName();
        Lang lang = plugin.lang();
        plugin.auditService().log(
                "OPEN_VIEW",
                admin,
                targetUuid,
                targetName,
                backupId,
                "view=" + view.name() + " listPage=" + listPage
        );
        Component loadingTitle = lang.msg("gui.backup-view.loading-title");
        runOnPlayer(admin, () -> {
            openMenu(admin, createLoading(loadingTitle), loadingTitle);
        });

        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> {
            BackupRecord record;
            List<SlotClaim> claims;
            try {
                record = store.loadBackup(targetUuid, backupId).orElse(null);
                if (record == null) {
                    runOnPlayer(admin, () -> {
                        Chat.error(admin, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
                        openBackupList(admin, targetUuid, targetName, Math.max(0, listPage));
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
                    closeMenu(admin);
                    Chat.error(admin, "errors.load-failed");
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
                    closeMenu(admin);
                    Chat.error(admin, "errors.snapshot-invalid");
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
                Inventory inv = createBackupViewInventory(
                        targetUuid,
                        targetName,
                        listPage,
                        backupId,
                        view,
                        parts,
                        claimedInv,
                        claimedEnder,
                        record.meta().locked(),
                        record.meta().note()
                );
                String name = targetName == null ? targetUuid.toString() : targetName;
                Component title = backupViewTitle(plugin.lang(), name, view);
                openMenu(admin, inv, title);
            });
        });
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
        BackupQuery query = holder.query();

        if ((slot >= 45 && slot <= 53) || slot < holder.backups().size()) {
            playGuiClick(admin);
        }

        if (slot == SLOT_LIST_PREV) {
            if (holder.page() <= 0) {
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            refreshBackupList(admin, holder, holder.page() - 1, query);
            return;
        }
        if (slot == SLOT_LIST_NEXT) {
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            refreshBackupList(admin, holder, holder.page() + 1, query);
            return;
        }

        if (slot == SLOT_LIST_TIME_FILTER) {
            BackupQuery next = nextTimeFilterQuery(query);
            refreshBackupList(admin, holder, 0, next);
            return;
        }

        if (slot == SLOT_LIST_TRIGGER_FILTER) {
            BackupQuery next = nextTriggerFilterQuery(query);
            refreshBackupList(admin, holder, 0, next);
            return;
        }

        if (slot == SLOT_LIST_CLEAR_FILTERS) {
            refreshBackupList(admin, holder, 0, BackupQuery.all());
            return;
        }

        if (slot == SLOT_LIST_SEARCH) {
            beginBackupIdSearch(admin, holder);
            return;
        }

        if (slot == SLOT_LIST_JUMP_BACK) {
            int nextPage = Math.max(0, holder.page() - 5);
            if (nextPage == holder.page()) {
                Chat.warn(admin, "errors.already-first-page");
                return;
            }
            refreshBackupList(admin, holder, nextPage, query);
            return;
        }

        if (slot == SLOT_LIST_JUMP_FORWARD) {
            int limit = plugin.pluginConfig().guiListPageSize();
            if (holder.backups().size() < limit) {
                Chat.warn(admin, "errors.no-next-page");
                return;
            }
            refreshBackupList(admin, holder, holder.page() + 5, query);
            return;
        }

        if (slot == SLOT_LIST_REFRESH) {
            refreshBackupList(admin, holder, holder.page(), query);
            return;
        }

        if (slot >= holder.backups().size()) {
            return;
        }
        BackupMeta meta = holder.backups().get(slot);
        openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.page(), query, meta.backupId(), GuiView.INVENTORY);
    }

    public void handleViewClick(Player admin, BackupViewHolder holder, int slot) {
        boolean isButton = slot == SLOT_VIEW_BACK
                || slot == SLOT_VIEW_TOGGLE
                || slot == SLOT_VIEW_RESTORE
                || slot == SLOT_VIEW_LOCK
                || slot == SLOT_VIEW_PENDING;
        if (isButton) {
            playGuiClick(admin);
        }

        if (slot == SLOT_VIEW_BACK) {
            openBackupList(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery());
            return;
        }
        if (slot == SLOT_VIEW_TOGGLE) {
            GuiView next = holder.view() == GuiView.INVENTORY ? GuiView.ENDER_CHEST : GuiView.INVENTORY;
            runOnPlayer(admin, () -> {
                Inventory inv = createBackupViewInventory(
                        holder.targetUuid(),
                        holder.targetName(),
                        holder.listPage(),
                        holder.listQuery(),
                        holder.backupId(),
                        next,
                        holder.parts(),
                        holder.claimedInv(),
                        holder.claimedEnder(),
                        holder.locked(),
                        holder.note()
                );
                String name = holder.targetName() == null ? holder.targetUuid().toString() : holder.targetName();
                Component title = backupViewTitle(plugin.lang(), name, next);
                openMenu(admin, inv, title);
            });
            return;
        }
        if (slot == SLOT_VIEW_PENDING) {
            if (!Permissions.has(admin, Permissions.PENDING)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.PENDING));
                return;
            }
            deliverPending(admin);
            return;
        }
        if (slot == SLOT_VIEW_RESTORE) {
            if (!Permissions.has(admin, Permissions.RESTORE)) {
                Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
                return;
            }
            Player target = Bukkit.getPlayer(holder.targetUuid());
            if (target == null) {
                Chat.error(admin, "errors.restore-target-offline");
                return;
            }
            openRestoreConfirm(admin, holder);
            return;
        }
        if (slot == SLOT_VIEW_LOCK) {
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
                return;
            }
            byte[] itemBytes = holder.parts().inventorySlotBytes()[slot];
            if (itemBytes == null || itemBytes.length == 0) {
                return;
            }
            playGuiClick(admin);
            tryClaimSlot(admin, holder, SlotType.INV, slot, itemBytes);
        } else {
            if (slot < 0 || slot >= SnapshotCodec.ENDER_CHEST_SLOT_COUNT) {
                return;
            }
            if (holder.claimedEnder()[slot]) {
                return;
            }
            byte[] itemBytes = holder.parts().enderChestSlotBytes()[slot];
            if (itemBytes == null || itemBytes.length == 0) {
                return;
            }
            playGuiClick(admin);
            tryClaimSlot(admin, holder, SlotType.ENDER, slot, itemBytes);
        }
    }

    public void handleRestoreConfirmClick(Player admin, RestoreConfirmHolder holder, int slot) {
        if (slot == CONFIRM_OK || slot == CONFIRM_CANCEL || slot == CONFIRM_INFO) {
            playGuiClick(admin);
        }
        if (slot == CONFIRM_CANCEL) {
            openBackupView(admin, holder.targetUuid(), holder.targetName(), holder.listPage(), holder.listQuery(), holder.backupId(), holder.returnView());
            return;
        }

        if (slot != CONFIRM_OK) {
            return;
        }

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
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(meta.createdAtMillis()));
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
                            Placeholder.unparsed("locked", lockedText),
                            Placeholder.unparsed("note", noteText)
                    )));
        }

        String timeFilterValue = timeFilterDisplayValue(lang, safeQuery);
        String triggerFilterValue = safeQuery.trigger() == null
                ? lang.raw("gui.backup-list.filter-trigger.value.all")
                : lang.raw(safeQuery.trigger().langKey());

        inv.setItem(SLOT_LIST_PREV, namedItem(Material.ARROW, lang.msg("gui.backup-list.prev.name"), List.of()));
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
                                Placeholder.unparsed("note", noteText)
                        )
                )
        );
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
                lang.msg(
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
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(meta.createdAtMillis()));
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
                            Placeholder.unparsed("locked", lockedText),
                            Placeholder.unparsed("note", noteText)
                    )));
        }

        String timeFilterValue = timeFilterDisplayValue(lang, safeQuery);
        String triggerFilterValue = safeQuery.trigger() == null
                ? lang.raw("gui.backup-list.filter-trigger.value.all")
                : lang.raw(safeQuery.trigger().langKey());

        inv.setItem(SLOT_LIST_PREV, namedItem(Material.ARROW, lang.msg("gui.backup-list.prev.name"), List.of()));
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
        BackupViewHolder holder = new BackupViewHolder(targetUuid, name, backupId, listPage, safeQuery, view, parts, claimedInv, claimedEnder, locked, note);
        Inventory inv = Bukkit.createInventory(
                holder,
                GUI_SIZE,
                view == GuiView.INVENTORY
                        ? lang.msg("gui.backup-view.title-inv", Placeholder.unparsed("target", name))
                        : lang.msg("gui.backup-view.title-ender", Placeholder.unparsed("target", name))
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
                                Placeholder.unparsed("note", noteText)
                        )
                )
        );
        return inv;
    }

    private void openRestoreConfirm(Player admin, BackupViewHolder holder) {
        runOnPlayer(admin, () -> {
            String titleName = holder.targetName() == null ? holder.targetUuid().toString() : holder.targetName();
            Lang lang = plugin.lang();
            RestoreConfirmHolder confirmHolder = new RestoreConfirmHolder(
                    holder.targetUuid(),
                    titleName,
                    holder.backupId(),
                    holder.listPage(),
                    holder.listQuery(),
                    holder.view()
            );
            Component title = lang.msg("gui.restore-confirm.title", Placeholder.unparsed("target", titleName));
            Inventory inv = Bukkit.createInventory(
                    confirmHolder,
                    CONFIRM_GUI_SIZE,
                    title
            );
            confirmHolder.setInventory(inv);

            inv.setItem(CONFIRM_OK, namedItem(Material.GREEN_CONCRETE, lang.msg("gui.restore-confirm.ok.name"), lang.msgList("gui.restore-confirm.ok.lore")));
            inv.setItem(CONFIRM_INFO, namedItem(
                    Material.PAPER,
                    lang.msg("gui.restore-confirm.info.name"),
                    lang.msgList(
                            "gui.restore-confirm.info.lore",
                            Placeholder.unparsed("target", titleName),
                            Placeholder.unparsed("id", holder.backupId())
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
        TimeFilterWindow window = resolveTimeFilterWindow(safe);
        TimeFilterWindow[] values = TimeFilterWindow.values();
        TimeFilterWindow next = values[(window.ordinal() + 1) % values.length];
        long after = next == TimeFilterWindow.ALL ? 0L : System.currentTimeMillis() - next.duration().toMillis();
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
        if (lang == null) {
            return "-";
        }
        TimeFilterWindow window = resolveTimeFilterWindow(query);
        return lang.raw(window.langKey());
    }

    private TimeFilterWindow resolveTimeFilterWindow(BackupQuery query) {
        if (query == null || query.createdAfterMillis() <= 0) {
            return TimeFilterWindow.ALL;
        }

        long now = System.currentTimeMillis();
        long diff = now - query.createdAfterMillis();
        if (diff < 0) {
            diff = 0;
        }

        TimeFilterWindow best = TimeFilterWindow.LAST_24H;
        long bestDelta = Math.abs(diff - best.duration().toMillis());

        for (TimeFilterWindow window : TimeFilterWindow.values()) {
            if (window == TimeFilterWindow.ALL) {
                continue;
            }
            long delta = Math.abs(diff - window.duration().toMillis());
            if (delta < bestDelta) {
                best = window;
                bestDelta = delta;
            }
        }
        return best;
    }

    private enum TimeFilterWindow {
        ALL(Duration.ZERO, "gui.backup-list.filter-time.value.all"),
        LAST_24H(Duration.ofHours(24), "gui.backup-list.filter-time.value.24h"),
        LAST_7D(Duration.ofDays(7), "gui.backup-list.filter-time.value.7d"),
        LAST_30D(Duration.ofDays(30), "gui.backup-list.filter-time.value.30d");

        private final Duration duration;
        private final String langKey;

        TimeFilterWindow(Duration duration, String langKey) {
            this.duration = duration;
            this.langKey = langKey;
        }

        Duration duration() {
            return duration;
        }

        String langKey() {
            return langKey;
        }
    }

    private static Component backupListTitle(Lang lang, String targetName, int page) {
        if (lang == null) {
            return Component.empty();
        }
        String safeTarget = targetName == null ? "-" : targetName;
        int safePage = Math.max(0, page);
        return lang.msg(
                "gui.backup-list.title",
                Placeholder.unparsed("target", safeTarget),
                Placeholder.unparsed("page", String.valueOf(safePage + 1))
        );
    }

    private static Component backupViewTitle(Lang lang, String targetName, GuiView view) {
        if (lang == null) {
            return Component.empty();
        }
        String safeTarget = targetName == null ? "-" : targetName;
        return view == GuiView.INVENTORY
                ? lang.msg("gui.backup-view.title-inv", Placeholder.unparsed("target", safeTarget))
                : lang.msg("gui.backup-view.title-ender", Placeholder.unparsed("target", safeTarget));
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

    private void playGuiClick(Player player) {
        var config = plugin.pluginConfig();
        if (config == null || !config.guiSoundsEnabled()) {
            return;
        }
        var effect = config.guiClickSound();
        if (effect == null || !effect.enabled()) {
            return;
        }
        runOnPlayer(player, () -> player.playSound(player.getLocation(), effect.sound(), effect.volume(), effect.pitch()));
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
