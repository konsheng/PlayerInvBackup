package org.playerinvbackup.backup.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 背包工具方法
 *
 * <p>用于将物品尽可能合并并插入主背包(36 格), 常用于待投递物品发放
 */
public final class InventoryUtil {
    private InventoryUtil() {
    }

    public static boolean tryInsertIntoStorage(PlayerInventory inventory, ItemStack toInsert) {
        if (toInsert == null || toInsert.getType().isAir() || toInsert.getAmount() <= 0) {
            return true;
        }

        int remaining = toInsert.getAmount();
        int max = toInsert.getMaxStackSize();

        ItemStack[] current = inventory.getStorageContents();
        ItemStack[] next = new ItemStack[current.length];
        for (int i = 0; i < current.length; i++) {
            next[i] = cloneItem(current[i]);
        }

        for (int i = 0; i < next.length; i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = next[i];
            if (existing == null || existing.getType() == Material.AIR) {
                continue;
            }
            if (!existing.isSimilar(toInsert)) {
                continue;
            }
            int space = Math.max(0, Math.min(max, existing.getMaxStackSize()) - existing.getAmount());
            if (space <= 0) {
                continue;
            }
            int add = Math.min(space, remaining);
            existing.setAmount(existing.getAmount() + add);
            remaining -= add;
        }

        for (int i = 0; i < next.length; i++) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = next[i];
            if (existing != null && existing.getType() != Material.AIR) {
                continue;
            }
            int add = Math.min(max, remaining);
            ItemStack placed = toInsert.clone();
            placed.setAmount(add);
            next[i] = placed;
            remaining -= add;
        }

        if (remaining > 0) {
            return false;
        }

        inventory.setStorageContents(next);
        return true;
    }

    private static ItemStack cloneItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        if (item.getType().isAir()) {
            return null;
        }
        return item.clone();
    }
}
