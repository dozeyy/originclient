package com.origin.client.feature;

import com.origin.client.OriginFeatures;
import com.origin.client.OriginState;
import com.origin.client.gui.OriginModMenu;
import com.origin.client.input.OriginKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives Origin's non-render features on the classic versions: zoom (FOV event),
 * fullbright (gamma), toggle sprint/sneak (client-side, creative-safe), and the
 * Right-Shift mod menu. Everything is client-side QoL — Lunar-tier, no server
 * interaction.
 */
public final class OriginClientEvents {
    private float savedGamma = Float.NaN;
    private boolean prevSprintKey = false;
    private boolean prevSneakKey = false;

    @SubscribeEvent
    public void onFov(FOVUpdateEvent event) {
        OriginFeatures f = OriginState.features();
        if (f.zoomEnabled && OriginKeys.zoom != null && OriginKeys.zoom.isKeyDown()) {
            float base = 70.0f;
            event.setNewfov((float) (f.zoomFov / base));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        OriginFeatures f = OriginState.features();

        // Right-Shift opens the Origin mod menu (only when no screen is open).
        if (OriginKeys.menu != null && OriginKeys.menu.isPressed() && mc.currentScreen == null) {
            mc.displayGuiScreen(new OriginModMenu());
        }

        applyFullbright(mc, f);

        if (mc.player == null) {
            return;
        }
        applyToggle(mc, f);
    }

    private void applyFullbright(Minecraft mc, OriginFeatures f) {
        GameSettings gs = mc.gameSettings;
        if (gs == null) {
            return;
        }
        if (f.fullbrightEnabled) {
            if (Float.isNaN(savedGamma)) {
                savedGamma = gs.gammaSetting;
            }
            gs.gammaSetting = 100.0f;
        } else if (!Float.isNaN(savedGamma)) {
            gs.gammaSetting = savedGamma;
            savedGamma = Float.NaN;
        }
    }

    private void applyToggle(Minecraft mc, OriginFeatures f) {
        // Sprint toggle: rising edge on the sprint key flips a latch; while
        // latched the player is kept sprinting each tick.
        boolean sprintKey = mc.gameSettings.keyBindSprint.isKeyDown();
        if (f.toggleSprintEnabled) {
            if (sprintKey && !prevSprintKey) {
                f.sprintToggledOn = !f.sprintToggledOn;
            }
            if (f.sprintToggledOn && mc.player.onGround
                    && mc.player.moveForward > 0.0f && !mc.player.isSneaking()) {
                mc.player.setSprinting(true);
            }
        } else {
            f.sprintToggledOn = false;
        }
        prevSprintKey = sprintKey;

        boolean sneakKey = mc.gameSettings.keyBindSneak.isKeyDown();
        if (f.toggleSneakEnabled) {
            if (sneakKey && !prevSneakKey) {
                f.sneakToggledOn = !f.sneakToggledOn;
            }
            if (f.sneakToggledOn) {
                mc.player.setSneaking(true);
            }
        } else {
            f.sneakToggledOn = false;
        }
        prevSneakKey = sneakKey;
    }
}
