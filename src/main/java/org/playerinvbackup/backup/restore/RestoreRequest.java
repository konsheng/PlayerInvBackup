package org.playerinvbackup.backup.restore;

import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 一次恢复请求的稳定上下文
 *
 * <p>这里集中保存 actor, target 和 backupId 这些在整个恢复流程中都会反复使用的信息
 * 用来减少方法之间长参数列表的重复传递
 */
record RestoreRequest(
        CommandSender actor,
        String actorDetails,
        UUID targetUuid,
        String targetName,
        String backupId
) {
    static RestoreRequest of(CommandSender actor, Player target, String backupId) {
        String actorName = actor == null ? "-" : actor.getName();
        String actorDetails = actor instanceof Player p ? actorName + "(" + p.getUniqueId() + ")" : actorName;
        return new RestoreRequest(
                actor,
                actorDetails,
                target.getUniqueId(),
                target.getName(),
                backupId
        );
    }
}
