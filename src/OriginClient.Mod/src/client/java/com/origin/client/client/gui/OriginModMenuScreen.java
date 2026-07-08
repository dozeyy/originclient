package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.hud.HudEditorScreen;
import com.origin.client.client.mods.ModOption;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The Right Shift panel: the single home for every Origin mod. Slides up
// from the bottom in ~180ms (pose translate only — GPU-cheap), occupies the
// center 75% of the screen, and hosts: a live-filter search bar, the mod
// grid (icon + name + toggle per box, toggle swallows its click, the rest
// of the box flips the SAME box to its settings face, Lunar-style), a HUD
// Editor button, and a backing-visibility toggle that hides the translucent
// panel behind the boxes. Non-pausing; Right Shift or Esc closes (reversed
// slide-down).
public class OriginModMenuScreen extends Screen {
	private static final long SLIDE_MS = 180;
	private static final long FACE_MS = 120;

	private final long openedAt = System.currentTimeMillis();
	private long closingAt = -1;

	private String search = "";
	private double scroll = 0;

	// Per-mod settings-face state: present = settings open; value = when it flipped.
	private final Map<String, Long> settingsFace = new HashMap<>();
	// Per-toggle knob animation state (id -> last flip time).
	private final Map<String, Long> knobAnim = new HashMap<>();

	// Active slider drag: {modId, optionKey} + its track rect.
	private String dragMod = null, dragKey = null;
	private int dragTrackX0, dragTrackX1;

	// Active keybind capture: {modId, optionKey}.
	private String capMod = null, capKey = null;

