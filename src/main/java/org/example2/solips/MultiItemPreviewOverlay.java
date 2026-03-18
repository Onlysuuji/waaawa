package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.StreamSupport;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
public class MultiItemPreviewOverlay {
    private static final Item[] PREVIEW_ITEMS = new Item[] {
            Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE,
            Items.IRON_SWORD,
            Items.GOLDEN_SWORD,
            Items.IRON_HELMET,
            Items.IRON_CHESTPLATE,
            Items.IRON_LEGGINGS,
            Items.IRON_BOOTS,
            Items.GOLDEN_BOOTS,
            Items.BOOK
    };

    private static final int BOOKSHELVES_LOW = 0;
    private static final int BOOKSHELVES_HIGH = 15;
    private static final int CELL_WIDTH = 22;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_MARGIN = 4;
    private static final int HEADER_COLOR = 0xFFFFFF;
    private static final int HUD_LABEL_COLOR = 0xAAAAAA;
    private static final int HUD_TEXT_COLOR = 0xFFFFFF;
    private static final int HUD_SUB_COLOR = 0xAAAAFF;
    private static final int HUD_DIM_COLOR = 0x888888;
    private static final int HUD_OK_COLOR = 0x55FF55;
    private static final int HUD_WARN_COLOR = 0xFFFF55;
    private static final int HUD_IDLE_COLOR = 0xCCCCCC;
    private static final int SINGLEPLAYER_SEED_CACHE_INTERVAL_TICKS = 20;

    private static final int[][] headerCosts = new int[2][3];
    private static final String[] headerLabels = new String[6];
    private static final ItemStack[][] previewStacks = new ItemStack[PREVIEW_ITEMS.length][6];
    private static final ItemStack[] rowIconStacks = new ItemStack[PREVIEW_ITEMS.length];

    private static int previewSeed = Integer.MIN_VALUE;
    private static boolean previewValid = false;

    private static int cachedSingleplayerSeed = Integer.MIN_VALUE;
    private static long nextSingleplayerSeedRefreshTick = 0L;

    private static volatile Method featureToggleMethod;
    private static volatile boolean featureToggleResolved = false;

    static {
        for (int row = 0; row < PREVIEW_ITEMS.length; row++) {
            rowIconStacks[row] = new ItemStack(PREVIEW_ITEMS[row]);
            for (int col = 0; col < 6; col++) {
                previewStacks[row][col] = ItemStack.EMPTY;
            }
        }
        for (int i = 0; i < headerLabels.length; i++) {
            headerLabels[i] = "0";
        }
    }

    @SubscribeEvent
    public static void onRenderHud(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!isFeatureEnabled()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        renderLeftHud(mc, graphics, font);

        if (!SeedCrackState.isSolved()) {
            return;
        }

        int solvedSeed = SeedCrackState.getSolvedSeed();
        if (!previewValid || previewSeed != solvedSeed) {
            rebuildPreviewCache(mc, solvedSeed);
        }

        if (previewValid) {
            renderRightTopGrid(mc, graphics, font);
        }
    }

