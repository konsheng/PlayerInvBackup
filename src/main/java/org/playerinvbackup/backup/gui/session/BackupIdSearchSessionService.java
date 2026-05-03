package org.playerinvbackup.backup.gui.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.gui.BackupGuiMode;
import org.playerinvbackup.backup.gui.holder.BackupListHolder;
import org.playerinvbackup.backup.gui.list.BackupListController;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.view.BackupViewController;
import org.playerinvbackup.backup.text.Chat;
import org.playerinvbackup.backup.text.Lang;
import org.bukkit.entity.Player;

/**
 * 备份编号搜索会话服务
 */
public final class BackupIdSearchSessionService {
    private final PlayerInvBackupPlugin plugin;
    private final GuiPlatformBridge platformBridge;
    private final BackupListController listController;
    private final BackupViewController viewController;
    private final Map<UUID, BackupIdSearchSession> sessions = new ConcurrentHashMap<>();

    public BackupIdSearchSessionService(
            PlayerInvBackupPlugin plugin,
            GuiPlatformBridge platformBridge,
            BackupListController listController,
            BackupViewController viewController
    ) {
        this.plugin = plugin;
        this.platformBridge = platformBridge;
        this.listController = listController;
        this.viewController = viewController;
    }

    public void beginSearch(Player admin, BackupListHolder holder) {
        if (admin == null || holder == null) {
            return;
        }
        sessions.put(
                admin.getUniqueId(),
                new BackupIdSearchSession(holder.targetUuid(), holder.targetName(), holder.page(), holder.query(), holder.guiMode())
        );
        platformBridge.closeMenu(admin);
        Lang lang = plugin.lang();
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

        Lang lang = plugin.lang();
        String input = message == null ? "" : message.trim();
        if (isCancelInput(lang, input)) {
            runOnPlayer(admin, () -> listController.openBackupList(
                    admin,
                    session.targetUuid(),
                    session.targetName(),
                    session.page(),
                    session.query(),
                    session.guiMode()
            ));
            return true;
        }

        if (input.isBlank()) {
            runOnPlayer(admin, () -> {
                Chat.warn(
                        admin,
                        "warn.search-backup-id-empty",
                        Placeholder.unparsed("cancel", cancelKeywordDisplay(lang))
                );
                listController.openBackupList(
                        admin,
                        session.targetUuid(),
                        session.targetName(),
                        session.page(),
                        session.query(),
                        session.guiMode()
                );
            });
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

    public void cancel(UUID adminUuid) {
        if (adminUuid == null) {
            return;
        }
        sessions.remove(adminUuid);
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

    private record BackupIdSearchSession(
            UUID targetUuid,
            String targetName,
            int page,
            org.playerinvbackup.backup.store.BackupQuery query,
            BackupGuiMode guiMode
    ) {
    }
}
