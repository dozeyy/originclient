package com.origin.client.hud;

import com.origin.client.OriginState;
import com.origin.client.render.OriginGl;
import com.origin.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Origin HUD readout (FPS + coordinates) drawn as a translucent Origin-styled
 * panel in the top-left, matching the Fabric build's HUD info look. Rendered on
 * the Forge overlay event so no HUD mixin is needed on the classic versions.
 */
public final class OriginHud {
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (!OriginState.features().hudInfoEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) {
            return; // don't stack on the F3 screen
        }
        try {
            draw(mc);
        } catch (Throwable ignored) {
        }
    }

    private void draw(Minecraft mc) {
        EntityPlayer p = mc.thePlayer;
        List<String> lines = new ArrayList<String>();
        lines.add("FPS " + fps(mc));
        lines.add("XYZ " + (int) Math.floor(p.posX) + " " + (int) Math.floor(p.posY) + " " + (int) Math.floor(p.posZ));

        int pad = 6;
        int lineH = OriginGl.fontHeight() + 2;
        int w = 0;
        for (String s : lines) {
            w = Math.max(w, OriginGl.textWidth(s));
        }
        int x = 6, y = 6;
        int panelW = w + pad * 2;
        int panelH = lines.size() * lineH + pad * 2 - 2;

        OriginGl.fill(x, y, x + panelW, y + panelH, OriginTheme.PANEL_TRANSLUCENT);
        // Thin left accent bar — the Origin "designed edge".
        OriginGl.fill(x, y, x + 1, y + panelH, OriginTheme.STROKE_STRONG);

        int ty = y + pad;
        for (String s : lines) {
            OriginGl.text(s, x + pad, ty, OriginTheme.TEXT, false);
            ty += lineH;
        }
    }

    /** FPS from Minecraft's public debug string (debugFPS is private on 1.8.9). */
    private int fps(Minecraft mc) {
        try {
            String d = mc.debug;
            if (d != null) {
                int sp = d.indexOf(' ');
                if (sp > 0) {
                    return Integer.parseInt(d.substring(0, sp).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
