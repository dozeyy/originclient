package com.origin.client.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Physical-pixel helpers for Origin's drawing around Minecraft's menus (the
 * loading bar, the panorama blur's pixel grid). Minecraft's own controls are
 * stock widgets (Will, 2026-10-19), so the textured glass surface that lived
 * here is gone; what's left draws at exactly one screen pixel at any GUI scale
 * (smooth-ui-baked-masks).
 */
public final class OriginSurface {
	private OriginSurface() {
	}

	/** Physical-pixel GUI scale (integer in vanilla, 1 at minimum). */
	public static int scale() {
		return (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	/** Fills a GUI-px span at physical precision: x0..x1 may be fractional (bars). */
	public static void fillPhysical(GuiGraphics g, double x0, double y0, double x1, double y1, int argb) {
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
