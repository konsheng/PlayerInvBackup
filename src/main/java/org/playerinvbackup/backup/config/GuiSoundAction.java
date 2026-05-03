package org.playerinvbackup.backup.config;

/**
 * GUI 可配置音效的交互动作
 */
public enum GuiSoundAction {
    BARRIER_SLOT("sounds.gui.barrier-slot"),
    LIST_ENTRY("sounds.gui.backup-list.entry-open"),
    LIST_PAGE_DISABLED("sounds.gui.backup-list.page-disabled"),
    LIST_PREV("sounds.gui.backup-list.prev-page"),
    LIST_NEXT("sounds.gui.backup-list.next-page"),
    LIST_FILTER_TIME("sounds.gui.backup-list.filter-time"),
    LIST_FILTER_TRIGGER("sounds.gui.backup-list.filter-trigger"),
    LIST_SEARCH("sounds.gui.backup-list.search"),
    LIST_CLEAR_FILTERS("sounds.gui.backup-list.clear-filters"),
    LIST_JUMP_BACK("sounds.gui.backup-list.jump-back"),
    LIST_JUMP_FORWARD("sounds.gui.backup-list.jump-forward"),
    LIST_REFRESH("sounds.gui.backup-list.refresh"),
    SEARCH_MODE_BY_ID("sounds.gui.search-mode.by-id"),
    SEARCH_MODE_BY_TIME("sounds.gui.search-mode.by-time"),
    SEARCH_MODE_BACK("sounds.gui.search-mode.back"),
    VIEW_BACK("sounds.gui.backup-view.back"),
    VIEW_TOGGLE("sounds.gui.backup-view.toggle"),
    VIEW_RESTORE("sounds.gui.backup-view.restore"),
    VIEW_LOCK("sounds.gui.backup-view.lock"),
    VIEW_EXPORT("sounds.gui.backup-view.export"),
    VIEW_PENDING("sounds.gui.backup-view.pending"),
    VIEW_CLAIM_SLOT("sounds.gui.backup-view.claim-slot"),
    RESTORE_CONFIRM_OK("sounds.gui.restore-confirm.confirm"),
    RESTORE_CONFIRM_INFO("sounds.gui.restore-confirm.info"),
    RESTORE_CONFIRM_CANCEL("sounds.gui.restore-confirm.cancel");

    private final String configPath;

    GuiSoundAction(String configPath) {
        this.configPath = configPath;
    }

    public String configPath() {
        return configPath;
    }
}
