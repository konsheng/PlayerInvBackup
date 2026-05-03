package org.playerinvbackup.backup.gui;

/**
 * GUI 访问模式
 */
public enum BackupGuiMode {
    MANAGE,
    VIEW_ONLY;

    public boolean viewOnly() {
        return this == VIEW_ONLY;
    }
}