	public OriginModMenuScreen() {
		super(Component.literal("Origin Mods"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // handled manually so Esc plays the slide-down
	}

	// ---- geometry ----

	private int panelX() {
		return (int) (width * 0.125);
	}

	private int panelY() {
		return (int) (height * 0.125);
	}

	private int panelW() {
		return (int) (width * 0.75);
	}

	private int panelH() {
		return (int) (height * 0.75);
	}

	private int cellW = 118, cellH = 92, gap = 8, cols = 4;
	private List<Mods.Mod> filtered = new ArrayList<>();

	private void layout() {
		int innerW = panelW() - 16;
		cols = Math.max(3, (innerW + gap) / (cellW + gap));
		filtered.clear();
		String q = search.toLowerCase();
		for (Mods.Mod m : Mods.ALL) {
			if (q.isEmpty() || m.name().toLowerCase().contains(q)) {
				filtered.add(m);
			}
		}
	}

	private int gridTop() {
		return panelY() + 34;
	}

	private int gridLeft() {
		int usable = panelW() - 16;
		int rowW = cols * cellW + (cols - 1) * gap;
		return panelX() + 8 + (usable - rowW) / 2;
	}

	private int[] cellRect(int index) {
		int col = index % cols, row = index / cols;
		int x = gridLeft() + col * (cellW + gap);
		int y = gridTop() + row * (cellH + gap) - (int) scroll;
		return new int[]{x, y, x + cellW, y + cellH};
	}

	private double maxScroll() {
		int rows = (filtered.size() + cols - 1) / cols;
		int contentH = rows * (cellH + gap) - gap;
		int viewH = panelY() + panelH() - 8 - gridTop();
		return Math.max(0, contentH - viewH);
	}

	// ---- render ----

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		layout();

		// Slide progress: up on open, reversed while closing; when the close
		// slide finishes, actually leave the screen.
		long now = System.currentTimeMillis();
		double p = OriginTheme.easeOut(Math.min(1.0, (now - openedAt) / (double) SLIDE_MS));
		if (closingAt > 0) {
			double cp = Math.min(1.0, (now - closingAt) / (double) SLIDE_MS);
			p = OriginTheme.easeOut(1.0 - cp);
			if (cp >= 1.0) {
				Minecraft.getInstance().setScreen(null);
				return;
			}
		}
		double slide = (1.0 - p) * (height - panelY());

		var pose = g.pose();
		pose.pushPose();
		pose.translate(0, slide, 0);

		int px = panelX(), py = panelY(), pw = panelW(), ph = panelH();

		// Panel backing (toggleable to fully invisible; boxes stay).
		boolean backing = Mods.metaBool("panelBacking", true);
		if (backing) {
			g.fill(px, py, px + pw, py + ph, 0xB0101010);
			frame(g, px, py, px + pw, py + ph, OriginTheme.STROKE);
		}

		// -- header band: search field, HUD editor, backing toggle --
		int hy = py + 8;
		int searchW = Math.min(220, pw / 2);
		int sx = px + 8;
		g.fill(sx, hy, sx + searchW, hy + 18, 0x66000000);
		frame(g, sx, hy, sx + searchW, hy + 18, OriginTheme.STROKE);
		String shown = search.isEmpty() ? "Search mods..." : search;
		int searchColor = search.isEmpty() ? OriginTheme.MUTED : OriginTheme.TEXT;
		g.drawString(font, shown, sx + 6, hy + 5, searchColor, false);
		if (!search.isEmpty() || (now / 500) % 2 == 0) {
			int cw = font.width(search);
			g.fill(sx + 6 + cw + 1, hy + 4, sx + 6 + cw + 2, hy + 14, OriginTheme.TEXT);
		}

		// HUD editor button
		int hbW = font.width("HUD Editor") + 14;
		int hbX = px + pw - 8 - 22 - 6 - hbW;
		boolean hbHover = in(mouseX, mouseY, hbX, hy, hbX + hbW, hy + 18);
		g.fill(hbX, hy, hbX + hbW, hy + 18, hbHover ? 0x28FFFFFF : 0x14FFFFFF);
		frame(g, hbX, hy, hbX + hbW, hy + 18, hbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE);
		g.drawString(font, "HUD Editor", hbX + 7, hy + 5, OriginTheme.TEXT, false);

		// backing-visibility toggle (eye-ish square)
		int vbX = px + pw - 8 - 22;
		boolean vbHover = in(mouseX, mouseY, vbX, hy, vbX + 22, hy + 18);
		g.fill(vbX, hy, vbX + 22, hy + 18, vbHover ? 0x28FFFFFF : 0x14FFFFFF);
		frame(g, vbX, hy, vbX + 22, hy + 18, vbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE);
		g.fill(vbX + 7, hy + 7, vbX + 15, hy + 11, backing ? OriginTheme.TEXT : OriginTheme.MUTED);

		// -- grid (scissored to the panel) --
		g.enableScissor(px, gridTop(), px + pw, py + ph - 6);
		for (int i = 0; i < filtered.size(); i++) {
			int[] r = cellRect(i);
			if (r[3] < gridTop() - cellH || r[1] > py + ph) {
				continue;
			}
			drawBox(g, filtered.get(i), r, mouseX, mouseY, now);
		}
		g.disableScissor();

		pose.popPose();
	}

	private void drawBox(GuiGraphics g, Mods.Mod mod, int[] r, int mx, int my, long now) {
		boolean hover = in(mx, my, r[0], r[1], r[2], r[3]);
		g.fill(r[0], r[1], r[2], r[3], hover ? 0x24FFFFFF : 0x16FFFFFF);
		frame(g, r[0], r[1], r[2], r[3], hover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE);

		Long flipped = settingsFace.get(mod.id());
		// Content alpha: quick fade when the face flips (same box, same
		// footprint — the interior swaps, per the Lunar model).
		float alpha = flipped == null ? 1f
				: (float) Math.min(1.0, (now - flipped) / (double) FACE_MS);
		int text = withAlpha(OriginTheme.TEXT, alpha);
		int muted = withAlpha(OriginTheme.MUTED, alpha);

		if (flipped == null) {
			// grid face: icon, name, toggle
			boolean on = Mods.on(mod.id());
			ModIcons.draw(g, mod.id(), r[0] + (cellW - 18) / 2, r[1] + 10, on ? OriginTheme.TEXT : OriginTheme.MUTED);
			String name = font.width(mod.name()) > cellW - 10
					? font.plainSubstrByWidth(mod.name(), cellW - 14) + "…" : mod.name();
			g.drawString(font, name, r[0] + (cellW - font.width(name)) / 2, r[1] + 36, text, false);
			drawSwitch(g, mod.id(), r[0] + (cellW - 22) / 2, r[1] + cellH - 22, on, now);
		} else {
			// settings face: header (back + name), then option rows
			g.drawString(font, "<", r[0] + 6, r[1] + 6, muted, false);
			String name = font.plainSubstrByWidth(mod.name(), cellW - 26);
			g.drawString(font, name, r[0] + 16, r[1] + 6, text, false);
			int y = r[1] + 20;
			y = drawRow(g, mod.id(), enabledRow(), r[0], y, now, alpha);
			for (ModOption o : mod.options()) {
				y = drawRow(g, mod.id(), o, r[0], y, now, alpha);
			}
		}
	}

	private static ModOption enabledRow() {
		return ModOption.toggle("enabled", "Enabled", false);
	}

	/** One settings row: label left, control right. Returns next row y. */
	private int drawRow(GuiGraphics g, String modId, ModOption o, int x, int y, long now, float alpha) {
		int rowH = 14;
		int cx1 = x + cellW - 6;       // control right edge
		int labelColor = withAlpha(OriginTheme.TEXT_DIM, alpha);
		String label = font.plainSubstrByWidth(o.label, cellW - 66);
		g.drawString(font, label, x + 6, y + 3, labelColor, false);

		switch (o.kind) {
			case TOGGLE -> {
				boolean v = o.key.equals("enabled") ? Mods.on(modId) : Mods.bool(modId, o.key);
				drawSwitch(g, modId + ":" + o.key, cx1 - 22, y + 2, v, now);
			}
			case SLIDER -> {
				double v = o.key.equals("fov") && modId.equals("zoom") ? Mods.num(modId, o.key) : Mods.num(modId, o.key);
				double t = (v - o.min) / (o.max - o.min);
				int trackW = 34;
				int tx = cx1 - trackW;
				g.fill(tx, y + 6, tx + trackW, y + 8, withAlpha(0xFF3A3A3A, alpha));
				int hx = tx + (int) Math.round(t * (trackW - 3));
				g.fill(hx, y + 3, hx + 3, y + 11, withAlpha(OriginTheme.TEXT, alpha));
				String val = o.format.contains("%%") ? String.format(o.format, v * 100) : String.format(o.format, v);
				g.drawString(font, val, tx - font.width(val) - 4, y + 3, labelColor, false);
			}
			case COLOR -> {
				int cur = Mods.color(modId, o.key);
				int n = o.swatches.length;
				int sw = 6;
				int startX = cx1 - n * (sw + 1);
				for (int i = 0; i < n; i++) {
					int px = startX + i * (sw + 1);
					g.fill(px, y + 4, px + sw, y + 10, o.swatches[i]);
					if (o.swatches[i] == cur) {
						frame(g, px - 1, y + 3, px + sw + 1, y + 11, OriginTheme.TEXT);
					}
				}
			}
			case KEYBIND -> {
				boolean capturing = modId.equals(capMod) && o.key.equals(capKey);
				String name = capturing ? "..." : keyName(Mods.keyCode(modId, o.key));
				int bw = Math.max(24, font.width(name) + 8);
				int bx = cx1 - bw;
				g.fill(bx, y + 1, cx1, y + 12, capturing ? 0x40FFFFFF : 0x22FFFFFF);
				g.drawString(font, name, bx + 4, y + 3, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case MODE -> {
				String v = Mods.mode(modId, o.key);
				int bw = Math.max(24, font.width(v) + 8);
				int bx = cx1 - bw;
				g.fill(bx, y + 1, cx1, y + 12, 0x22FFFFFF);
				g.drawString(font, v, bx + 4, y + 3, withAlpha(OriginTheme.TEXT, alpha), false);
			}
		}
		return y + rowH;
	}

	/** Small animated pill switch, 22x10. Key identifies its knob animation. */
	private void drawSwitch(GuiGraphics g, String animKey, int x, int y, boolean on, long now) {
		Long flip = knobAnim.get(animKey);
		float t = flip == null ? 1f : (float) Math.min(1.0, (now - flip) / 120.0);
		float k = on ? t : 1f - t;
		int track = on ? 0x66E0E0E0 : 0x33FFFFFF;
		g.fill(x, y + 2, x + 22, y + 8, track);
		int knobX = x + 1 + Math.round(k * 12);
		g.fill(knobX, y, knobX + 8, y + 10, on ? 0xFFE8E8E8 : 0xFF9A9A9A);
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0 || closingAt > 0) {
			return super.mouseClicked(mx, my, button);
		}
		layout();
		long now = System.currentTimeMillis();
		int px = panelX(), py = panelY(), pw = panelW();
		int hy = py + 8;

		// header controls
		int searchW = Math.min(220, pw / 2);
		int hbW = font.width("HUD Editor") + 14;
		int hbX = px + pw - 8 - 22 - 6 - hbW;
		int vbX = px + pw - 8 - 22;
		if (in(mx, my, hbX, hy, hbX + hbW, hy + 18)) {
			Minecraft.getInstance().setScreen(new HudEditorScreen());
			return true;
		}
		if (in(mx, my, vbX, hy, vbX + 22, hy + 18)) {
			Mods.setMetaBool("panelBacking", !Mods.metaBool("panelBacking", true));
			return true;
		}

		// grid boxes
		for (int i = 0; i < filtered.size(); i++) {
			int[] r = cellRect(i);
			if (!in(mx, my, r[0], r[1], r[2], r[3])) {
				continue;
			}
			Mods.Mod mod = filtered.get(i);
			Long flipped = settingsFace.get(mod.id());

			if (flipped == null) {
				// grid face: precise toggle hitbox first, else open settings
				int tx = r[0] + (cellW - 22) / 2, ty = r[1] + cellH - 22;
				if (in(mx, my, tx - 3, ty - 3, tx + 25, ty + 13)) {
					Mods.setOn(mod.id(), !Mods.on(mod.id()));
					knobAnim.put(mod.id(), now);
				} else {
					settingsFace.put(mod.id(), now);
				}
				return true;
			}

			// settings face
			if (in(mx, my, r[0], r[1], r[0] + 16, r[1] + 16)) { // back
				settingsFace.remove(mod.id());
				return true;
			}
			int y = r[1] + 20;
			if (clickRow(mod.id(), enabledRow(), r[0], y, mx, my, now)) {
				return true;
			}
			y += 14;
			for (ModOption o : mod.options()) {
				if (clickRow(mod.id(), o, r[0], y, mx, my, now)) {
					return true;
				}
				y += 14;
			}
			return true; // click inside the box never falls through
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickRow(String modId, ModOption o, int x, int rowY, double mx, double my, long now) {
		if (my < rowY || my >= rowY + 14) {
			return false;
		}
		int cx1 = x + cellW - 6;
		switch (o.kind) {
			case TOGGLE -> {
				if (mx >= cx1 - 26) {
					if (o.key.equals("enabled")) {
						Mods.setOn(modId, !Mods.on(modId));
						knobAnim.put(modId, now); // grid switch mirrors
					} else {
						Mods.set(modId, o.key, !Mods.bool(modId, o.key));
					}
					knobAnim.put(modId + ":" + o.key, now);
					return true;
				}
			}
			case SLIDER -> {
				int trackW = 34;
				int tx = cx1 - trackW;
				if (mx >= tx - 4 && mx <= cx1 + 2) {
					dragMod = modId;
					dragKey = o.key;
					dragTrackX0 = tx;
					dragTrackX1 = tx + trackW;
					applySlider(o, mx);
					return true;
				}
			}
			case COLOR -> {
				int n = o.swatches.length;
				int sw = 6;
				int startX = cx1 - n * (sw + 1);
				for (int i = 0; i < n; i++) {
					int pxx = startX + i * (sw + 1);
					if (mx >= pxx - 1 && mx <= pxx + sw + 1) {
						Mods.set(modId, o.key, o.swatches[i]);
						return true;
					}
				}
			}
			case KEYBIND -> {
				if (mx >= cx1 - 44) {
					capMod = modId;
					capKey = o.key;
					return true;
				}
			}
			case MODE -> {
				if (mx >= cx1 - 44) {
					String cur = Mods.mode(modId, o.key);
					int idx = 0;
					for (int i = 0; i < o.modes.length; i++) {
						if (o.modes[i].equals(cur)) {
							idx = i;
						}
					}
					Mods.set(modId, o.key, o.modes[(idx + 1) % o.modes.length]);
					return true;
				}
			}
		}
		return false;
	}

	private void applySlider(ModOption o, double mx) {
		double t = Math.max(0, Math.min(1, (mx - dragTrackX0) / (double) (dragTrackX1 - dragTrackX0)));
		double v = o.min + t * (o.max - o.min);
		v = Math.round(v / o.step) * o.step;
		Mods.set(dragMod, o.key, v);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragMod != null) {
			Mods.Mod m = Mods.byId(dragMod);
			if (m != null) {
				for (ModOption o : m.options()) {
					if (o.key.equals(dragKey)) {
						applySlider(o, mx);
					}
				}
			}
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		dragMod = null;
		dragKey = null;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 24));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// keybind capture eats the next key
		if (capMod != null) {
			if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
				Mods.set(capMod, capKey, keyCode);
			} else {
				Mods.set(capMod, capKey, -1);
			}
			capMod = null;
			capKey = null;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			beginClose();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scroll = 0;
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (capMod != null) {
			return true;
		}
		if (chr >= 32 && search.length() < 24) {
			search += chr;
			scroll = 0;
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	public void beginClose() {
		if (closingAt < 0) {
			closingAt = System.currentTimeMillis();
		}
	}

	// ---- helpers ----

	private static boolean in(double mx, double my, int x0, int y0, int x1, int y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y0 + 1, color);
		g.fill(x0, y1 - 1, x1, y1, color);
		g.fill(x0, y0, x0 + 1, y1, color);
		g.fill(x1 - 1, y0, x1, y1, color);
	}

	private static int withAlpha(int argb, float alpha) {
		int a = (int) (((argb >>> 24) & 0xFF) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static String keyName(int code) {
		if (code < 0) {
			return "None";
		}
		try {
			return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
		} catch (Throwable t) {
			return "Key " + code;
		}
	}
}
