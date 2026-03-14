package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)

public class MultiItemPreviewOverlay {


    private static Integer lastSeed = null;
    private static final List<RowData> cachedRows = new ArrayList<>();

    private static int[] headerCost0 = new int[]{0, 0, 0};
    private static int[] headerCost15 = new int[]{0, 0, 0};

    private static final RowTarget[] ROW_TARGETS = new RowTarget[]{
            new RowTarget(Items.DIAMOND_SWORD),
            new RowTarget(Items.GOLDEN_SWORD),
            new RowTarget(Items.IRON_SWORD),
            new RowTarget(Items.DIAMOND_PICKAXE),
            new RowTarget(Items.GOLDEN_PICKAXE),
            new RowTarget(Items.IRON_PICKAXE),
            new RowTarget(Items.IRON_BOOTS),
            new RowTarget(Items.BOOK)
    };


    @SubscribeEvent
    public static void onRenderHud(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clearCache();
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        int left = 5;
        int y = 5;

        if (!ObservedEnchantState.isValid()) {
            graphics.drawString(font, "No observation", left, y, 0xFFFFFF, false);
            return;
        }

        int[] costs = ObservedEnchantState.getCosts();
        int[] clueIds = ObservedEnchantState.getClueIds();
        int[] clueLv = ObservedEnchantState.getClueLevels();

        ObservationRecord snapshot = ObservedEnchantState.snapshot();
        if (snapshot != null && !SeedCrackState.isRunning() && !SeedCrackState.hasObservationKey(snapshot.getKey())) {
            EnchantSeedCracker.startCrack();
        }

        graphics.drawString(font, "observations=" + SeedCrackState.getObservationCount(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "running=" + SeedCrackState.isRunning(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "checked=" + SeedCrackState.getChecked(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "matched=" + SeedCrackState.getMatched(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;


        int actualSeed = -1;
        if (mc.player != null && mc.hasSingleplayerServer()) {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (serverPlayer != null) {
                    actualSeed = serverPlayer.getEnchantmentSeed();
                }
            }
        }

        graphics.drawString(font, "actualSeed=" + actualSeed, left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;
        graphics.drawString(font, "actualLow24=" + (actualSeed & 0x00FFFFFF), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        int clientSeed = mc.player != null ? mc.player.getEnchantmentSeed() : -1;
        graphics.drawString(font, "clientSeed=" + clientSeed, left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        int serverSeed = -1;
        if (mc.player != null && mc.hasSingleplayerServer()) {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (serverPlayer != null) {
                    serverSeed = serverPlayer.getEnchantmentSeed();
                }
            }
        }
        graphics.drawString(font, "serverSeed=" + serverSeed, left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        if (SeedCrackState.isSolved()) {
            graphics.drawString(font, "seed=" + SeedCrackState.getSolvedSeed(), left, y, 0x55FF55, false);
        }
    }

    private static void clearCache() {
        lastSeed = null;
        cachedRows.clear();
        headerCost0 = new int[]{0, 0, 0};
        headerCost15 = new int[]{0, 0, 0};
    }

    private static String formatHeaderCost(int cost) {
        return cost <= 0 ? "-" : String.valueOf(cost);
    }

    private static void rebuildCache(int seed, RegistryAccess registryAccess) {
        cachedRows.clear();

        if (ROW_TARGETS.length > 0) {
            Item headerItem = ROW_TARGETS[0].item;
            headerCost0 = buildCosts(headerItem, 0, seed);
            headerCost15 = buildCosts(headerItem, 15, seed);
        }

        for (RowTarget target : ROW_TARGETS) {
            String[] bs0 = buildSlotTexts(target.item, 0, seed, registryAccess);
            String[] bs15 = buildSlotTexts(target.item, 15, seed, registryAccess);
            cachedRows.add(new RowData(new ItemStack(target.item), bs0, bs15));
        }
    }

    private static int[] buildCosts(Item item, int bookshelves, int seed) {
        ItemStack stack = new ItemStack(item);
        RandomSource random = RandomSource.create(seed);
        int[] costs = new int[3];

        for (int slot = 0; slot < 3; slot++) {
            costs[slot] = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelves, stack);
            if (costs[slot] < slot + 1) {
                costs[slot] = 0;
            }
        }

        return costs;
    }

    private static String[] buildSlotTexts(Item item, int bookshelves, int seed, RegistryAccess registryAccess) {
        ItemStack stack = new ItemStack(item);
        RandomSource costRandom = RandomSource.create(seed);

        int[] costs = new int[3];
        String[] slotTexts = new String[3];

        for (int slot = 0; slot < 3; slot++) {
            costs[slot] = EnchantmentHelper.getEnchantmentCost(costRandom, slot, bookshelves, stack);
            if (costs[slot] < slot + 1) {
                costs[slot] = 0;
            }
        }

        HolderLookup.RegistryLookup<Enchantment> lookup = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);

        for (int slot = 0; slot < 3; slot++) {
            if (costs[slot] <= 0) {
                slotTexts[slot] = "-";
                continue;
            }

            RandomSource enchantRandom = RandomSource.create((long) seed + slot);
            List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(
                    enchantRandom,
                    stack,
                    costs[slot],
                    lookup.listElements().map(holder -> (Holder<Enchantment>) holder)
            );

            slotTexts[slot] = formatCompactList(list);
        }

        return slotTexts;
    }

    private static String formatCompactList(List<EnchantmentInstance> list) {
        if (list == null || list.isEmpty()) return "-";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            EnchantmentInstance data = list.get(i);
            if (i > 0) sb.append("+");
            sb.append(shortEnchant(data));
        }
        return sb.toString();
    }

    private static String shortEnchant(EnchantmentInstance data) {
        String path = data.enchantment.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("unknown");

        String code;
        switch (path) {
            case "sharpness" -> code = "s";
            case "smite" -> code = "sm";
            case "bane_of_arthropods" -> code = "ba";
            case "knockback" -> code = "kb";
            case "fire_aspect" -> code = "fa";
            case "looting" -> code = "lo";
            case "sweeping_edge", "sweeping" -> code = "sw";
            case "efficiency" -> code = "eff";
            case "silk_touch" -> code = "st";
            case "fortune" -> code = "fo";
            case "unbreaking" -> code = "u";
            case "mending" -> code = "m";
            case "curse_of_vanishing" -> code = "van";
            case "curse_of_binding", "binding_curse" -> code = "bind";
            default -> code = fallbackCode(path);
        }
        return code + data.level;
    }

    private static String fallbackCode(String path) {
        String s = path.replace("minecraft:", "").replace("_", "");
        if (s.length() <= 3) return s;
        return s.substring(0, 3);
    }

    private static void drawCentered(Font font, GuiGraphics graphics, String text, int left, int right, int y, int color) {
        int centerX = (left + right) / 2;
        graphics.drawCenteredString(font, text, centerX, y, color);
    }

    private static void drawCellText(Font font, GuiGraphics graphics, String text, int left, int right, int rowTop, int rowHeight) {
        int textY = rowTop + (rowHeight - font.lineHeight) / 2;
        int maxTextWidth = (right - left) - 8;

        String draw = trimToWidth(font, text, maxTextWidth);
        graphics.drawString(font, draw, left + 2, textY, 0xFFFFFF, false);
    }

    private static int computeColumnWidth(Font font, String header, List<RowData> rows, boolean isRightGroup, int index, int padX) {
        int max = font.width(header);

        for (RowData row : rows) {
            String text = isRightGroup ? row.bs15[index] : row.bs0[index];
            if (text != null) {
                max = Math.max(max, font.width(text));
            }
        }

        return max + padX * 2;
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;

        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            String next = sb.toString() + text.charAt(i);
            if (font.width(next) + ellipsisWidth > maxWidth) break;
            sb.append(text.charAt(i));
        }

        return sb + ellipsis;
    }

    private static void drawVLine(GuiGraphics graphics, int x, int top, int bottomExclusive, int color) {
        graphics.vLine(x, top, bottomExclusive - 1, color);
    }

    private static void drawHLine(GuiGraphics graphics, int left, int rightExclusive, int y, int color) {
        graphics.hLine(left, rightExclusive - 1, y, color);
    }

    private static void drawBorder(GuiGraphics graphics, int left, int top, int rightExclusive, int bottomExclusive, int color) {
        drawHLine(graphics, left, rightExclusive, top, color);
        drawHLine(graphics, left, rightExclusive, bottomExclusive - 1, color);
        drawVLine(graphics, left, top, bottomExclusive, color);
        drawVLine(graphics, rightExclusive - 1, top, bottomExclusive, color);
    }

    private static class RowTarget {
        private final Item item;

        private RowTarget(Item item) {
            this.item = item;
        }
    }

    private static class RowData {
        private final ItemStack itemStack;
        private final String[] bs0;
        private final String[] bs15;

        private RowData(ItemStack itemStack, String[] bs0, String[] bs15) {
            this.itemStack = itemStack;
            this.bs0 = bs0;
            this.bs15 = bs15;
        }
    }
}