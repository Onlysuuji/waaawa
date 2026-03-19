package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.util.Arrays;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
public class EnchantScreenObserver {
    private static final int TABLE_SEARCH_HORIZONTAL_RADIUS = 6;
    private static final int TABLE_SEARCH_VERTICAL_RADIUS = 4;
    private static final long LOOK_HINT_MAX_AGE_TICKS = 40L;
    private static final double MAX_TABLE_DISTANCE_SQUARED = 64.0D;
    private static final int ITEM_CHANGE_REFRESH_WAIT_TICKS = 8;

    private static boolean wasInEnchantScreen = false;
    private static String pendingKey = null;
    private static int pendingTicks = 0;
    private static volatile Field reflectedAccessField;

    private static BlockPos lastLookedEnchantTablePos = null;
    private static long lastLookedEnchantTableTick = Long.MIN_VALUE;
    private static BlockPos activeEnchantTablePos = null;

    private static Item lastSeenItem = null;
    private static String lastRawMenuFingerprint = null;
    private static boolean waitingForFreshMenuAfterItemChange = false;
    private static String itemChangeBaselineMenuFingerprint = null;
    private static int itemChangeWaitTicks = 0;

    private static void resetPending() {
        pendingKey = null;
        pendingTicks = 0;
    }

    private static void resetItemChangeGuard() {
        lastSeenItem = null;
        lastRawMenuFingerprint = null;
        waitingForFreshMenuAfterItemChange = false;
        itemChangeBaselineMenuFingerprint = null;
        itemChangeWaitTicks = 0;
    }

    public static void clearClientObservationState() {
        ObservedEnchantState.clear();
        resetPending();
        resetItemChangeGuard();
        wasInEnchantScreen = false;
        activeEnchantTablePos = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (!(mc.screen instanceof EnchantmentScreen)) {
            updateTableLookHint(mc);
        }

        if (!ClientFeatureToggle.isEnabled()) {
            if (wasInEnchantScreen || pendingKey != null || ObservedEnchantState.snapshot() != null) {
                clearClientObservationState();
            }
            return;
        }

        Integer currentEnchantSeed = getAuthoritativeEnchantSeed(mc);
        if (currentEnchantSeed != null) {
            boolean reset = SeedCrackState.updateEnchantSeedAndCheckReset(currentEnchantSeed);
            if (reset) {
                System.out.println("[seed-reset] newEnchantSeed=" + Integer.toUnsignedString(currentEnchantSeed));
                clearClientObservationState();
                wasInEnchantScreen = mc.screen instanceof EnchantmentScreen;
                return;
            }
        }

        boolean inEnchantScreen = mc.screen instanceof EnchantmentScreen;
        if (!inEnchantScreen) {
            if (wasInEnchantScreen) {
                clearClientObservationState();
            }
            wasInEnchantScreen = false;
            return;
        }
        wasInEnchantScreen = true;

        if (mc.player == null || !(mc.player.containerMenu instanceof EnchantmentMenu menu)) {
            clearClientObservationState();
            return;
        }

        ItemStack stack = menu.getSlot(0).getItem();
        if (stack.isEmpty()) {
            clearClientObservationState();
            return;
        }

        if (lastSeenItem != stack.getItem()) {
            lastSeenItem = stack.getItem();
            waitingForFreshMenuAfterItemChange = true;
            itemChangeBaselineMenuFingerprint = lastRawMenuFingerprint;
            itemChangeWaitTicks = 0;
            resetPending();
        }

        int[] costs = new int[3];
        int[] clueIds = new int[3];
        int[] clueLevels = new int[3];
        boolean usable = false;
        for (int i = 0; i < 3; i++) {
            costs[i] = menu.costs[i];
            clueIds[i] = menu.enchantClue[i];
            clueLevels[i] = menu.levelClue[i];
            if (costs[i] > 0 && clueIds[i] >= 0 && clueLevels[i] > 0) {
                usable = true;
            }
        }
        if (!usable) {
            return;
        }

        String menuFingerprint = buildMenuFingerprint(costs, clueIds, clueLevels);
        lastRawMenuFingerprint = menuFingerprint;

        if (waitingForFreshMenuAfterItemChange) {
            itemChangeWaitTicks++;

            boolean changedFromPreviousItemMenu = itemChangeBaselineMenuFingerprint == null
                    || !menuFingerprint.equals(itemChangeBaselineMenuFingerprint);

            if (!changedFromPreviousItemMenu && itemChangeWaitTicks < ITEM_CHANGE_REFRESH_WAIT_TICKS) {
                return;
            }

            waitingForFreshMenuAfterItemChange = false;
            itemChangeBaselineMenuFingerprint = null;
            itemChangeWaitTicks = 0;
            resetPending();
        }

        Integer resolvedBookshelves = resolveBookshelves(mc, menu);
        if (resolvedBookshelves == null) {
            clearClientObservationState();
            return;
        }

        int bookshelves = resolvedBookshelves;
        String key = ObservationRecord.buildKey(stack.getItem(), bookshelves, costs, clueIds, clueLevels);
        if (!key.equals(pendingKey)) {
            pendingKey = key;
            pendingTicks = 1;
            return;
        }

        pendingTicks++;
        if (pendingTicks < 3) {
            return;
        }

        logObservation(stack, bookshelves, costs, clueIds, clueLevels);

        ObservedEnchantState.set(stack.getItem(), bookshelves, costs, clueIds, clueLevels);
        ObservationRecord observation = new ObservationRecord(stack.getItem(), bookshelves, costs, clueIds, clueLevels);
        EnchantSeedCracker.submitObservation(observation);
    }

