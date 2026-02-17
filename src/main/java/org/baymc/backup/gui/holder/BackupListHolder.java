package org.baymc.backup.gui.holder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.baymc.backup.domain.BackupMeta;
import org.baymc.backup.store.BackupQuery;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 备份列表界面的 holder
 *
 * <p>用于在 GUI 刷新与点击处理时保存上下文(目标玩家, 页码, 筛选条件, 以及异步刷新序列号)
 */
public final class BackupListHolder implements InventoryHolder {
    private final UUID targetUuid;
    private final String targetName;
    // 用于原地刷新时的序列号, 避免异步加载结果乱序覆盖当前界面
    private final AtomicLong refreshSeq = new AtomicLong();
    private int page;
    private BackupQuery query;
    private List<BackupMeta> backups;
    private Inventory inventory;

    public BackupListHolder(UUID targetUuid, String targetName, int page, BackupQuery query, List<BackupMeta> backups) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.page = Math.max(0, page);
        this.query = query == null ? BackupQuery.all() : query;
        this.backups = backups == null ? List.of() : List.copyOf(backups);
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(0, page);
    }

    public BackupQuery query() {
        return query;
    }

    public void setQuery(BackupQuery query) {
        this.query = query == null ? BackupQuery.all() : query;
    }

    public List<BackupMeta> backups() {
        return backups;
    }

    public void setBackups(List<BackupMeta> backups) {
        this.backups = backups == null ? List.of() : List.copyOf(backups);
    }

    public long nextRefreshSeq() {
        return refreshSeq.incrementAndGet();
    }

    public boolean isRefreshSeqCurrent(long seq) {
        return refreshSeq.get() == seq;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