    private static void renderLeftHud(Minecraft mc, GuiGraphics graphics, Font font) {
        int left = 5;
        int y = 5;

        graphics.drawString(font, "resetKey=M", left, y, HUD_LABEL_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "observations=" + SeedCrackState.getObservationCount(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "applied=" + SeedCrackState.getAppliedObservationCount(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "queued=" + SeedCrackState.getQueuedObservationCount(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "running=" + SeedCrackState.isRunning(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        int stopwatchColor = SeedCrackState.isSolved()
                ? HUD_OK_COLOR
                : (SeedCrackState.isStopwatchRunning() ? HUD_WARN_COLOR : HUD_IDLE_COLOR);
        graphics.drawString(font, "elapsed=" + SeedCrackState.getElapsedFormatted(), left, y, stopwatchColor, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "checked=" + SeedCrackState.getChecked(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "matched=" + SeedCrackState.getMatched(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "solved=" + SeedCrackState.isSolved(), left, y, HUD_TEXT_COLOR, false);
        y += font.lineHeight + 2;

        if (SeedCrackState.isSolved()) {
            graphics.drawString(font,
                    "solvedSeed=" + Integer.toUnsignedString(SeedCrackState.getSolvedSeed()),
                    left,
                    y,
                    HUD_OK_COLOR,
                    false);
            y += font.lineHeight + 2;
        }

        ObservationRecord snapshot = ObservedEnchantState.snapshot();
        if (snapshot != null) {
            graphics.drawString(font, "bookshelves=" + snapshot.getBookshelves() + " (auto)", left, y, HUD_SUB_COLOR, false);
            y += font.lineHeight + 2;
            graphics.drawString(font, "latest=" + snapshot.getKey(), left, y, HUD_SUB_COLOR, false);
            y += font.lineHeight + 2;
        } else {
            graphics.drawString(font, "bookshelves=(none)", left, y, HUD_DIM_COLOR, false);
            y += font.lineHeight + 2;
            graphics.drawString(font, "latest=(none)", left, y, HUD_DIM_COLOR, false);
            y += font.lineHeight + 2;
        }

        Integer actualSeed = getCachedSingleplayerSeed(mc);
        if (actualSeed != null) {
            graphics.drawString(font, "answerSeed=" + Integer.toUnsignedString(actualSeed), left, y, HUD_OK_COLOR, false);
        } else if (mc.hasSingleplayerServer()) {
            graphics.drawString(font, "answerSeed=(loading)", left, y, HUD_WARN_COLOR, false);
        } else {
            graphics.drawString(font, "answerSeed=(multiplayer)", left, y, HUD_DIM_COLOR, false);
        }
    }

    private static void renderRightTopGrid(Minecraft mc, GuiGraphics graphics, Font font) {
        int columns = 7;
        int rows = 1 + PREVIEW_ITEMS.length;
        int gridWidth = columns * CELL_WIDTH;
        int startX = mc.getWindow().getGuiScaledWidth() - gridWidth - GRID_MARGIN;
        int startY = GRID_MARGIN;

        drawCenteredString(graphics, font, "", startX, startY, CELL_WIDTH, CELL_HEIGHT, HEADER_COLOR);
        for (int col = 0; col < 6; col++) {
            drawCenteredString(
                    graphics,
                    font,
                    headerLabels[col],
                    startX + (col + 1) * CELL_WIDTH,
                    startY,
                    CELL_WIDTH,
                    CELL_HEIGHT,
                    HEADER_COLOR
            );
        }

        for (int row = 0; row < PREVIEW_ITEMS.length; row++) {
            int y = startY + (row + 1) * CELL_HEIGHT;
            graphics.renderItem(rowIconStacks[row], startX + 3, y + 2);
            for (int col = 0; col < 6; col++) {
                ItemStack stack = previewStacks[row][col];
                if (stack.isEmpty()) {
                    continue;
                }
                int cellX = startX + (col + 1) * CELL_WIDTH;
                graphics.renderItem(stack, cellX + 3, y + 2);
            }
        }
    }

    private static void drawCenteredString(GuiGraphics graphics, Font font, String text, int x, int y, int width, int height, int color) {
        int drawX = x + (width - font.width(text)) / 2;
        int drawY = y + (height - font.lineHeight) / 2;
        graphics.drawString(font, text, drawX, drawY, color, false);
    }

    private static void rebuildPreviewCache(Minecraft mc, int seed) {
        RegistryAccess registryAccess = mc.level.registryAccess();
        Registry<Enchantment> enchantmentRegistry = registryAccess.registryOrThrow(Registries.ENCHANTMENT);
        List<Holder<Enchantment>> holders = StreamSupport
                .stream(enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(), false)
                .map(holder -> (Holder<Enchantment>) holder)
                .toList();

        fillHeaderCosts(seed);
        fillPreviewStacks(seed, holders);

        previewSeed = seed;
        previewValid = true;
    }

    private static void fillHeaderCosts(int seed) {
        ItemStack headerBase = new ItemStack(Items.IRON_PICKAXE);
        copyCostsInto(seed, BOOKSHELVES_LOW, headerBase.copy(), headerCosts[0]);
        copyCostsInto(seed, BOOKSHELVES_HIGH, headerBase.copy(), headerCosts[1]);

        for (int slot = 0; slot < 3; slot++) {
            headerLabels[slot] = Integer.toString(headerCosts[0][slot]);
            headerLabels[slot + 3] = Integer.toString(headerCosts[1][slot]);
        }
    }

    private static void fillPreviewStacks(int seed, List<Holder<Enchantment>> holders) {
        for (int row = 0; row < PREVIEW_ITEMS.length; row++) {
            Item item = PREVIEW_ITEMS[row];
            for (int slot = 0; slot < 3; slot++) {
                previewStacks[row][slot] = buildPreviewStack(seed, BOOKSHELVES_LOW, item, slot, holders);
                previewStacks[row][slot + 3] = buildPreviewStack(seed, BOOKSHELVES_HIGH, item, slot, holders);
            }
        }
    }

    private static void copyCostsInto(int seed, int bookshelves, ItemStack stack, int[] out) {
        RandomSource random = RandomSource.create(seed);
        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelves, stack);
            if (cost < slot + 1) {
                cost = 0;
            }
            out[slot] = cost;
        }
    }

    private static ItemStack buildPreviewStack(int seed, int bookshelves, Item item, int slot, List<Holder<Enchantment>> holders) {
        ItemStack base = new ItemStack(item);
        int[] costs = new int[3];
        copyCostsInto(seed, bookshelves, base.copy(), costs);
        int cost = costs[slot];
        if (cost <= 0) {
            return base;
        }

        ItemStack enchanted = EnchantmentHelper.enchantItem(
                RandomSource.create((long) seed + slot),
                base.copy(),
                cost,
                holders.stream()
        );

        enchanted.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
        return enchanted;
    }

    private static Integer getCachedSingleplayerSeed(Minecraft mc) {
        long gameTime = mc.level == null ? 0L : mc.level.getGameTime();
        if (gameTime >= nextSingleplayerSeedRefreshTick) {
            nextSingleplayerSeedRefreshTick = gameTime + SINGLEPLAYER_SEED_CACHE_INTERVAL_TICKS;
            Integer fresh = getAuthoritativeSingleplayerSeed(mc);
            cachedSingleplayerSeed = fresh == null ? Integer.MIN_VALUE : fresh;
        }
        return cachedSingleplayerSeed == Integer.MIN_VALUE ? null : cachedSingleplayerSeed;
    }

    private static Integer getAuthoritativeSingleplayerSeed(Minecraft mc) {
        if (!mc.hasSingleplayerServer() || mc.player == null) {
            return null;
        }
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (serverPlayer == null) {
            return null;
        }
        return serverPlayer.getEnchantmentSeed();
    }

    private static boolean isFeatureEnabled() {
        try {
            Method method = featureToggleMethod;
            if (method == null && !featureToggleResolved) {
                featureToggleResolved = true;
                Class<?> clazz = Class.forName("org.example2.solips.ClientFeatureToggle");
                method = clazz.getDeclaredMethod("isEnabled");
                method.setAccessible(true);
                featureToggleMethod = method;
            }
            if (method != null) {
                Object result = method.invoke(null);
                if (result instanceof Boolean enabled) {
                    return enabled;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            featureToggleMethod = null;
        }
        return true;
    }
}
