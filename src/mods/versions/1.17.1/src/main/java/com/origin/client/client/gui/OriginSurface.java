package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;

/**
 * Physical-pixel helpers for Origin's drawing around Minecraft's menus (the
 * loading bar, the logo's pixel grid). Minecraft's own controls are stock
 * widgets (Will, 2026-10-19); what's left here draws at exactly one screen
 * pixel at any GUI scale (smooth-ui-baked-masks). Ported from 1.21.1 with the
 * pre-1.20 Gfx wrapper in place of GuiGraphics.
 */
public final class OriginSurface {
	private OriginSurface() {
	}

	/** Physical-pixel GUI scale (integer in vanilla, 1 at minimum). */
	public static int scale() {
		return (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	/** Fills a GUI-px span at physical precision: x0..x1 may be fractional (bars). */
	public static void fillPhysical(Gfx g, double x0, double y0, double x1, double y1, int argb) {
		int gs = scale();
		int px0 = (int) Math.round(x0 * gs), px1 = (int) Math.round(x1 * gs);
		int py0 = (int) Math.round(y0 * gs), py1 = (int) Math.round(y1 * gs);
		if (px1 <= px0 || py1 <= py0) {
			return;
		}
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		g.fill(px0, py0, px1, py1, argb);
		pose.popPose();
	}
}
