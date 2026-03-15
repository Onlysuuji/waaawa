package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.util.Arrays;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
public class EnchantScreenObserver {
    private static boolean wasInEnchantScreen = false;
    private static String pendingKey = null;
    private static int pendingTicks = 0;
    private static volatile Field reflectedAccessField;

    private static void resetPending() {
        pendingKey = null;
        pendingTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        Integer currentEnchantSeed = getAuthoritativeEnchantSeed(mc);
        if (currentEnchantSeed != null) {
            boolean reset = SeedCrackState.updateEnchantSeedAndCheckReset(currentEnchantSeed);
            if (reset) {
                System.out.println("[seed-reset] newEnchantSeed=" + Integer.toUnsignedString(currentEnchantSeed));
                ObservedEnchantState.clear();
                resetPending();
                wasInEnchantScreen = mc.screen instanceof EnchantmentScreen;
                return;
            }
        }

        boolean inEnchantScreen = mc.screen instanceof EnchantmentScreen;

        if (!inEnchantScreen) {
            if (wasInEnchantScreen) {
                ObservedEnchantState.clear();
                resetPending();
            }
            wasInEnchantScreen = false;
            return;
        }

        wasInEnchantScreen = true;

        if (mc.player == null || !(mc.player.containerMenu instanceof EnchantmentMenu menu)) {
            ObservedEnchantState.clear();
            resetPending();
            return;
        }

        ItemStack stack = menu.getSlot(0).getItem();
        if (stack.isEmpty()) {
            ObservedEnchantState.clear();
            resetPending();
            return;
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

        int bookshelves = resolveBookshelves(mc, menu);
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

        ObservedEnchantState.set(stack.getItem(), bookshelves, costs, clueIds, clueLevels);

        ObservationRecord observation =
                new ObservationRecord(stack.getItem(), bookshelves, costs, clueIds, clueLevels);

        if (!SeedCrackState.hasObservationKey(observation.getKey())) {
            System.out.println("[obs-debug] commit item=" + stack.getItem()
                    + " bookshelves=" + bookshelves
                    + " costs=" + Arrays.toString(costs)
                    + " clueIds=" + Arrays.toString(clueIds)
                    + " clueLv=" + Arrays.toString(clueLevels));
        }

        EnchantSeedCracker.submitObservation(observation);
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

    private static int resolveBookshelves(Minecraft mc, EnchantmentMenu clientMenu) {
        Integer serverBookshelves = tryResolveServerBookshelves(mc);
        if (serverBookshelves != null) {
            return serverBookshelves;
        }

        Integer clientBookshelves = tryResolveMenuBookshelves(clientMenu);
        if (clientBookshelves != null) {
            return clientBookshelves;
        }

        return 0;
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
            Field field = reflectedAccessField;
            if (field == null) {
                field = EnchantmentMenu.class.getDeclaredField("access");
                field.setAccessible(true);
                reflectedAccessField = field;
            }

            ContainerLevelAccess access = (ContainerLevelAccess) field.get(menu);
            if (access == null) {
                return null;
            }

            Integer value = access.evaluate(EnchantScreenObserver::countBookshelvesAtTable, Integer.valueOf(-1));
            return value != null && value >= 0 ? value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Integer countBookshelvesAtTable(Level level, BlockPos tablePos) {
        int count = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                if (!isGapOpen(level, tablePos.offset(dx, 0, dz))
                        || !isGapOpen(level, tablePos.offset(dx, 1, dz))) {
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
