package com.origin.client.client.gui;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

// The one shared color picker, reused for every color option across every mod
// (spec §6). A modal overlay the mod menu draws last and feeds input to first.
// Edits the live value through Mods.color/set; chroma settings persist as
// sibling keys (key#chroma / key#speed / key#type) so any consumer can animate
// the hue. HSV editing throughout: the 2D field is saturation×brightness, the
// vertical sliders are hue and alpha.
public final class OriginColorPicker {
	private OriginColorPicker() {
	}

	public static final String[] CHROMA_TYPES = {"Wave", "Spread", "Solid Cycle"};

	private static boolean open = false;
	private static String modId, key, title;
	// chroma is hidden for consumers whose renderer can't animate it (block outline)
	private static boolean allowChroma = true;

	// working HSVA (0..360, 0..1, 0..1, 0..255)
	private static float h, s, v;
	private static int alpha;

	private static int px, py;
	private static final int PW = 234, PH = 236;
	private static int drag = 0; // 0 none, 1 field, 2 hue, 3 alpha, 4 speed

	public static boolean isOpen() {
		return open;
	}

	public static void open(String modId, String key, String label) {
		OriginColorPicker.modId = modId;
		OriginColorPicker.key = key;
		OriginColorPicker.title = label;
		OriginColorPicker.allowChroma = true;
		int argb = Mods.color(modId, key);
		float[] hsv = argbToHsv(argb);
		h = hsv[0];
		s = hsv[1];
		v = hsv[2];
		alpha = (argb >>> 24) & 0xFF;
		open = true;
		drag = 0;
	}

	public static void close() {
		open = false;
		drag = 0;
	}

	// sibling-key accessors any consumer can read to animate chroma
	public static boolean chromaOn(String modId, String key) {
		return Mods.bool(modId, key + "#chroma");
	}

	/** The color a consumer should draw RIGHT NOW: the picked color, or the
	 *  chroma-cycled hue (at the picked alpha) when chroma is on. */
	public static int liveColor(String modId, String key) {
		int base = Mods.color(modId, key);
		if (!chromaOn(modId, key)) {
			return base;
		}
		double speed = chromaSpeed(modId, key); // 1..100
		float hue = (float) ((System.currentTimeMillis() * speed * 0.004) % 360.0);
		return hsvToArgb(hue, 1f, 1f, (base >>> 24) & 0xFF);
	}

	public static double chromaSpeed(String modId, String key) {
		double sp = Mods.num(modId, key + "#speed");
		return sp <= 0 ? 40 : sp;
	}

	// ---- geometry ----
	private int fieldX() { return px + 12; }
	private int fieldY() { return py + 46; }
	private static final int FIELD_W = 150, FIELD_H = 108;
	private int hueX() { return px + 12 + FIELD_W + 10; }
	private int alphaX() { return hueX() + 24; }
	private int barY() { return fieldY(); }
	private static final int BAR_W = 14;
	private int chromaSwX() { return px + PW - 12 - 30; }
	private int chromaSwY() { return py + 26; }
	private int speedX() { return px + 12; }
	private int speedY() { return fieldY() + FIELD_H + 16; }
	private static final int SPEED_W = 120;
	// Type dropdown sits on its OWN row below the speed slider (it used to
	// overlap the speed value at every window size).
	private int typeX() { return px + 12; }
	private int typeY() { return speedY() + 18; }
	private int presetY() { return typeY() + 22; }

	// ---- render ----
	public static void render(GuiGraphicsExtractor g, int mx, int my) {
		if (!open) {
			return;
		}
		new OriginColorPicker().draw(g, mx, my);
	}

