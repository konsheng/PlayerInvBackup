package org.playerinvbackup.backup.gui.session;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiSoundAction;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.holder.SearchModeHolder;
import org.playerinvbackup.backup.gui.list.BackupListController;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.render.SearchModeRenderer;
import org.playerinvbackup.backup.gui.view.BackupViewController;
import org.playerinvbackup.backup.store.BackupQuery;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;

/**
 * 备份编号搜索会话服务
 */
public final class BackupIdSearchSessionService {
    private static final long SEARCH_TIMEOUT_SECONDS = 60L;

    private enum SearchKind {
        BACKUP_ID,
        TIME
    }

    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final BackupListController listController;
    private final BackupViewController viewController;
    private final SearchModeRenderer searchModeRenderer;
    private final BackupTimeSearchParser timeSearchParser;
    private final Map<UUID, BackupIdSearchSession> sessions = new ConcurrentHashMap<>();

    public BackupIdSearchSessionService(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            BackupListController listController,
            BackupViewController viewController,
            SearchModeRenderer searchModeRenderer
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.listController = listController;
        this.viewController = viewController;
        this.searchModeRenderer = searchModeRenderer;
        this.timeSearchParser = new BackupTimeSearchParser(java.time.ZoneId.systemDefault());
    }

    public void openSearchMode(Player admin, BackupListHolder holder) {
        if (admin == null || holder == null) {
            return;
        }
        SearchModeHolder searchHolder = new SearchModeHolder(
                holder.targetUuid(),
                holder.targetName(),
                holder.page(),
                holder.query(),
                holder.guiMode()
        );
        var title = searchModeRenderer.title();
        Inventory inventory = searchModeRenderer.create(searchHolder, title);
        platformBridge.openMenu(admin, inventory, title);
    }

    public void handleSearchModeClick(Player admin, SearchModeHolder holder, int slot) {
        if (admin == null || holder == null) {
            return;
        }
        if (slot == SearchModeRenderer.SLOT_SEARCH_BY_ID) {
            playGuiSound(admin, GuiSoundAction.SEARCH_MODE_BY_ID);
            beginSearch(admin, holder, SearchKind.BACKUP_ID);
            return;
        }
        if (slot == SearchModeRenderer.SLOT_SEARCH_BY_TIME) {
            playGuiSound(admin, GuiSoundAction.SEARCH_MODE_BY_TIME);
            beginSearch(admin, holder, SearchKind.TIME);
            return;
        }
        if (slot == SearchModeRenderer.SLOT_BACK) {
            playGuiSound(admin, GuiSoundAction.SEARCH_MODE_BACK);
            listController.openBackupList(
                    admin,
                    holder.targetUuid(),
                    holder.targetName(),
                    holder.page(),
                    holder.query(),
                    holder.guiMode()
            );
        }
    }

    private void beginSearch(Player admin, SearchModeHolder holder, SearchKind kind) {
        BackupIdSearchSession session = new BackupIdSearchSession(
                kind,
                holder.targetUuid(),
                holder.targetName(),
                holder.page(),
                holder.query(),
                holder.guiMode()
        );
        BackupIdSearchSession previous = sessions.put(admin.getUniqueId(), session);
        cancelTimeout(previous);

        ScheduledTask timeoutTask = Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                ignored -> expireSearch(admin, session),
                SEARCH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );
        session.timeoutTask(timeoutTask);
        if (sessions.get(admin.getUniqueId()) != session) {
            cancelTimeout(session);
        }

