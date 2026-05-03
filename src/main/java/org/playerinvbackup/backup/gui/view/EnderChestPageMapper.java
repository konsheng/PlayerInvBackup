package org.playerinvbackup.backup.gui.view;

/**
 * 末影箱预览分页槽位映射
 */
public final class EnderChestPageMapper {
    public static final int PAGE_SIZE = 45;

    private EnderChestPageMapper() {
    }

    public static int maxPage(int slotCount) {
        if (slotCount <= 0) {
            return 0;
        }
        return Math.max(0, (slotCount - 1) / PAGE_SIZE);
    }

    public static boolean hasMultiplePages(int slotCount) {
        return maxPage(slotCount) > 0;
    }

    public static int clampPage(int page, int slotCount) {
        return Math.max(0, Math.min(page, maxPage(slotCount)));
    }

    public static int displaySlotToRealSlot(int page, int displaySlot, int slotCount) {
        if (displaySlot < 0 || displaySlot >= PAGE_SIZE) {
            return -1;
        }
        int realSlot = clampPage(page, slotCount) * PAGE_SIZE + displaySlot;
        return realSlot >= 0 && realSlot < slotCount ? realSlot : -1;
    }

    public static int realSlotToDisplaySlot(int page, int realSlot, int slotCount) {
        if (realSlot < 0 || realSlot >= slotCount) {
            return -1;
        }
        int start = clampPage(page, slotCount) * PAGE_SIZE;
        int displaySlot = realSlot - start;
        return displaySlot >= 0 && displaySlot < PAGE_SIZE ? displaySlot : -1;
    }
}