	private void draw(GuiGraphicsExtractor g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		px = Math.max(2, (sw - PW) / 2);
		py = Math.max(2, (sh - PH) / 2);

		// scrim + panel
		g.fill(0, 0, sw, sh, 0x66000000);
		OriginUi.panel(g, px, py, PW, PH, 12, 0xF2101010, OriginTheme.STROKE_STRONG);

		// header
		g.text(font, title == null ? "Color" : title, px + 12, py + 10, OriginTheme.TEXT, false);
		boolean closeHover = in(mx, my, px + PW - 26, py + 8, px + PW - 8, py + 24);
		g.text(font, "✕", px + PW - 22, py + 10, closeHover ? OriginTheme.TEXT : OriginTheme.MUTED, false);

		// chroma switch + label (hidden for non-animatable consumers)
		if (allowChroma) {
			g.text(font, "Chroma", chromaSwX() - 4 - font.width("Chroma"), chromaSwY() + 4, OriginTheme.TEXT_DIM, false);
			OriginUi.switchAt(g, "cp:chroma", chromaSwX(), chromaSwY(), 30, chromaOn(modId, key), true);
		}

		// 2D saturation/brightness field: white->hue horizontally, then a
		// transparent->black vertical overlay = the standard SB square.
		int fx = fieldX(), fy = fieldY();
		int hueColor = hsvToArgb(h, 1f, 1f, 255);
		int strips = FIELD_W;
		for (int i = 0; i < strips; i++) {
			int c = OriginTheme.lerpColor(0xFFFFFFFF, hueColor, i / (double) (strips - 1));
			g.fill(fx + i, fy, fx + i + 1, fy + FIELD_H, c);
		}
		g.fillGradient(fx, fy, fx + FIELD_W, fy + FIELD_H, 0x00000000, 0xFF000000);
		OriginUi.panel(g, fx - 1, fy - 1, FIELD_W + 2, FIELD_H + 2, 3, 0, OriginTheme.STROKE);
		// field cursor
		int cxp = fx + Math.round(s * FIELD_W);
		int cyp = fy + Math.round((1 - v) * FIELD_H);
		g.fill(cxp - 3, cyp - 1, cxp + 3, cyp, 0xFFFFFFFF);
		g.fill(cxp - 1, cyp - 3, cxp, cyp + 3, 0xFFFFFFFF);

		// hue slider (vertical rainbow)
		int hx = hueX();
		int hstrips = FIELD_H;
		for (int i = 0; i < hstrips; i++) {
			float hh = (i / (float) (hstrips - 1)) * 360f;
			g.fill(hx, barY() + i, hx + BAR_W, barY() + i + 1, hsvToArgb(hh, 1f, 1f, 255));
		}
		OriginUi.panel(g, hx - 1, barY() - 1, BAR_W + 2, FIELD_H + 2, 3, 0, OriginTheme.STROKE);
		int hy2 = barY() + Math.round(h / 360f * FIELD_H);
		g.fill(hx - 2, hy2 - 1, hx + BAR_W + 2, hy2 + 1, 0xFFFFFFFF);

		// alpha slider: fully invisible at the TOP, fully visible at the BOTTOM.
		// Drawn as per-row strips (not fillGradient, whose 0-alpha endpoint reads
		// as opaque) so the fade is guaranteed linear over the checker.
		int ax = alphaX();
		checker(g, ax, barY(), BAR_W, FIELD_H);
		int solidRGB = hsvToArgb(h, s, v, 255) & 0xFFFFFF;
		for (int i = 0; i < FIELD_H; i++) {
			int a = Math.round(255f * i / (FIELD_H - 1));
			g.fill(ax, barY() + i, ax + BAR_W, barY() + i + 1, (a << 24) | solidRGB);
		}
		OriginUi.panel(g, ax - 1, barY() - 1, BAR_W + 2, FIELD_H + 2, 3, 0, OriginTheme.STROKE);
		// handle matches the gradient: alpha 0 at the top, 255 at the bottom
		int ay2 = barY() + Math.round(alpha / 255f * FIELD_H);
		g.fill(ax - 2, ay2 - 1, ax + BAR_W + 2, ay2 + 1, 0xFFFFFFFF);

		// speed slider + type dropdown — chroma-only controls
		if (allowChroma) {
			g.text(font, "Speed", speedX(), speedY() - 12, OriginTheme.MUTED, false);
			double speed = chromaSpeed(modId, key);
			OriginUi.slider(g, speedX(), speedY(), SPEED_W, clamp01((speed - 1) / 99.0), drag == 4);
			g.text(font, String.format("%.0f", speed), speedX() + SPEED_W + 8, speedY() - 4, OriginTheme.TEXT_DIM, false);

			String type = chromaType();
			boolean tHover = in(mx, my, typeX(), typeY(), typeX() + 92, typeY() + 18);
			OriginUi.panel(g, typeX(), typeY(), 92, 18, 7, tHover ? 0x24FFFFFF : 0x14FFFFFF, OriginTheme.STROKE);
			g.text(font, "<", typeX() + 6, typeY() + 5, OriginTheme.TEXT_DIM, false);
			g.text(font, type, typeX() + (92 - font.width(type)) / 2, typeY() + 5, OriginTheme.TEXT, false);
			g.text(font, ">", typeX() + 92 - 6 - font.width(">"), typeY() + 5, OriginTheme.TEXT_DIM, false);
		}

		// preset palette + hex
		int argb = current();
		String hex = String.format("#%08X", argb);
		g.text(font, hex, px + 12, presetY() + 4, OriginTheme.TEXT_DIM, false);
		int psw = 12, pgap = 4;
		int startX = px + PW - 12 - Mods.PALETTE.length * (psw + pgap) + pgap;
		for (int i = 0; i < Mods.PALETTE.length; i++) {
			int cx = startX + i * (psw + pgap);
			OriginUi.panel(g, cx, presetY(), psw, psw, 4, Mods.PALETTE[i], 0x40000000);
		}
	}

