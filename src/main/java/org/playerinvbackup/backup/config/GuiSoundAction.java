package org.playerinvbackup.backup.config;

/**
 * GUI 可配置音效的交互动作
 */
public enum GuiSoundAction {
    BARRIER_SLOT("gui.barrier-slot"),
    LIST_ENTRY("gui.backup-list.entry-open"),
    LIST_PAGE_DISABLED("gui.backup-list.page-disabled"),
    LIST_PREV("gui.backup-list.prev-page"),
    LIST_NEXT("gui.backup-list.next-page"),
    LIST_FILTER_TIME("gui.backup-list.filter-time"),
    LIST_FILTER_TRIGGER("gui.backup-list.filter-trigger"),
    LIST_SEARCH("gui.backup-list.search"),
    LIST_CLEAR_FILTERS("gui.backup-list.clear-filters"),
    LIST_JUMP_BACK("gui.backup-list.jump-back"),
    LIST_JUMP_FORWARD("gui.backup-list.jump-forward"),
    LIST_REFRESH("gui.backup-list.refresh"),
    SEARCH_MODE_BY_ID("gui.search-mode.by-id"),
    SEARCH_MODE_BY_TIME("gui.search-mode.by-time"),
    SEARCH_MODE_BACK("gui.search-mode.back"),
    VIEW_BACK("gui.backup-view.back"),
    VIEW_TOGGLE("gui.backup-view.toggle"),
    VIEW_RESTORE("gui.backup-view.restore"),
    VIEW_LOCK("gui.backup-view.lock"),
    VIEW_EXPORT("gui.backup-view.export"),
    VIEW_TELEPORT("gui.backup-view.teleport"),
    VIEW_PENDING("gui.backup-view.pending"),
    VIEW_CLAIM_SLOT("gui.backup-view.claim-slot"),
    RESTORE_CONFIRM_OK("gui.restore-confirm.confirm"),
    RESTORE_CONFIRM_INFO("gui.restore-confirm.info"),
    RESTORE_CONFIRM_CANCEL("gui.restore-confirm.cancel");

    private final String configPath;

    GuiSoundAction(String configPath) {
        this.configPath = configPath;
    }

    public String configPath() {
        return configPath;
    }
}
