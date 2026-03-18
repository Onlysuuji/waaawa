package org.example2.solips;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

public final class
ObservationRecord {
    private final Item item;
    private final int bookshelves;
    private final int[] costs;
    private final int[] clueIds;
    private final int[] clueLevels;
    private final String key;
    private final String costKey;

    public ObservationRecord(Item item, int bookshelves, int[] costs, int[] clueIds, int[] clueLevels) {
        this.item = item;
        this.bookshelves = bookshelves;
        this.costs = Arrays.copyOf(costs, 3);
        this.clueIds = Arrays.copyOf(clueIds, 3);
        this.clueLevels = Arrays.copyOf(clueLevels, 3);
        this.key = buildKey(item, bookshelves, costs, clueIds, clueLevels);
        this.costKey = buildCostKey(bookshelves, costs);
    }

    public Item getItem() {
        return item;
    }

    public ItemStack createStack() {
        return new ItemStack(item);
    }

    public int getBookshelves() {
        return bookshelves;
    }

    public int[] getCosts() {
        return Arrays.copyOf(costs, 3);
    }

    public int[] getClueIds() {
        return Arrays.copyOf(clueIds, 3);
    }

    public int[] getClueLevels() {
        return Arrays.copyOf(clueLevels, 3);
    }

    public String getKey() {
        return key;
    }

    public String getCostKey() {
        return costKey;
    }

    public static String buildKey(Item item, int bookshelves, int[] costs, int[] clueIds, int[] clueLevels) {
        return item + "|" + bookshelves + "|"
                + costs[0] + "," + costs[1] + "," + costs[2] + "|"
                + clueIds[0] + "," + clueIds[1] + "," + clueIds[2] + "|"
                + clueLevels[0] + "," + clueLevels[1] + "," + clueLevels[2];
    }

    public static String buildCostKey(int bookshelves, int[] costs) {
        return bookshelves + "|" + costs[0] + "," + costs[1] + "," + costs[2];
    }
}
