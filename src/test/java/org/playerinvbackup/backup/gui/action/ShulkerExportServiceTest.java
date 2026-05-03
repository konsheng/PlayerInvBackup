package org.playerinvbackup.backup.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 该测试文件用于验证背包导出到潜影盒时的槽位拆分规则
 * 覆盖快捷栏 装备副手和主背包三类槽位映射
 */
class ShulkerExportServiceTest {
    @Test
    void inventoryExportFirstBoxContainsHotbarArmorAndOffhand() {
        assertMapping(0, 0, 0);
        assertMapping(8, 0, 8);

        assertMapping(36, 0, 9);
        assertMapping(39, 0, 12);
        assertMapping(40, 0, 13);
    }

    @Test
    void inventoryExportSecondBoxContainsMainStorageArea() {
        assertMapping(9, 1, 0);
        assertMapping(35, 1, 26);
    }

    @Test
    void inventoryExportIgnoresOutOfRangeSlots() {
        assertNull(ShulkerExportService.mapInventoryExportSlot(-1));
        assertNull(ShulkerExportService.mapInventoryExportSlot(41));
    }

    private static void assertMapping(int sourceSlot, int boxIndex, int targetSlot) {
        ShulkerExportService.InventoryExportSlotMapping mapping =
                ShulkerExportService.mapInventoryExportSlot(sourceSlot);

        assertEquals(boxIndex, mapping.boxIndex());
        assertEquals(targetSlot, mapping.targetSlot());
    }
}
