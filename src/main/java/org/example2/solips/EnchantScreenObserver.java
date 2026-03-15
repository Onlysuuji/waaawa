package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Arrays;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
public class EnchantScreenObserver {

    private static boolean wasInEnchantScreen = false;

    private static String pendingKey = null;
    private static int pendingTicks = 0;

    private static void resetPending() {
        pendingKey = null;
        pendingTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean inEnchantScreen = mc.screen instanceof EnchantmentScreen;

        if (!inEnchantScreen) {
            if (wasInEnchantScreen) {
                EnchantSeedCracker.stopCrack();
                ObservedEnchantState.clear();
                SeedCrackState.resetAll();
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

        // サーバー同期直後の未確定状態は捨てる
        if (!usable) {
            return;
        }

        int bookshelves = 15;
        String key = ObservationRecord.buildKey(stack.getItem(), bookshelves, costs, clueIds, clueLevels);

        // 同じ観測が連続で見えた回数を数える
        if (!key.equals(pendingKey)) {
            pendingKey = key;
            pendingTicks = 1;
            return;
        }

        pendingTicks++;

        // 2〜3tick 安定してから確定
        if (pendingTicks < 3) {
            return;
        }

        ObservedEnchantState.set(stack.getItem(), bookshelves, costs, clueIds, clueLevels);

        if (!SeedCrackState.hasObservationKey(key)) {
            System.out.println("[obs-debug] commit item=" + stack.getItem()
                    + " costs=" + Arrays.toString(costs)
                    + " clueIds=" + Arrays.toString(clueIds)
                    + " clueLv=" + Arrays.toString(clueLevels));
        }
    }
}