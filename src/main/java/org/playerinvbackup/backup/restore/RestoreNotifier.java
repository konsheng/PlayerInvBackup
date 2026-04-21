package org.playerinvbackup.backup.restore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 统一处理恢复流程里的消息发送
 *
 * <p>这里不做业务判断, 只负责把 RestoreService 已经决定好的消息发给 actor 或 target
 * 这样主流程可以少掉大量分散的 Chat.info, Chat.warn, Chat.error 调用
 */
final class RestoreNotifier {
    private final PlayerInvBackupPlugin plugin;

    RestoreNotifier(PlayerInvBackupPlugin plugin) {
        this.plugin = plugin;
    }

    void showRestoreLoading(CommandSender actor) {
        Chat.info(actor, "info.restoring-loading");
    }

    void showExperienceRestoreLoading(CommandSender actor) {
        Chat.info(actor, "info.restoring-experience-loading");
    }

    void showStoreUnavailable(CommandSender actor) {
        Chat.error(actor, "errors.store-unavailable", Placeholder.unparsed("label", "pib"));
    }

    void showBackupNotFound(CommandSender actor, String backupId) {
        Chat.error(actor, "errors.backup-not-found", Placeholder.unparsed("backup_id", backupId));
    }

    void showReadFailed(CommandSender actor) {
        Chat.error(actor, "errors.read-failed");
    }

    void showSnapshotHashMismatch(CommandSender actor, String backupId, String expectedSha256, String actualSha256) {
        Chat.error(
                actor,
                "errors.snapshot-hash-mismatch",
                Placeholder.unparsed("backup_id", backupId),
                Placeholder.unparsed("expected", expectedSha256),
                Placeholder.unparsed("actual", actualSha256)
        );
    }

    void showSnapshotInvalid(CommandSender actor) {
        Chat.error(actor, "errors.snapshot-invalid");
    }

    void showBackupExperienceUnavailable(CommandSender actor) {
        Chat.error(actor, "errors.backup-experience-unavailable");
    }

    void showTargetOffline(CommandSender actor) {
        Chat.error(actor, "errors.target-offline");
    }

    void showPreRestoreBackupFailed(CommandSender actor, String errorKey) {
        Chat.error(actor, errorKey);
    }

    void showPreRestoreBackupReady(CommandSender actor, String preRestoreBackupId) {
        Chat.info(
                actor,
                "info.restore-pre-backup-success",
                Placeholder.component("backup_id", createCopyableBackupId(preRestoreBackupId))
        );
    }

    void showRestoreRunning(CommandSender actor) {
        Chat.info(actor, "info.restore-running");
    }

    void showExperienceRestoreRunning(CommandSender actor) {
        Chat.info(actor, "info.restore-experience-running");
    }

    void showRestoreSuccess(CommandSender actor) {
        Chat.success(actor, "success.restore-success");
    }

    void showExperienceRestoreSuccess(CommandSender actor) {
        Chat.success(actor, "success.restore-experience-success");
    }

    void showTargetRestoreNotice(Player target) {
        Chat.warn(target, "warn.restored-notify-target");
    }

    void showTargetExperienceRestoreNotice(Player target) {
        Chat.warn(target, "warn.restored-experience-notify-target");
    }

    private Component createCopyableBackupId(String backupId) {
        return Component.text(backupId)
                .clickEvent(ClickEvent.copyToClipboard(backupId))
                .hoverEvent(HoverEvent.showText(plugin.lang().msg("success.restore-pre-backup-copy-hover")));
    }
}
