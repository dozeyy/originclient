package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

/**
 * Physical-pixel helpers for Origin's drawing around Minecraft's menus (the
 * loading bar, the logo's pixel grid). Minecraft's own controls are stock
 * widgets (Will, 2026-10-19), so no textured glass surface lives here; what is
 * here draws at exactly one screen pixel at any GUI scale (smooth-ui-baked-masks).
 *
 * <p>26.x port of 1.21.1's OriginSurface: the pose is a Matrix3x2fStack
 * (pushMatrix / scale(x, y) / popMatrix) instead of a PoseStack.
 */
public final class OriginSurface {
	private OriginSurface() {
	}

	/** Physical-pixel GUI scale (integer in vanilla, 1 at minimum). */
	public static int scale() {
		return (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	/** Fills a GUI-px span at physical precision: x0..x1 may be fractional (bars). */
	public static void fillPhysical(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int argb) {
		int gs = scale();
		int px0 = (int) Math.round(x0 * gs), px1 = (int) Math.round(x1 * gs);
		int py0 = (int) Math.round(y0 * gs), py1 = (int) Math.round(y1 * gs);
		if (px1 <= px0 || py1 <= py0) {
			return;
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
		g.fill(px0, py0, px1, py1, argb);
		pose.popMatrix();
	}
}
