package org.example2.solips;

import net.minecraft.world.item.Item;

import java.util.Arrays;

public final class ObservedEnchantState {

    private static boolean valid = false;
    private static Item item;
    private static int bookshelves;
    private static final int[] costs = new int[3];
    private static final int[] clueIds = new int[3];
    private static final int[] clueLevels = new int[3];

    private ObservedEnchantState() {}

    public static void clear() {
        valid = false;
        item = null;
        bookshelves = 0;
        Arrays.fill(costs, 0);
        Arrays.fill(clueIds, -1);
        Arrays.fill(clueLevels, 0);
    }

    public static void set(Item newItem, int newBookshelves, int[] newCosts, int[] newClueIds, int[] newClueLevels) {
        valid = true;
        item = newItem;
        bookshelves = newBookshelves;
        System.arraycopy(newCosts, 0, costs, 0, 3);
        System.arraycopy(newClueIds, 0, clueIds, 0, 3);
        System.arraycopy(newClueLevels, 0, clueLevels, 0, 3);
    }

    public static boolean isValid() {
        return valid;
    }

    public static Item getItem() {
        return item;
    }

    public static int getBookshelves() {
        return bookshelves;
    }

    public static int[] getCosts() {
        return Arrays.copyOf(costs, 3);
    }

    public static int[] getClueIds() {
        return Arrays.copyOf(clueIds, 3);
    }

    public static int[] getClueLevels() {
        return Arrays.copyOf(clueLevels, 3);
    }

    public static ObservationRecord snapshot() {
        if (!valid || item == null) {
            return null;
        }
        return new ObservationRecord(item, bookshelves, costs, clueIds, clueLevels);
    }
}