package org.playerinvbackup.backup.gui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnderChestPageMapperTest {
    @Test
    void pageCountUsesFortyFiveDisplaySlots() {
        assertEquals(0, EnderChestPageMapper.maxPage(0));
        assertEquals(0, EnderChestPageMapper.maxPage(27));
        assertEquals(0, EnderChestPageMapper.maxPage(45));
        assertEquals(1, EnderChestPageMapper.maxPage(46));
        assertEquals(1, EnderChestPageMapper.maxPage(54));

        assertFalse(EnderChestPageMapper.hasMultiplePages(45));
        assertTrue(EnderChestPageMapper.hasMultiplePages(46));
    }

    @Test
    void displaySlotsMapWithinCurrentPage() {
        assertEquals(0, EnderChestPageMapper.displaySlotToRealSlot(0, 0, 54));
        assertEquals(44, EnderChestPageMapper.displaySlotToRealSlot(0, 44, 54));
        assertEquals(45, EnderChestPageMapper.displaySlotToRealSlot(1, 0, 54));
        assertEquals(53, EnderChestPageMapper.displaySlotToRealSlot(1, 8, 54));

        assertEquals(-1, EnderChestPageMapper.displaySlotToRealSlot(1, 9, 54));
        assertEquals(-1, EnderChestPageMapper.displaySlotToRealSlot(0, 45, 54));
    }

    @Test
    void realSlotsOnlyMapWhenVisibleOnPage() {
        assertEquals(44, EnderChestPageMapper.realSlotToDisplaySlot(0, 44, 54));
        assertEquals(-1, EnderChestPageMapper.realSlotToDisplaySlot(0, 45, 54));
        assertEquals(0, EnderChestPageMapper.realSlotToDisplaySlot(1, 45, 54));
        assertEquals(8, EnderChestPageMapper.realSlotToDisplaySlot(1, 53, 54));

        assertEquals(-1, EnderChestPageMapper.realSlotToDisplaySlot(1, -1, 54));
        assertEquals(-1, EnderChestPageMapper.realSlotToDisplaySlot(1, 54, 54));
    }
}