    private static String buildMenuFingerprint(int[] costs, int[] clueIds, int[] clueLevels) {
        return costs[0] + "," + costs[1] + "," + costs[2] + "|"
                + clueIds[0] + "," + clueIds[1] + "," + clueIds[2] + "|"
                + clueLevels[0] + "," + clueLevels[1] + "," + clueLevels[2];
    }

    private static void logObservation(ItemStack stack, int bookshelves, int[] costs, int[] clueIds, int[] clueLevels) {
        System.out.println("[obs-read] item=" + stack.getItem()
                + " bookshelves=" + bookshelves
                + " costs=" + Arrays.toString(costs)
                + " clueIds=" + Arrays.toString(clueIds)
                + " clueLv=" + Arrays.toString(clueLevels));
    }

    private static void updateTableLookHint(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult blockHitResult) {
            BlockPos pos = blockHitResult.getBlockPos();
            if (mc.level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
                lastLookedEnchantTablePos = pos.immutable();
                lastLookedEnchantTableTick = mc.level.getGameTime();
            }
        }
    }

    private static Integer getAuthoritativeEnchantSeed(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        if (mc.hasSingleplayerServer()) {
            var server = mc.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (serverPlayer == null) {
                return null;
            }
            return serverPlayer.getEnchantmentSeed();
        }
        return mc.player.getEnchantmentSeed();
    }

    private static Integer resolveBookshelves(Minecraft mc, EnchantmentMenu clientMenu) {
        Integer serverBookshelves = tryResolveServerBookshelves(mc);
        if (serverBookshelves != null) {
            return serverBookshelves;
        }

        BlockPos menuTablePos = tryResolveMenuTablePos(clientMenu);
        if (menuTablePos != null) {
            activeEnchantTablePos = menuTablePos;
        }

        Integer clientBookshelves = tryResolveMenuBookshelves(clientMenu);
        if (clientBookshelves != null) {
            return clientBookshelves;
        }

        if (mc.level != null && activeEnchantTablePos != null && isValidEnchantTable(mc.level, mc, activeEnchantTablePos)) {
            return countBookshelvesAtTable(mc.level, activeEnchantTablePos);
        }

        return tryResolveNearbyClientBookshelves(mc);
    }

    private static Integer tryResolveServerBookshelves(Minecraft mc) {
        if (!mc.hasSingleplayerServer() || mc.player == null) {
            return null;
        }

        var server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }

        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (serverPlayer == null) {
            return null;
        }

        if (!(serverPlayer.containerMenu instanceof EnchantmentMenu serverMenu)) {
            return null;
        }

        return tryResolveMenuBookshelves(serverMenu);
    }

    private static Integer tryResolveMenuBookshelves(EnchantmentMenu menu) {
        try {
            Field field = getAccessField();
            ContainerLevelAccess access = (ContainerLevelAccess) field.get(menu);
            if (access == null) {
                return null;
            }

            Integer value = access.evaluate((level, pos) -> {
                if (level == null || pos == null) {
                    return Integer.valueOf(-1);
                }
                return countBookshelvesAtTable(level, pos);
            }, Integer.valueOf(-1));

            return value != null && value >= 0 ? value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static BlockPos tryResolveMenuTablePos(EnchantmentMenu menu) {
        try {
            Field field = getAccessField();
            ContainerLevelAccess access = (ContainerLevelAccess) field.get(menu);
            if (access == null) {
                return null;
            }

            return access.evaluate((level, pos) -> pos == null ? null : pos.immutable(), null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Field getAccessField() throws NoSuchFieldException {
        Field field = reflectedAccessField;
        if (field == null) {
            field = EnchantmentMenu.class.getDeclaredField("access");
            field.setAccessible(true);
            reflectedAccessField = field;
        }
        return field;
    }

    private static Integer tryResolveNearbyClientBookshelves(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }

        BlockPos selectedTable = selectNearbyEnchantTable(mc);
        if (selectedTable == null) {
            return null;
        }

        activeEnchantTablePos = selectedTable;
        return countBookshelvesAtTable(mc.level, selectedTable);
    }

    private static BlockPos selectNearbyEnchantTable(Minecraft mc) {
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return null;
        }

        if (activeEnchantTablePos != null && isValidEnchantTable(level, mc, activeEnchantTablePos)) {
            return activeEnchantTablePos;
        }

        if (lastLookedEnchantTablePos != null
                && (level.getGameTime() - lastLookedEnchantTableTick) <= LOOK_HINT_MAX_AGE_TICKS
                && isValidEnchantTable(level, mc, lastLookedEnchantTablePos)) {
            return lastLookedEnchantTablePos;
        }

        BlockPos playerPos = mc.player.blockPosition();
        BlockPos bestTable = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.offset(-TABLE_SEARCH_HORIZONTAL_RADIUS, -TABLE_SEARCH_VERTICAL_RADIUS, -TABLE_SEARCH_HORIZONTAL_RADIUS),
                playerPos.offset(TABLE_SEARCH_HORIZONTAL_RADIUS, TABLE_SEARCH_VERTICAL_RADIUS, TABLE_SEARCH_HORIZONTAL_RADIUS)
        )) {
            if (!level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
                continue;
            }

            double distanceSquared = getPlayerDistanceSquared(mc, pos);
            if (distanceSquared > MAX_TABLE_DISTANCE_SQUARED || distanceSquared >= bestDistanceSquared) {
                continue;
            }

            bestDistanceSquared = distanceSquared;
            bestTable = pos.immutable();
        }

        return bestTable;
    }

    private static boolean isValidEnchantTable(Level level, Minecraft mc, BlockPos pos) {
        return level != null
                && pos != null
                && level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)
                && getPlayerDistanceSquared(mc, pos) <= MAX_TABLE_DISTANCE_SQUARED;
    }

    private static double getPlayerDistanceSquared(Minecraft mc, BlockPos pos) {
        if (mc.player == null) {
            return Double.MAX_VALUE;
        }
        double dx = (pos.getX() + 0.5D) - mc.player.getX();
        double dy = (pos.getY() + 0.5D) - mc.player.getY();
        double dz = (pos.getZ() + 0.5D) - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Integer countBookshelvesAtTable(Level level, BlockPos tablePos) {
        int count = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                if (!isGapOpen(level, tablePos.offset(dx, 0, dz)) || !isGapOpen(level, tablePos.offset(dx, 1, dz))) {
                    continue;
                }

                count += getBookshelfCount(level, tablePos.offset(dx * 2, 0, dz * 2));
                count += getBookshelfCount(level, tablePos.offset(dx * 2, 1, dz * 2));

                if (dx != 0 && dz != 0) {
                    count += getBookshelfCount(level, tablePos.offset(dx * 2, 0, dz));
                    count += getBookshelfCount(level, tablePos.offset(dx * 2, 1, dz));
                    count += getBookshelfCount(level, tablePos.offset(dx, 0, dz * 2));
                    count += getBookshelfCount(level, tablePos.offset(dx, 1, dz * 2));
                }
            }
        }

        return Math.min(count, 15);
    }

    private static boolean isGapOpen(Level level, BlockPos pos) {
        return level.isEmptyBlock(pos) || level.getBlockState(pos).canBeReplaced();
    }

    private static int getBookshelfCount(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.BOOKSHELF) ? 1 : 0;
    }
}
