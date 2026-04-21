package org.playerinvbackup.backup.restore;

import org.playerinvbackup.backup.domain.SnapshotParts;
import org.bukkit.entity.Player;

/**
 * 只负责经验恢复
 *
 * <p>这里集中应用等级, 当前经验进度和总经验值
 * 不读取存储, 不做恢复前备份, 不承担消息逻辑
 */
final class ExperienceRestoreApplier {
    void apply(Player target, SnapshotParts parts) {
        target.closeInventory();
        target.setExp(0.0f);
        target.setLevel(0);
        target.setTotalExperience(0);
        target.giveExp(parts.totalExperience());
        target.setLevel(parts.experienceLevel());
        target.setExp(parts.experienceProgress());
        target.setTotalExperience(parts.totalExperience());
    }
}