        platformBridge.closeMenu(admin);
        Lang lang = plugin.lang();
        if (kind == SearchKind.TIME) {
            Chat.info(admin, "info.search-backup-time-prompt", Placeholder.unparsed("cancel", cancelKeywordDisplay(lang)));
            return;
        }
        Chat.info(admin, "info.search-backup-id-prompt", Placeholder.unparsed("cancel", cancelKeywordDisplay(lang)));
    }

    public boolean handleChat(Player admin, String message) {
        if (admin == null) {
            return false;
        }
        BackupIdSearchSession session = sessions.remove(admin.getUniqueId());
        if (session == null) {
            return false;
        }
        cancelTimeout(session);

        Lang lang = plugin.lang();
        String input = message == null ? "" : message.trim();
        if (isCancelInput(lang, input)) {
            runOnPlayer(admin, () -> Chat.info(admin, "info.search-backup-cancelled"));
            return true;
        }

        if (input.isBlank()) {
            runOnPlayer(admin, () -> {
                String warnKey = session.kind() == SearchKind.TIME
                        ? "warn.search-backup-time-empty"
                        : "warn.search-backup-id-empty";
                Chat.warn(admin, warnKey, Placeholder.unparsed("cancel", cancelKeywordDisplay(lang)));
                reopenList(admin, session);
            });
            return true;
        }

        if (session.kind() == SearchKind.TIME) {
            handleTimeSearch(admin, session, input);
            return true;
        }

        runOnPlayer(admin, () -> viewController.openBackupView(
                admin,
                session.targetUuid(),
                session.targetName(),
                session.page(),
                session.query(),
                input,
                org.playerinvbackup.backup.gui.GuiView.INVENTORY,
                session.guiMode()
        ));
        return true;
    }

    private void handleTimeSearch(Player admin, BackupIdSearchSession session, String input) {
        BackupTimeSearchParser.TimeRange range;
        try {
            range = timeSearchParser.parse(input);
        } catch (IllegalArgumentException e) {
            runOnPlayer(admin, () -> {
                Chat.warn(admin, "warn.search-backup-time-invalid");
                reopenList(admin, session);
            });
            return;
        }

        BackupQuery base = session.query() == null ? BackupQuery.all() : session.query();
        BackupQuery timeQuery = new BackupQuery(base.trigger(), range.startMillis(), range.endMillis());
        runOnPlayer(admin, () -> {
            plugin.auditService().log(
                    "SEARCH_TIME",
                    admin,
                    session.targetUuid(),
                    session.targetName(),
                    null,
                    "mode=" + session.guiMode().name()
                            + " scope=" + scope(admin, session.targetUuid())
                            + " input=" + input
                            + " start=" + range.startText()
                            + " end=" + range.endText()
            );
            listController.openBackupList(
                    admin,
                    session.targetUuid(),
                    session.targetName(),
                    0,
                    timeQuery,
                    session.guiMode()
            );
        });
    }

    private void reopenList(Player admin, BackupIdSearchSession session) {
        listController.openBackupList(
                admin,
                session.targetUuid(),
                session.targetName(),
                session.page(),
                session.query(),
                session.guiMode()
        );
    }

    public void cancel(UUID adminUuid) {
        if (adminUuid == null) {
            return;
        }
        BackupIdSearchSession session = sessions.remove(adminUuid);
        cancelTimeout(session);
    }

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
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

        List<String> keywords = lang == null ? java.util.List.of() : lang.rawList("common.cancel_keywords");
        if (keywords.isEmpty()) {
            return safe.equalsIgnoreCase("cancel") || safe.equalsIgnoreCase("取消");
        }

        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty() && safe.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static String scope(Player admin, UUID targetUuid) {
        if (admin == null || targetUuid == null) {
            return "unknown";
        }
        return admin.getUniqueId().equals(targetUuid) ? "self" : "others";
    }

    private void expireSearch(Player admin, BackupIdSearchSession session) {
        if (admin == null || session == null) {
            return;
        }
        if (!sessions.remove(admin.getUniqueId(), session)) {
            return;
        }
        runOnPlayer(admin, () -> Chat.warn(admin, "warn.search-backup-timeout"));
    }

    private static void cancelTimeout(BackupIdSearchSession session) {
        if (session == null) {
            return;
        }
        ScheduledTask task = session.timeoutTask();
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
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

    private static final class BackupIdSearchSession {
        private final SearchKind kind;
        private final UUID targetUuid;
        private final String targetName;
        private final int page;
        private final BackupQuery query;
        private final BackupGuiMode guiMode;
        private volatile ScheduledTask timeoutTask;

        private BackupIdSearchSession(
                SearchKind kind,
                UUID targetUuid,
                String targetName,
                int page,
                BackupQuery query,
                BackupGuiMode guiMode
        ) {
            this.kind = kind;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.page = page;
            this.query = query;
            this.guiMode = guiMode;
        }

        private SearchKind kind() {
            return kind;
        }

        private UUID targetUuid() {
            return targetUuid;
        }

        private String targetName() {
            return targetName;
        }

        private int page() {
            return page;
        }

        private BackupQuery query() {
            return query;
        }

        private BackupGuiMode guiMode() {
            return guiMode;
        }

        private ScheduledTask timeoutTask() {
            return timeoutTask;
        }

        private void timeoutTask(ScheduledTask timeoutTask) {
            this.timeoutTask = timeoutTask;
        }
    }
}
