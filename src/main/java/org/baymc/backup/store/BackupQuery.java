package org.baymc.backup.store;

import org.baymc.backup.domain.TriggerType;

/**
 * 备份查询条件
 *
 * <p>用于列表筛选(触发类型, 时间范围等)
 */
public record BackupQuery(
        TriggerType trigger,
        long createdAfterMillis
) {
    public static BackupQuery all() {
        return new BackupQuery(null, 0L);
    }
}
