package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

@EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
public class MultiItemPreviewOverlay {

    @SubscribeEvent
    public static void onRenderHud(RenderGuiLayerEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        int left = 5;
        int y = 5;

        graphics.drawString(font, "observations=" + SeedCrackState.getObservationCount(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "running=" + SeedCrackState.isRunning(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "checked=" + SeedCrackState.getChecked(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "matched=" + SeedCrackState.getMatched(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        graphics.drawString(font, "solved=" + SeedCrackState.isSolved(), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        if (SeedCrackState.isSolved()) {
            graphics.drawString(
                    font,
                    "solvedSeed=" + Integer.toUnsignedString(SeedCrackState.getSolvedSeed()),
                    left,
                    y,
                    0x55FF55,
                    false
            );
            y += font.lineHeight + 2;
        }

        ObservationRecord snapshot = ObservedEnchantState.snapshot();
        if (snapshot != null) {
            graphics.drawString(font, "latest=" + snapshot.getKey(), left, y, 0xAAAAFF, false);
            y += font.lineHeight + 2;
        } else {
            graphics.drawString(font, "latest=(none)", left, y, 0x888888, false);
            y += font.lineHeight + 2;
        }

        int clientSeed = mc.player.getEnchantmentSeed();
        graphics.drawString(font, "clientSeed=" + Integer.toUnsignedString(clientSeed), left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;
    }
}