	// ---- input ----
	public static boolean mouseClicked(double mx, double my, int button) {
		if (!open) {
			return false;
		}
		OriginColorPicker cp = new OriginColorPicker();
		cp.px = Math.max(2, (Minecraft.getInstance().getWindow().getGuiScaledWidth() - PW) / 2);
		cp.py = Math.max(2, (Minecraft.getInstance().getWindow().getGuiScaledHeight() - PH) / 2);
		return cp.click(mx, my, button);
	}

	private boolean click(double mx, double my, int button) {
		if (in(mx, my, px + PW - 26, py + 8, px + PW - 8, py + 24)) {
			close();
			return true;
		}
		if (!in(mx, my, px, py, px + PW, py + PH)) {
			close(); // click outside dismisses
			return true;
		}
		// Hit-test exactly the drawn pill (switchAt draws 30 x 30*8/15=16 here),
		// so only clicks on the switch itself toggle it — no slop around the edge.
		if (allowChroma && in(mx, my, chromaSwX(), chromaSwY(), chromaSwX() + 30, chromaSwY() + 16)) {
			Mods.set(modId, key + "#chroma", !chromaOn(modId, key));
			return true;
		}
		if (in(mx, my, fieldX(), fieldY(), fieldX() + FIELD_W, fieldY() + FIELD_H)) {
			drag = 1;
			applyField(mx, my);
			return true;
		}
		if (in(mx, my, hueX(), barY(), hueX() + BAR_W, barY() + FIELD_H)) {
			drag = 2;
			applyHue(my);
			return true;
		}
		if (in(mx, my, alphaX(), barY(), alphaX() + BAR_W, barY() + FIELD_H)) {
			drag = 3;
			applyAlpha(my);
			return true;
		}
		if (allowChroma && in(mx, my, speedX(), speedY() - 4, speedX() + SPEED_W, speedY() + 10)) {
			drag = 4;
			applySpeed(mx);
			return true;
		}
		if (allowChroma && in(mx, my, typeX(), typeY(), typeX() + 92, typeY() + 18)) {
			cycleType(button == 1 ? -1 : 1);
			return true;
		}
		int psw = 12, pgap = 4;
		int startX = px + PW - 12 - Mods.PALETTE.length * (psw + pgap) + pgap;
		for (int i = 0; i < Mods.PALETTE.length; i++) {
			int cx = startX + i * (psw + pgap);
			if (in(mx, my, cx, presetY(), cx + psw, presetY() + psw)) {
				float[] hsv = argbToHsv(Mods.PALETTE[i]);
				h = hsv[0];
				s = hsv[1];
				v = hsv[2];
				commit();
				return true;
			}
		}
		return true; // modal: swallow everything inside the panel
	}

