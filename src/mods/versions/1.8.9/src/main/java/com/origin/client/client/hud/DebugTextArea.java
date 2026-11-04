package com.origin.client.client.hud;

import net.minecraft.client.gui.FontRenderer;

import java.util.List;

/**
 * 1.8.9 ONLY: where the F3 debug text sits this frame, so the Origin HUD hides only the
 * elements underneath it (1.21.1 keeps its HUD with F3 open; 1.8.9 used to hide all of it).
 *
 * <p>Forge 1.8.9 draws the debug text in GuiIngameForge.renderHUDText from the two string
 * lists of RenderGameOverlayEvent.Text (other mods add lines to them too): left lines from
 * (2, 2), right lines right-aligned 2 px from the edge, one FONT_HEIGHT row per non-null
 * line, each on a box 1 px wider on every side. HudHooks hands the lists over when the
 * event fires ({@link #text}) and clears them at the start of every HUD pass
 * ({@link #begin}); the boxes are measured once per frame, on first use, after every mod
 * has added its lines (the HUD draws at Post(ALL), after the text).
 *
 * <p>Fail-soft: no Text event seen this frame while F3 is open ({@link #known} false) =
 * the old behaviour, the whole HUD hides.
 */
public final class DebugTextArea {
	private DebugTextArea() {
	}

	private static List<String> left, right;
	private static boolean measured;
	// Line boxes, GUI px: {x0, y0, x1, y1} per drawn line; count = used entries.
	private static int[] boxes = new int[64 * 4];
	private static int count;

	/** RenderGameOverlayEvent.Pre(ALL): a new HUD pass, nothing known yet. */
	public static void begin() {
		left = null;
		right = null;
		measured = false;
	}

	/** RenderGameOverlayEvent.Text: the lists Forge is about to draw (read later, once complete). */
	public static void text(List<String> l, List<String> r) {
		left = l;
		right = r;
		measured = false;
	}

	/** Whether this frame's debug text is known (else the caller hides the whole HUD). */
	public static boolean known() {
		return left != null || right != null;
	}

	/** True when an element's box (GUI px) overlaps a line of the debug text. */
	public static boolean covers(double x, double y, double w, double h, int screenW, FontRenderer font) {
		if (!measured) {
			measured = true;
			count = 0;
			add(left, false, screenW, font);
			add(right, true, screenW, font);
		}
		for (int i = 0; i < count; i++) {
			int o = i * 4;
			if (x < boxes[o + 2] && x + w > boxes[o] && y < boxes[o + 3] && y + h > boxes[o + 1]) {
				return true;
			}
		}
		return false;
	}

	// Forge's own layout (GuiIngameForge.renderHUDText): null lines are skipped without a row.
	private static void add(List<String> lines, boolean rightSide, int screenW, FontRenderer font) {
		if (lines == null) {
			return;
		}
		int top = 2;
		for (int i = 0; i < lines.size(); i++) {
			String s = lines.get(i);
			if (s == null) {
				continue;
			}
			int w = font.getStringWidth(s);
			if (w > 0) {
				int x0 = rightSide ? screenW - 2 - w : 2;
				if ((count + 1) * 4 > boxes.length) {
					boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2);
				}
				int o = count * 4;
				boxes[o] = x0 - 1;
				boxes[o + 1] = top - 1;
				boxes[o + 2] = x0 + w + 1;
				boxes[o + 3] = top + font.FONT_HEIGHT - 1;
				count++;
			}
			top += font.FONT_HEIGHT;
		}
	}
}
