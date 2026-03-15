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

        if (!ObservedEnchantState.isValid()) {
            graphics.drawString(font, "No observation", left, y, 0xFFFFFF, false);
            return;
        }

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

        int clientSeed = mc.player.getEnchantmentSeed();
        graphics.drawString(font, "clientSeed=" + clientSeed, left, y, 0xFFFFFF, false);
        y += font.lineHeight + 2;

        int serverSeed = -1;
        if (mc.hasSingleplayerServer()) {
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

        if (SeedCrackState.isActualSeedKnown()) {
            graphics.drawString(font, "actualSeed=" + SeedCrackState.getActualSeed(), left, y, 0xFFFFFF, false);
            y += font.lineHeight + 2;

            graphics.drawString(
                    font,
                    "actualPassesLatest=" + SeedCrackState.getActualSeedPassesLatest(),
                    left,
                    y,
                    SeedCrackState.getActualSeedPassesLatest() ? 0x55FF55 : 0xFF5555,
                    false
            );
            y += font.lineHeight + 2;

            graphics.drawString(
                    font,
                    "actualPassesAll=" + SeedCrackState.getActualSeedPassesAll(),
                    left,
                    y,
                    SeedCrackState.getActualSeedPassesAll() ? 0x55FF55 : 0xFF5555,
                    false
            );
            y += font.lineHeight + 2;

            if (SeedCrackState.getActualFailedObservationIndex() >= 0) {
                graphics.drawString(
                        font,
                        "actualFailedAt=obs#" + (SeedCrackState.getActualFailedObservationIndex() + 1),
                        left,
                        y,
                        0xFFAAAA,
                        false
                );
                y += font.lineHeight + 2;
            }

            String reason = SeedCrackState.getActualMismatchReason();
            if (reason != null && !reason.isEmpty()) {
                graphics.drawString(font, "actualReason=" + reason, left, y, 0xFFAAAA, false);
                y += font.lineHeight + 2;
            }
        } else {
            graphics.drawString(font, "actualSeed=unavailable", left, y, 0xFFAAAA, false);
            y += font.lineHeight + 2;
        }

        if (SeedCrackState.isSolved()) {
            graphics.drawString(font, "seed=" + SeedCrackState.getSolvedSeed(), left, y, 0x55FF55, false);
        }
    }
}