	public static boolean mouseDragged(double mx, double my, int button) {
		if (!open || drag == 0) {
			return false;
		}
		OriginColorPicker cp = new OriginColorPicker();
		cp.px = Math.max(2, (Minecraft.getInstance().getWindow().getGuiScaledWidth() - PW) / 2);
		cp.py = Math.max(2, (Minecraft.getInstance().getWindow().getGuiScaledHeight() - PH) / 2);
		switch (drag) {
			case 1 -> cp.applyField(mx, my);
			case 2 -> cp.applyHue(my);
			case 3 -> cp.applyAlpha(my);
			case 4 -> cp.applySpeed(mx);
		}
		return true;
	}

	public static boolean mouseReleased() {
		if (!open) {
			return false;
		}
		drag = 0;
		return true;
	}

	public static boolean keyPressed(int keyCode) {
		if (!open) {
			return false;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			close();
		}
		return true; // swallow keys while modal
	}

	private void applyField(double mx, double my) {
		s = (float) clamp01((mx - fieldX()) / (double) FIELD_W);
		v = (float) (1 - clamp01((my - fieldY()) / (double) FIELD_H));
		commit();
	}

	private void applyHue(double my) {
		h = (float) (clamp01((my - barY()) / (double) FIELD_H) * 360.0);
		commit();
	}

	private void applyAlpha(double my) {
		// top = transparent (0), bottom = opaque (255) — matches the gradient
		alpha = (int) Math.round(clamp01((my - barY()) / (double) FIELD_H) * 255);
		commit();
	}

	private void applySpeed(double mx) {
		double t = clamp01((mx - speedX()) / (double) SPEED_W);
		Mods.set(modId, key + "#speed", Math.round(1 + t * 99));
	}

	private void cycleType(int dir) {
		String cur = chromaType();
		int idx = 0;
		for (int i = 0; i < CHROMA_TYPES.length; i++) {
			if (CHROMA_TYPES[i].equals(cur)) {
				idx = i;
			}
		}
		idx = (idx + dir + CHROMA_TYPES.length) % CHROMA_TYPES.length;
		Mods.set(modId, key + "#type", CHROMA_TYPES[idx]);
	}

	private String chromaType() {
		String t = Mods.mode(modId, key + "#type");
		return t == null || t.isEmpty() ? CHROMA_TYPES[0] : t;
	}

	private int current() {
		return hsvToArgb(h, s, v, alpha);
	}

	private void commit() {
		Mods.set(modId, key, current());
	}

	// ---- helpers ----
	private static void checker(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int cs = 4;
		for (int yy = 0; yy < h; yy += cs) {
			for (int xx = 0; xx < w; xx += cs) {
				boolean dark = ((xx / cs) + (yy / cs)) % 2 == 0;
				g.fill(x + xx, y + yy, Math.min(x + xx + cs, x + w), Math.min(y + yy + cs, y + h),
						dark ? 0xFF3A3A3A : 0xFF6A6A6A);
			}
		}
	}

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	static int hsvToArgb(float h, float s, float v, int a) {
		h = ((h % 360f) + 360f) % 360f;
		float c = v * s;
		float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
		float m = v - c;
		float r = 0, g = 0, b = 0;
		if (h < 60) { r = c; g = x; }
		else if (h < 120) { r = x; g = c; }
		else if (h < 180) { g = c; b = x; }
		else if (h < 240) { g = x; b = c; }
		else if (h < 300) { r = x; b = c; }
		else { r = c; b = x; }
		int ri = Math.round((r + m) * 255);
		int gi = Math.round((g + m) * 255);
		int bi = Math.round((b + m) * 255);
		return ((a & 0xFF) << 24) | (ri << 16) | (gi << 8) | bi;
	}

	static float[] argbToHsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float hh;
		if (d == 0) {
			hh = 0;
		} else if (max == r) {
			hh = 60 * (((g - b) / d) % 6);
		} else if (max == g) {
			hh = 60 * (((b - r) / d) + 2);
		} else {
			hh = 60 * (((r - g) / d) + 4);
		}
		if (hh < 0) {
			hh += 360;
		}
		float ss = max == 0 ? 0 : d / max;
		return new float[]{hh, ss, max};
	}
}
