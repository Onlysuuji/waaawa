package org.example2.solips;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class ManualResetKeyHandler {
    private static KeyMapping resetAllMapping;
    private static boolean wasDown = false;

    private ManualResetKeyHandler() {
    }

    private static void triggerReset(Minecraft mc) {
        SeedCrackState.resetAll();
        EnchantScreenObserver.clearClientObservationState();
        System.out.println("[manual-reset] cleared by M key");

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[solips] reset all"), true);
        }
    }

    @EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            if (resetAllMapping == null) {
                resetAllMapping = new KeyMapping(
                        "key.solips.reset_all",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_M,
                        "key.categories.solips"
                );
            }
            event.register(resetAllMapping);
        }
    }

    @EventBusSubscriber(modid = Solips.MODID, value = Dist.CLIENT)
    public static final class GameBusEvents {
        private GameBusEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || resetAllMapping == null) {
                wasDown = false;
                return;
            }

            boolean isDown = resetAllMapping.isDown();
            if (isDown && !wasDown) {
                triggerReset(mc);
            }
            wasDown = isDown;
        }
    }
}
