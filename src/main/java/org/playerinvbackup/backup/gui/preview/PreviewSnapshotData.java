package org.playerinvbackup.backup.gui.preview;

/**
 * 详情页预览所需的纯数据
 */
public record PreviewSnapshotData(
        boolean[] claimedInv,
        boolean[] claimedEnder,
        boolean[] incompatibleInv,
        boolean[] incompatibleEnder,
        boolean incompatibleClaimBlocksWholeBackup
) {
}
