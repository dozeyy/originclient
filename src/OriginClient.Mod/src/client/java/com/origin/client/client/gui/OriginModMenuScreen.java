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
import java.util.List;

// The Right Shift panel, redesigned as a premium desktop-app surface:
// navigating into a mod replaces the WHOLE overlay with that mod's settings
// page (fade + subtle scale, like moving between windows — never a nested
// panel), every control is a baked high-res asset (Apple-style switches,
// rounded panels, 96px icons), and the only pixelated thing on screen is
// Minecraft's font, by design. The centered Origin mark in the header is
// the entry point to HUD editing; the HUD Editor chip goes to the same
// workspace.
public class OriginModMenuScreen extends Screen {
	private static final long SLIDE_MS = 180;
	private static final long PAGE_MS = 170;

	private final long openedAt = System.currentTimeMillis();
	private long closingAt = -1;

	// page navigation: null = grid, otherwise the open mod's id
	private String page = null;
	private long pageChangedAt = 0;

	private String search = "";
	private double scroll = 0, scrollTarget = 0;
	private long lastFrameNanos = 0;

	private String dragMod = null, dragKey = null;
	private int dragTrackX0, dragTrackX1;
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
		return false;
	}

	// ---- geometry ----

	private int px() {
		return (int) (width * 0.125);
	}

	private int py() {
		return (int) (height * 0.125);
	}

	private int pw() {
		return (int) (width * 0.75);
	}

	private int ph() {
		return (int) (height * 0.75);
	}

	private int cellW = 122, cellH = 96, gap = 10, cols = 4;
	private final List<Mods.Mod> filtered = new ArrayList<>();

	private void layout() {
		cols = Math.max(3, (pw() - 24 + gap) / (cellW + gap));
		filtered.clear();
		String q = search.toLowerCase();
		for (Mods.Mod m : Mods.ALL) {
			if (q.isEmpty() || m.name().toLowerCase().contains(q)) {
				filtered.add(m);
			}
		}
	}

	private int gridTop() {
		return py() + 42;
	}

	private int gridLeft() {
		int rowW = cols * cellW + (cols - 1) * gap;
		return px() + (pw() - rowW) / 2;
	}

	private int[] cellRect(int i) {
		int col = i % cols, row = i / cols;
		int x = gridLeft() + col * (cellW + gap);
		int y = gridTop() + row * (cellH + gap) - (int) scroll;
		return new int[]{x, y, x + cellW, y + cellH};
	}

	private double maxScroll() {
		int rows = (filtered.size() + cols - 1) / cols;
		return Math.max(0, rows * (cellH + gap) - gap - (py() + ph() - 10 - gridTop()));
	}

	// ---- render ----

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		layout();
		long now = System.currentTimeMillis();

		// smooth scrolling
		long nanos = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 16.7 : Math.min(50.0, (nanos - lastFrameNanos) / 1_000_000.0);
		lastFrameNanos = nanos;
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		double p = OriginTheme.easeOut(Math.min(1.0, (now - openedAt) / (double) SLIDE_MS));
		if (closingAt > 0) {
			double cp = Math.min(1.0, (now - closingAt) / (double) SLIDE_MS);
			p = OriginTheme.easeOut(1.0 - cp);
			if (cp >= 1.0) {
				Minecraft.getInstance().setScreen(null);
				return;
			}
		}

		var pose = g.pose();
		pose.pushPose();
		pose.translate(0, (1.0 - p) * (height - py()), 0);

		// backing (toggleable to invisible; content panels stay)
		if (Mods.metaBool("panelBacking", true)) {
			OriginUi.panel(g, px(), py(), pw(), ph(), 10, 0xC80E0E0E, OriginTheme.STROKE);
		}

		// page transition: incoming page fades + scales in around panel center
		float t = (float) OriginTheme.easeOut(Math.min(1.0, (now - pageChangedAt) / (double) PAGE_MS));
		if (pageChangedAt == 0) {
			t = 1f;
		}
		pose.pushPose();
		float s = 0.985f + 0.015f * t;
		pose.translate(px() + pw() / 2.0, py() + ph() / 2.0, 0);
		pose.scale(s, s, 1f);
		pose.translate(-(px() + pw() / 2.0), -(py() + ph() / 2.0), 0);

		if (page == null) {
			renderGrid(g, mouseX, mouseY, now, t);
		} else {
			renderSettings(g, mouseX, mouseY, now, t, Mods.byId(page));
		}
		pose.popPose();

		// version stamp
		String ver = "Origin Client " + VERSION;
		g.drawString(font, ver, px() + pw() - 10 - font.width(ver), py() + ph() - 13, withAlpha(OriginTheme.MUTED, 0.8f), false);

		pose.popPose();
	}

	private void renderGrid(GuiGraphics g, int mouseX, int mouseY, long now, float alpha) {
		int hy = py() + 10;

		// centered Origin mark — the HUD editing entry point
		int mcx = px() + pw() / 2;
		boolean markHover = Math.abs(mouseX - mcx) < 14 && mouseY >= hy - 2 && mouseY < hy + 24;
		float mh = OriginUi.anim("markHover", markHover, 140.0);
		if (mh > 0.01f) {
			OriginUi.glow(g, mcx, hy + 10, (int) (48 + 10 * mh), 0.20f * mh);
		}
		OriginUi.mark(g, mcx, hy + 10, (int) (20 + 3 * mh), (0.75f + 0.25f * mh) * alpha);

		// search chip (left)
		int sw = Math.min(200, pw() / 3);
		int sx = px() + 12;
		OriginUi.panel(g, sx, hy, sw, 20, 8, withAlpha(0x66000000, alpha), withAlpha(OriginTheme.STROKE, alpha));
		String shown = search.isEmpty() ? "Search" : search;
		g.drawString(font, shown, sx + 8, hy + 6, withAlpha(search.isEmpty() ? OriginTheme.MUTED : OriginTheme.TEXT, alpha), false);
		if ((now / 530) % 2 == 0) {
			int cw = font.width(search);
			g.fill(sx + 8 + cw + 1, hy + 5, sx + 8 + cw + 2, hy + 15, withAlpha(OriginTheme.TEXT, alpha));
		}

		// right chips: HUD Editor + backing visibility
		int hbW = font.width("HUD Editor") + 16;
		int hbX = px() + pw() - 12 - 24 - 6 - hbW;
		boolean hbHover = in(mouseX, mouseY, hbX, hy, hbX + hbW, hy + 20);
		OriginUi.panel(g, hbX, hy, hbW, 20, 8,
				withAlpha(hbHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(hbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		g.drawString(font, "HUD Editor", hbX + 8, hy + 6, withAlpha(OriginTheme.TEXT, alpha), false);

		int vbX = px() + pw() - 12 - 24;
		boolean vbHover = in(mouseX, mouseY, vbX, hy, vbX + 24, hy + 20);
		boolean backing = Mods.metaBool("panelBacking", true);
		OriginUi.panel(g, vbX, hy, 24, 20, 8,
				withAlpha(vbHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(vbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		OriginUi.icon(g, "freelook", vbX + 4, hy + 2, 16, withAlpha(backing ? OriginTheme.TEXT : OriginTheme.MUTED, alpha));

		// grid
		g.enableScissor(px(), gridTop(), px() + pw(), py() + ph() - 8);
		for (int i = 0; i < filtered.size(); i++) {
			int[] r = cellRect(i);
			if (r[3] < gridTop() - cellH || r[1] > py() + ph()) {
				continue;
			}
			Mods.Mod mod = filtered.get(i);
			boolean hover = in(mouseX, mouseY, r[0], r[1], r[2], r[3]);
			float hv = OriginUi.anim("cell:" + mod.id(), hover, 130.0);
			boolean on = Mods.on(mod.id());

			OriginUi.panel(g, r[0], r[1] - Math.round(hv), cellW, cellH, 8,
					withAlpha(hover ? 0x28FFFFFF : 0x14FFFFFF, alpha),
					withAlpha(hover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
			int iy = r[1] - Math.round(hv);
			int iconSize = 26 + Math.round(2 * hv);
			OriginUi.icon(g, mod.id(), r[0] + (cellW - iconSize) / 2, iy + 12 - Math.round(hv), iconSize,
					withAlpha(on ? OriginTheme.TEXT : OriginTheme.MUTED, alpha * (0.85f + 0.15f * hv)));
			String name = font.width(mod.name()) > cellW - 12
					? font.plainSubstrByWidth(mod.name(), cellW - 16) + "…" : mod.name();
			g.drawString(font, name, r[0] + (cellW - font.width(name)) / 2, iy + 46, withAlpha(OriginTheme.TEXT, alpha), false);
			OriginUi.switchAt(g, mod.id(), r[0] + (cellW - 30) / 2, iy + cellH - 26, 30, on, true);
		}
		g.disableScissor();
	}

	private void renderSettings(GuiGraphics g, int mouseX, int mouseY, long now, float alpha, Mods.Mod mod) {
		if (mod == null) {
			page = null;
			return;
		}
		int x0 = px() + 18, x1 = px() + pw() - 18;
		int hy = py() + 12;

		// back chip
		boolean backHover = in(mouseX, mouseY, x0, hy, x0 + 24, hy + 20);
		OriginUi.panel(g, x0, hy, 24, 20, 8,
				withAlpha(backHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(backHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		g.drawString(font, "<", x0 + 9, hy + 6, withAlpha(OriginTheme.TEXT, alpha), false);

		OriginUi.icon(g, mod.id(), x0 + 34, hy - 2, 24, withAlpha(OriginTheme.TEXT, alpha));
		g.drawString(font, mod.name(), x0 + 64, hy + 6, withAlpha(OriginTheme.TEXT, alpha), false);

		// master enable switch, right
		OriginUi.switchAt(g, mod.id(), x1 - 34, hy + 1, 34, Mods.on(mod.id()), true);

		// rows
		int y = hy + 36;
		for (ModOption o : mod.options()) {
			renderRow(g, mod.id(), o, x0, x1, y, mouseX, mouseY, alpha);
			y += 30;
		}
		if (mod.options().isEmpty()) {
			g.drawString(font, "No additional settings — the switch is everything.",
					x0, y + 4, withAlpha(OriginTheme.MUTED, alpha), false);
		}
	}

	private void renderRow(GuiGraphics g, String modId, ModOption o, int x0, int x1, int y, int mx, int my, float alpha) {
		OriginUi.panel(g, x0, y, x1 - x0, 26, 8, withAlpha(0x10FFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
		g.drawString(font, o.label, x0 + 10, y + 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);

		switch (o.kind) {
			case TOGGLE -> OriginUi.switchAt(g, modId + ":" + o.key, x1 - 40, y + 5, 30, Mods.bool(modId, o.key), true);
			case SLIDER -> {
				double v = Mods.num(modId, o.key);
				double tt = (v - o.min) / (o.max - o.min);
				int tw = Math.min(140, (x1 - x0) / 3);
				int tx = x1 - 10 - tw;
				OriginUi.slider(g, tx, y + 11, tw, tt, modId.equals(dragMod) && o.key.equals(dragKey));
				String val = o.format.contains("%%") ? String.format(o.format, v * 100) : String.format(o.format, v);
				g.drawString(font, val, tx - font.width(val) - 10, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case COLOR -> {
				int cur = Mods.color(modId, o.key);
				int sw = 12, gap2 = 4;
				int startX = x1 - 10 - o.swatches.length * (sw + gap2) + gap2;
				for (int i = 0; i < o.swatches.length; i++) {
					int cx = startX + i * (sw + gap2);
					OriginUi.panel(g, cx, y + 7, sw, sw, 5, o.swatches[i], o.swatches[i] == cur ? 0xFFFFFFFF : 0x30000000);
				}
			}
			case KEYBIND -> {
				boolean capturing = modId.equals(capMod) && o.key.equals(capKey);
				String name = capturing ? "press a key" : keyName(Mods.keyCode(modId, o.key));
				int bw = Math.max(40, font.width(name) + 16);
				OriginUi.panel(g, x1 - 10 - bw, y + 4, bw, 18, 7,
						withAlpha(capturing ? 0x40FFFFFF : 0x1EFFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
				g.drawString(font, name, x1 - 10 - bw + 8, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case MODE -> {
				String v = Mods.mode(modId, o.key);
				int bw = Math.max(40, font.width(v) + 16);
				OriginUi.panel(g, x1 - 10 - bw, y + 4, bw, 18, 7, withAlpha(0x1EFFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
				g.drawString(font, v, x1 - 10 - bw + 8, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0 || closingAt > 0) {
			return super.mouseClicked(mx, my, button);
		}
		layout();
		int hy = py() + 10;

		if (page == null) {
			// Origin mark -> HUD editor
			int mcx = px() + pw() / 2;
			if (Math.abs(mx - mcx) < 14 && my >= hy - 2 && my < hy + 24) {
				Minecraft.getInstance().setScreen(new HudEditorScreen());
				return true;
			}
			int hbW = font.width("HUD Editor") + 16;
			int hbX = px() + pw() - 12 - 24 - 6 - hbW;
			if (in(mx, my, hbX, hy, hbX + hbW, hy + 20)) {
				Minecraft.getInstance().setScreen(new HudEditorScreen());
				return true;
			}
			int vbX = px() + pw() - 12 - 24;
			if (in(mx, my, vbX, hy, vbX + 24, hy + 20)) {
				Mods.setMetaBool("panelBacking", !Mods.metaBool("panelBacking", true));
				return true;
			}
			for (int i = 0; i < filtered.size(); i++) {
				int[] r = cellRect(i);
				if (!in(mx, my, r[0], r[1], r[2], r[3])) {
					continue;
				}
				Mods.Mod mod = filtered.get(i);
				int tx = r[0] + (cellW - 30) / 2, ty = r[1] + cellH - 26;
				if (in(mx, my, tx - 4, ty - 4, tx + 34, ty + 20)) {
					Mods.setOn(mod.id(), !Mods.on(mod.id()));
				} else {
					page = mod.id();
					pageChangedAt = System.currentTimeMillis();
				}
				return true;
			}
			return super.mouseClicked(mx, my, button);
		}

		// settings page
		Mods.Mod mod = Mods.byId(page);
		if (mod == null) {
			page = null;
			return true;
		}
		int x0 = px() + 18, x1 = px() + pw() - 18;
		if (in(mx, my, x0, hy + 2, x0 + 24, hy + 22)) { // back
			page = null;
			pageChangedAt = System.currentTimeMillis();
			return true;
		}
		if (in(mx, my, x1 - 38, hy, x1, hy + 24)) { // master switch
			Mods.setOn(mod.id(), !Mods.on(mod.id()));
			return true;
		}
		int y = hy + 38;
		for (ModOption o : mod.options()) {
			if (my >= y && my < y + 26 && clickRow(mod.id(), o, x0, x1, y, mx)) {
				return true;
			}
			y += 30;
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickRow(String modId, ModOption o, int x0, int x1, int y, double mx) {
		switch (o.kind) {
			case TOGGLE -> {
				if (mx >= x1 - 46) {
					Mods.set(modId, o.key, !Mods.bool(modId, o.key));
					return true;
				}
			}
			case SLIDER -> {
				int tw = Math.min(140, (x1 - x0) / 3);
				int tx = x1 - 10 - tw;
				if (mx >= tx - 6 && mx <= x1 - 4) {
					dragMod = modId;
					dragKey = o.key;
					dragTrackX0 = tx;
					dragTrackX1 = tx + tw;
					applySlider(o, mx);
					return true;
				}
			}
			case COLOR -> {
				int sw = 12, gap2 = 4;
				int startX = x1 - 10 - o.swatches.length * (sw + gap2) + gap2;
				for (int i = 0; i < o.swatches.length; i++) {
					int cx = startX + i * (sw + gap2);
					if (mx >= cx - 2 && mx <= cx + sw + 2) {
						Mods.set(modId, o.key, o.swatches[i]);
						return true;
					}
				}
			}
			case KEYBIND -> {
				if (mx >= x1 - 90) {
					capMod = modId;
					capKey = o.key;
					return true;
				}
			}
			case MODE -> {
				if (mx >= x1 - 90) {
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
		Mods.set(dragMod, o.key, Math.round(v / o.step) * o.step);
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
		if (page == null) {
			scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - sy * 30));
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (capMod != null) {
			Mods.set(capMod, capKey, keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode);
			capMod = null;
			capKey = null;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (page != null) {
				page = null;
				pageChangedAt = System.currentTimeMillis();
			} else {
				beginClose();
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			beginClose();
			return true;
		}
		if (page == null && keyCode == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
			search = search.substring(0, search.length() - 1);
			scrollTarget = 0;
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (capMod != null) {
			return true;
		}
		if (page == null && chr >= 32 && search.length() < 24) {
			search += chr;
			scrollTarget = 0;
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

	private static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer("originclient")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");
}
