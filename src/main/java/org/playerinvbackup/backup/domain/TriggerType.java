package org.playerinvbackup.backup.domain;

/**
 * 备份触发类型
 *
 * <p>用于记录备份产生的原因, 并在列表/筛选中展示
 */
public enum TriggerType {
    TIMER("trigger.timer"),
    MANUAL("trigger.manual"),
    JOIN("trigger.join"),
    QUIT("trigger.quit"),
    DEATH("trigger.death"),
    WORLD_CHANGE("trigger.world-change");

    private final String langKey;

    TriggerType(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }
}
