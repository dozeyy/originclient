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

	// settings-page (per-mod) scroll + search — long pages (Coords, Particles)
	// must scroll, and each page has its own filter box.
	private String settingsSearch = "";
	private double settingsScroll = 0, settingsScrollTarget = 0;
	private int settingsMaxScroll = 0;
	private final java.util.List<SRow> srows = new java.util.ArrayList<>();

	// one laid-out settings row: the option, its absolute y (scroll-adjusted),
	// height, and whether it's a nested (indented) child row.
	private record SRow(ModOption o, int y, int h, boolean indent) {
	}

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

	private static final int BASE_CELL_W = 118;
	private int cellW = BASE_CELL_W, cellH = 104, gap = 10, cols = 4;
	private final List<Mods.Mod> filtered = new ArrayList<>();

	// the two non-gray colors in the system (muted sage / muted clay) — used
	// ONLY by the ENABLED/DISABLED button per the design: icons and everything
	// else stay white regardless of state.
	private static final int GREEN_TEXT = 0xFF7FA98F, GREEN_EDGE = 0xB32F7D53, GREEN_FILL = 0x2E2F7D53;
	private static final int RED_TEXT = 0xFFC77A73, RED_EDGE = 0xB3B23A33, RED_FILL = 0x2EB23A33;

	// search focus + the cursor-halo glow that follows the mouse
	private boolean searchFocused = false;
	private double haloX = -1, haloY = -1;

	// top-bar tab selection
	enum Tab { MODS, SETTINGS }

	private Tab tab = Tab.MODS;

	// SETTINGS tab: General / Performance sub-tabs (spec §7 — no Controls tab)
	enum SubTab { GENERAL, PERFORMANCE }

	private SubTab subTab = SubTab.GENERAL;
	private double settingsTabScroll = 0, settingsTabScrollTarget = 0;
	private int settingsTabMaxScroll = 0;

	private java.util.List<ModOption> subOpts() {
		return subTab == SubTab.GENERAL ? Mods.GENERAL_SETTINGS : Mods.PERFORMANCE_SETTINGS;
	}

	private String subId() {
		return subTab == SubTab.GENERAL ? Mods.GENERAL_ID : Mods.PERFORMANCE_ID;
	}

	private void layout() {
		// Global scaling: pick how many base-width cells fit (min 3 columns),
		// then shrink the cell to exactly fill the row — cards can never be
		// pushed out of view sideways at any window size; overflow scrolls.
		int avail = pw() - 24;
		cols = Math.max(3, (avail + gap) / (BASE_CELL_W + gap));
		cellW = Math.min(BASE_CELL_W, (avail - (cols - 1) * gap) / cols);
		filtered.clear();
		String q = search.toLowerCase();
		for (Mods.Mod m : Mods.ALL) {
			if (q.isEmpty() || m.name().toLowerCase().contains(q)) {
				filtered.add(m);
			}
		}
	}

	private int gridTop() {
		return py() + 70;
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
		settingsScroll += (settingsScrollTarget - settingsScroll) * Math.min(1.0, dt / 60.0);
		settingsTabScroll += (settingsTabScrollTarget - settingsTabScroll) * Math.min(1.0, dt / 60.0);

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

		// cursor halo across the whole menu — same lagged-follow factor as the
		// launcher site's glow (HALO_LERP_FACTOR)
		if (haloX < 0) {
			haloX = mouseX;
			haloY = mouseY;
		}
		haloX += (mouseX - haloX) * OriginTheme.HALO_LERP_FACTOR;
		haloY += (mouseY - haloY) * OriginTheme.HALO_LERP_FACTOR;
		OriginUi.glow(g, haloX, haloY, 150, 0.10f * (float) p);

		// shared color picker overlay draws last, in raw screen space
		OriginColorPicker.render(g, mouseX, mouseY);
	}

	private void renderGrid(GuiGraphics g, int mouseX, int mouseY, long now, float alpha) {
		int hy = py() + 10;

		// ORIGIN logo (top-left): tri-ring mark + wordmark
		OriginUi.mark(g, px() + 22, hy + 10, 15, alpha);
		g.drawString(font, "ORIGIN", px() + 38, hy + 5, withAlpha(OriginTheme.TEXT, alpha), false);

		// MODS / SETTINGS tabs (centered)
		renderTabs(g, mouseX, mouseY, hy, alpha);

		// right chips: panel-backing visibility + HUD Editor
		int vbX = px() + pw() - 12 - 24;
		boolean vbHover = in(mouseX, mouseY, vbX, hy, vbX + 24, hy + 20);
		boolean backing = Mods.metaBool("panelBacking", true);
		OriginUi.panel(g, vbX, hy, 24, 20, 8,
				withAlpha(vbHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(vbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		OriginUi.icon(g, "blockoverlay", vbX + 4, hy + 2, 16, withAlpha(backing ? OriginTheme.TEXT : OriginTheme.MUTED, alpha));

		int hbW = font.width("HUD Editor") + 16;
		int hbX = vbX - 6 - hbW;
		boolean hbHover = in(mouseX, mouseY, hbX, hy, hbX + hbW, hy + 20);
		OriginUi.panel(g, hbX, hy, hbW, 20, 8,
				withAlpha(hbHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(hbHover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		g.drawString(font, "HUD Editor", hbX + 8, hy + 6, withAlpha(OriginTheme.TEXT, alpha), false);

		if (tab == Tab.SETTINGS) {
			renderSettingsTab(g, mouseX, mouseY, now, alpha);
			return;
		}

		// search bar — centered, identical in clear and backed states. Focused:
		// placeholder disappears and a pulsing cursor shows it's ready.
		int sy = py() + 40;
		int sw = Math.min(300, pw() - 24);
		int sx = px() + (pw() - sw) / 2;
		OriginUi.panel(g, sx, sy, sw, 22, 8,
				withAlpha(searchFocused ? 0x80000000 : 0x66000000, alpha),
				withAlpha(searchFocused ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));
		OriginUi.icon(g, "zoom", sx + 5, sy + 3, 15, withAlpha(OriginTheme.MUTED, alpha));
		if (search.isEmpty() && !searchFocused) {
			g.drawString(font, "Search mods", sx + 24, sy + 7, withAlpha(OriginTheme.MUTED, alpha), false);
		} else {
			g.drawString(font, search, sx + 24, sy + 7, withAlpha(OriginTheme.TEXT, alpha), false);
		}
		if (searchFocused) {
			float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(now / 350.0));
			int cw = font.width(search);
			g.fill(sx + 24 + cw + 1, sy + 6, sx + 24 + cw + 2, sy + 16, withAlpha(OriginTheme.TEXT, alpha * pulse));
		}

		// grid
		g.enableScissor(px(), gridTop(), px() + pw(), py() + ph() - 8);
		for (int i = 0; i < filtered.size(); i++) {
			int[] r = cellRect(i);
			if (r[3] < gridTop() - cellH || r[1] > py() + ph()) {
				continue;
			}
			renderCard(g, filtered.get(i), r, mouseX, mouseY, alpha);
		}
		g.disableScissor();
	}

	private void renderTabs(GuiGraphics g, int mx, int my, int hy, float alpha) {
		String[] labels = {"MODS", "SETTINGS"};
		Tab[] tabs = {Tab.MODS, Tab.SETTINGS};
		int gap2 = 10, total = -gap2;
		for (String l : labels) {
			total += font.width(l) + 28 + gap2;
		}
		int tx = px() + pw() / 2 - total / 2;
		for (int i = 0; i < labels.length; i++) {
			int w = font.width(labels[i]) + 28;
			boolean active = tab == tabs[i];
			boolean hover = in(mx, my, tx, hy, tx + w, hy + 20);
			OriginUi.panel(g, tx, hy, w, 20, 8,
					withAlpha(active ? 0x2EFFFFFF : (hover ? 0x1EFFFFFF : 0x12FFFFFF), alpha),
					withAlpha(active ? 0x55FFFFFF : OriginTheme.STROKE, alpha));
			g.drawString(font, labels[i], tx + 14, hy + 6,
					withAlpha(active ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), false);
			tx += w + gap2;
		}
	}

	private void renderCard(GuiGraphics g, Mods.Mod mod, int[] r, int mx, int my, float alpha) {
		boolean hover = in(mx, my, r[0], r[1], r[2], r[3]);
		float hv = OriginUi.anim("cell:" + mod.id(), hover, 130.0);
		boolean on = Mods.on(mod.id());
		int cx = r[0], cy = r[1] - Math.round(hv);

		OriginUi.panel(g, cx, cy, cellW, cellH, 10,
				withAlpha(hover ? 0x24FFFFFF : 0x14FFFFFF, alpha),
				withAlpha(hover ? OriginTheme.STROKE_STRONG : OriginTheme.STROKE, alpha));

		// icon stays fully white in every state — only the toggle button below
		// communicates enabled/disabled
		int iconSize = 30 + Math.round(2 * hv);
		OriginUi.icon(g, mod.id(), cx + (cellW - iconSize) / 2, cy + 12 - Math.round(hv), iconSize,
				withAlpha(OriginTheme.TEXT, alpha));

		String name = font.width(mod.name()) > cellW - 12
				? font.plainSubstrByWidth(mod.name(), cellW - 16) + "…" : mod.name();
		g.drawString(font, name, cx + (cellW - font.width(name)) / 2, cy + 47, withAlpha(OriginTheme.TEXT, alpha), false);

		int bw = cellW - 24, bx = cx + 12;
		int oby = cy + 61;
		boolean oHover = in(mx, my, bx, oby, bx + bw, oby + 15);
		OriginUi.panel(g, bx, oby, bw, 15, 7,
				withAlpha(oHover ? 0x2EFFFFFF : 0x18FFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
		g.drawString(font, "OPTIONS", bx + (bw - font.width("OPTIONS")) / 2, oby + 4, withAlpha(OriginTheme.TEXT_DIM, alpha), false);

		int tby = cy + 80;
		String label = on ? "ENABLED" : "DISABLED";
		boolean tHover = in(mx, my, bx, tby, bx + bw, tby + 15);
		int fill = on ? GREEN_FILL : RED_FILL;
		if (tHover) {
			fill = (fill & 0xFFFFFF) | 0x46000000;
		}
		OriginUi.panel(g, bx, tby, bw, 15, 7, withAlpha(fill, alpha),
				withAlpha(on ? GREEN_EDGE : RED_EDGE, alpha));
		g.drawString(font, label, bx + (bw - font.width(label)) / 2, tby + 4,
				withAlpha(on ? GREEN_TEXT : RED_TEXT, alpha), false);
	}

	private void renderSettingsTab(GuiGraphics g, int mx, int my, long now, float alpha) {
		int x0 = px() + 18, x1 = px() + pw() - 18;
		int sty = py() + 40;
		String[] labels = {"GENERAL", "PERFORMANCE"};
		SubTab[] subs = {SubTab.GENERAL, SubTab.PERFORMANCE};
		int tx = x0;
		for (int i = 0; i < labels.length; i++) {
			int w = font.width(labels[i]) + 24;
			boolean active = subTab == subs[i];
			boolean hover = in(mx, my, tx, sty, tx + w, sty + 20);
			OriginUi.panel(g, tx, sty, w, 20, 8,
					withAlpha(active ? 0x2EFFFFFF : (hover ? 0x1EFFFFFF : 0x12FFFFFF), alpha),
					withAlpha(active ? 0x55FFFFFF : OriginTheme.STROKE, alpha));
			g.drawString(font, labels[i], tx + 12, sty + 6,
					withAlpha(active ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), false);
			tx += w + 8;
		}

		int top = py() + 70;
		int bottom = py() + ph() - 10;
		settingsTabMaxScroll = layoutRows(subOpts(), subId(), top, settingsTabScroll, "");
		g.enableScissor(px(), top, px() + pw(), bottom);
		drawRows(g, subId(), x0, x1, top, bottom, mx, my, alpha);
		g.disableScissor();
	}

	private boolean clickSettingsTab(double mx, double my) {
		int x0 = px() + 18, x1 = px() + pw() - 18;
		int sty = py() + 40;
		String[] labels = {"GENERAL", "PERFORMANCE"};
		SubTab[] subs = {SubTab.GENERAL, SubTab.PERFORMANCE};
		int tx = x0;
		for (int i = 0; i < labels.length; i++) {
			int w = font.width(labels[i]) + 24;
			if (in(mx, my, tx, sty, tx + w, sty + 20)) {
				subTab = subs[i];
				settingsTabScroll = settingsTabScrollTarget = 0;
				return true;
			}
			tx += w + 8;
		}
		int top = py() + 70, bottom = py() + ph() - 10;
		settingsTabMaxScroll = layoutRows(subOpts(), subId(), top, settingsTabScroll, "");
		if (my >= top && my <= bottom) {
			clickRows(subId(), x0, x1, mx, my);
		}
		return true; // settings-tab clicks never fall through
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

		OriginUi.icon(g, mod.id(), x0 + 32, hy - 3, 26, withAlpha(OriginTheme.TEXT, alpha));
		g.drawString(font, mod.name(), x0 + 64, hy + 2, withAlpha(OriginTheme.TEXT, alpha), false);
		if (!mod.description().isEmpty()) {
			g.drawString(font, mod.description(), x0 + 64, hy + 13, withAlpha(OriginTheme.MUTED, alpha), false);
		}

		// master enable switch, right
		OriginUi.switchAt(g, mod.id(), x1 - 34, hy + 1, 34, Mods.on(mod.id()), true);

		// options search box (below the title row)
		int sby = hy + 34;
		int sbw = Math.min(240, x1 - x0);
		OriginUi.panel(g, x0, sby, sbw, 20, 8, withAlpha(0x66000000, alpha), withAlpha(OriginTheme.STROKE, alpha));
		OriginUi.icon(g, "zoom", x0 + 4, sby + 2, 14, withAlpha(OriginTheme.MUTED, alpha));
		String sq = settingsSearch.isEmpty() ? "Search settings" : settingsSearch;
		g.drawString(font, sq, x0 + 22, sby + 6,
				withAlpha(settingsSearch.isEmpty() ? OriginTheme.MUTED : OriginTheme.TEXT, alpha), false);

		// scrollable content
		int top = hy + 62;
		int bottom = py() + ph() - 10;
		settingsMaxScroll = layoutRows(mod.options(), mod.id(), top, settingsScroll, settingsSearch);
		g.enableScissor(px(), top, px() + pw(), bottom);
		drawRows(g, mod.id(), x0, x1, top, bottom, mouseX, mouseY, alpha);
		g.disableScissor();

		if (mod.options().isEmpty()) {
			g.drawString(font, "No additional settings — the switch is everything.",
					x0, top + 6, withAlpha(OriginTheme.MUTED, alpha), false);
		}
	}

	// Builds the scroll-adjusted row layout shared by render + click, so hit
	// testing always matches what's drawn. Headers add a section gap; rows
	// nested under an off toggle are omitted; a search query flattens to matches.
	private int layoutRows(java.util.List<ModOption> opts, String id, int top, double scroll, String search) {
		srows.clear();
		int bottom = py() + ph() - 10;
		int y = top + 6 - (int) Math.round(scroll);
		String q = search.toLowerCase(java.util.Locale.ROOT);
		boolean searching = !q.isEmpty();
		boolean first = true;
		for (ModOption o : opts) {
			if (o.kind == ModOption.Kind.HEADER) {
				if (searching) {
					continue;
				}
				if (!first) {
					y += 10;
				}
				srows.add(new SRow(o, y, 18, false));
				y += 22;
				first = false;
				continue;
			}
			if (o.dependsOn != null && !Mods.bool(id, o.dependsOn)) {
				continue;
			}
			if (searching && !o.label.toLowerCase(java.util.Locale.ROOT).contains(q)) {
				continue;
			}
			srows.add(new SRow(o, y, 26, o.dependsOn != null));
			y += 30;
			first = false;
		}
		int content = y + (int) Math.round(scroll) - (top + 6);
		return Math.max(0, content - (bottom - top));
	}

	// draws the currently-laid-out srows (headers + control rows) for a target
	// id, within an already-active scissor region.
	private void drawRows(GuiGraphics g, String id, int x0, int x1, int top, int bottom, int mouseX, int mouseY, float alpha) {
		for (SRow r : srows) {
			if (r.y() + r.h() < top - 6 || r.y() > bottom) {
				continue;
			}
			if (r.o().kind == ModOption.Kind.HEADER) {
				g.drawString(font, r.o().label.toUpperCase(java.util.Locale.ROOT), x0 + 2, r.y() + 5,
						withAlpha(OriginTheme.MUTED, alpha), false);
				g.fill(x0 + 2, r.y() + 16, x1, r.y() + 17, withAlpha(OriginTheme.STROKE, alpha));
			} else {
				int rx0 = r.indent() ? x0 + 16 : x0;
				renderRow(g, id, r.o(), rx0, x1, r.y(), mouseX, mouseY, alpha);
			}
		}
	}

	// hit-tests the currently-laid-out srows against a click.
	private boolean clickRows(String id, int x0, int x1, double mx, double my) {
		for (SRow r : srows) {
			if (r.o().kind == ModOption.Kind.HEADER) {
				continue;
			}
			int rx0 = r.indent() ? x0 + 16 : x0;
			if (my >= r.y() && my < r.y() + r.h() && clickRow(id, r.o(), rx0, x1, r.y(), mx)) {
				return true;
			}
		}
		return false;
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
				String hex = String.format("#%06X", cur & 0xFFFFFF);
				int sw = 16, cx = x1 - 10 - sw;
				OriginUi.panel(g, cx, y + 5, sw, sw, 5, cur, 0x40FFFFFF);
				g.drawString(font, hex, cx - 8 - font.width(hex), y + 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);
			}
			case HEADER -> {
			}
			case KEYBIND -> {
				boolean capturing = modId.equals(capMod) && o.key.equals(capKey);
				String name = capturing ? "press a key" : keyName(Mods.keyCode(modId, o.key));
				int bw = Math.max(40, font.width(name) + 16);
				OriginUi.panel(g, x1 - 10 - bw, y + 4, bw, 18, 7,
						withAlpha(capturing ? 0x40FFFFFF : 0x1EFFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
				g.drawString(font, name, x1 - 10 - bw + 8, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case DROPDOWN -> {
				String v = Mods.mode(modId, o.key);
				int bw = Math.max(70, font.width(v) + 34);
				int bx = x1 - 10 - bw;
				OriginUi.panel(g, bx, y + 4, bw, 18, 7, withAlpha(0x1EFFFFFF, alpha), withAlpha(OriginTheme.STROKE, alpha));
				g.drawString(font, "<", bx + 6, y + 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);
				g.drawString(font, v, bx + (bw - font.width(v)) / 2, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
				g.drawString(font, ">", bx + bw - 6 - font.width(">"), y + 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);
			}
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (OriginColorPicker.isOpen()) {
			return OriginColorPicker.mouseClicked(mx, my, button);
		}
		if (button != 0 || closingAt > 0) {
			return super.mouseClicked(mx, my, button);
		}
		layout();
		int hy = py() + 10;

		if (page == null) {
			if (clickTabs(mx, my, hy)) {
				return true;
			}
			int vbX = px() + pw() - 12 - 24;
			if (in(mx, my, vbX, hy, vbX + 24, hy + 20)) {
				Mods.setMetaBool("panelBacking", !Mods.metaBool("panelBacking", true));
				return true;
			}
			int hbW = font.width("HUD Editor") + 16;
			int hbX = vbX - 6 - hbW;
			if (in(mx, my, hbX, hy, hbX + hbW, hy + 20)) {
				Minecraft.getInstance().setScreen(new HudEditorScreen());
				return true;
			}
			if (tab == Tab.SETTINGS) {
				searchFocused = false;
				return clickSettingsTab(mx, my);
			}
			// search bar focus (centered box below the top bar)
			int sy2 = py() + 40;
			int sw2 = Math.min(300, pw() - 24);
			int sx2 = px() + (pw() - sw2) / 2;
			searchFocused = in(mx, my, sx2, sy2, sx2 + sw2, sy2 + 22);
			if (searchFocused) {
				return true;
			}
			for (int i = 0; i < filtered.size(); i++) {
				int[] r = cellRect(i);
				if (!in(mx, my, r[0], r[1], r[2], r[3])) {
					continue;
				}
				Mods.Mod mod = filtered.get(i);
				int bx = r[0] + 12, bw = cellW - 24, tby = r[1] + 80;
				if (in(mx, my, bx, tby, bx + bw, tby + 15)) {
					Mods.setOn(mod.id(), !Mods.on(mod.id())); // ENABLED/DISABLED toggle
				} else {
					page = mod.id(); // OPTIONS button or card body opens the page
					pageChangedAt = System.currentTimeMillis();
					settingsSearch = "";
					settingsScroll = settingsScrollTarget = 0;
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
		int top = py() + 12 + 62;
		int bottom = py() + ph() - 10;
		settingsMaxScroll = layoutRows(mod.options(), mod.id(), top, settingsScroll, settingsSearch);
		if (my >= top && my <= bottom && clickRows(mod.id(), x0, x1, mx, my)) {
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickTabs(double mx, double my, int hy) {
		Tab[] tabs = {Tab.MODS, Tab.SETTINGS};
		String[] labels = {"MODS", "SETTINGS"};
		int gap2 = 10, total = -gap2;
		for (String l : labels) {
			total += font.width(l) + 28 + gap2;
		}
		int tx = px() + pw() / 2 - total / 2;
		for (int i = 0; i < labels.length; i++) {
			int w = font.width(labels[i]) + 28;
			if (in(mx, my, tx, hy, tx + w, hy + 20)) {
				tab = tabs[i];
				return true;
			}
			tx += w + gap2;
		}
		return false;
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
				if (mx >= x1 - 90) {
					OriginColorPicker.open(modId, o.key, o.label);
					return true;
				}
			}
			case HEADER -> {
			}
			case KEYBIND -> {
				if (mx >= x1 - 90) {
					capMod = modId;
					capKey = o.key;
					return true;
				}
			}
			case DROPDOWN -> {
				int bw = Math.max(70, font.width(Mods.mode(modId, o.key)) + 34);
				int bx = x1 - 10 - bw;
				if (mx >= bx && mx <= x1 - 10) {
					int dir = mx < bx + bw / 2.0 ? -1 : 1; // left half prev, right half next
					String cur = Mods.mode(modId, o.key);
					int idx = 0;
					for (int i = 0; i < o.modes.length; i++) {
						if (o.modes[i].equals(cur)) {
							idx = i;
						}
					}
					Mods.set(modId, o.key, o.modes[(idx + dir + o.modes.length) % o.modes.length]);
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
		if (OriginColorPicker.mouseDragged(mx, my, button)) {
			return true;
		}
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
		if (OriginColorPicker.mouseReleased()) {
			return true;
		}
		dragMod = null;
		dragKey = null;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (OriginColorPicker.isOpen()) {
			return true;
		}
		if (page == null) {
			if (tab == Tab.SETTINGS) {
				settingsTabScrollTarget = Math.max(0, Math.min(settingsTabMaxScroll, settingsTabScrollTarget - sy * 30));
			} else {
				scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - sy * 30));
			}
		} else {
			settingsScrollTarget = Math.max(0, Math.min(settingsMaxScroll, settingsScrollTarget - sy * 30));
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (OriginColorPicker.isOpen()) {
			return OriginColorPicker.keyPressed(keyCode);
		}
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
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (page == null && tab == Tab.MODS && !search.isEmpty()) {
				search = search.substring(0, search.length() - 1);
				scrollTarget = 0;
				return true;
			}
			if (page != null && !settingsSearch.isEmpty()) {
				settingsSearch = settingsSearch.substring(0, settingsSearch.length() - 1);
				settingsScrollTarget = 0;
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (capMod != null) {
			return true;
		}
		if (page == null && tab == Tab.MODS && chr >= 32 && search.length() < 24) {
			search += chr;
			searchFocused = true; // typing puts the box in its focused state
			scrollTarget = 0;
			return true;
		}
		if (page != null && chr >= 32 && settingsSearch.length() < 24) {
			settingsSearch += chr;
			settingsScrollTarget = 0;
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
