package org.playerinvbackup.backup.domain;

/**
 * 一份完整备份记录
 *
 * <p>包含元数据与二进制快照内容
 */
public record BackupRecord(
        BackupMeta meta,
        byte[] snapshotBytes
) {
}
