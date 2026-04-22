package org.playerinvbackup.backup.gui.confirm;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.playerinvbackup.backup.Permissions;
import org.playerinvbackup.backup.PlayerInvBackupPlugin;
import org.playerinvbackup.backup.config.GuiSoundAction;
import org.playerinvbackup.backup.gui.holder.RestoreConfirmHolder;
import org.playerinvbackup.backup.gui.platform.GuiPlatformBridge;
import org.playerinvbackup.backup.gui.render.RestoreConfirmRenderer;
import org.playerinvbackup.backup.gui.view.BackupViewController;
import org.playerinvbackup.backup.restore.RestoreService;
import org.playerinvbackup.backup.text.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 恢复确认页点击动作
 */
public final class RestoreConfirmActions {
    private final PlayerInvBackupPlugin plugin;
    private final RestoreService restoreService;
    private final BackupViewController viewController;
    private final GuiPlatformBridge platformBridge;

    public RestoreConfirmActions(
            PlayerInvBackupPlugin plugin,
            RestoreService restoreService,
            BackupViewController viewController,
            GuiPlatformBridge platformBridge
    ) {
        this.plugin = plugin;
        this.restoreService = restoreService;
        this.viewController = viewController;
        this.platformBridge = platformBridge;
    }

    public void handleClick(Player admin, RestoreConfirmHolder holder, int slot) {
        if (slot == RestoreConfirmRenderer.CONFIRM_CANCEL) {
            playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_CANCEL);
            viewController.openBackupView(
                    admin,
                    holder.targetUuid(),
                    holder.targetName(),
                    holder.listPage(),
                    holder.listQuery(),
                    holder.backupId(),
                    holder.returnView()
            );
            return;
        }

        if (slot == RestoreConfirmRenderer.CONFIRM_INFO) {
            playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_INFO);
            return;
        }

        if (slot != RestoreConfirmRenderer.CONFIRM_OK) {
            return;
        }

        playGuiSound(admin, GuiSoundAction.RESTORE_CONFIRM_OK);
        if (!Permissions.has(admin, Permissions.RESTORE)) {
            Chat.error(admin, "errors.no-permission", Placeholder.unparsed("perm", Permissions.RESTORE));
            viewController.openBackupView(
                    admin,
                    holder.targetUuid(),
                    holder.targetName(),
                    holder.listPage(),
                    holder.listQuery(),
                    holder.backupId(),
                    holder.returnView()
            );
            return;
        }

        Player target = Bukkit.getPlayer(holder.targetUuid());
        if (target == null) {
            Chat.error(admin, "errors.restore-target-offline");
            viewController.openBackupView(
                    admin,
                    holder.targetUuid(),
                    holder.targetName(),
                    holder.listPage(),
                    holder.listQuery(),
                    holder.backupId(),
                    holder.returnView()
            );
            return;
        }

        platformBridge.closeMenu(admin);
        if (holder.kind() == RestoreConfirmHolder.RestoreKind.EXPERIENCE) {
            restoreService.restoreExperienceToPlayer(admin, target, holder.backupId());
            return;
        }
        restoreService.restoreToPlayer(admin, target, holder.backupId());
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

    private void runOnPlayer(Player player, Runnable runnable) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().run(plugin, ignored -> runnable.run(), null);
    }
}
