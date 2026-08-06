package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.hud.HudEditorScreen;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.mods.ModOption;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// The Right Shift panel — 2026-07 redesign to Will's OneConfig-style spec:
//
//   * a left SIDEBAR of sections (Mods / Settings) with Edit HUD +
//     Close pinned at the bottom, and the content to its right;
//   * COMPACT mod cards, exactly 4 per row: icon on top, a colored name bar
//     under it, a favourite star in the bottom-right corner;
//   * everything drawn with subtle, smooth, anti-aliased ROUNDED corners
//     (OriginUi.panel) — no more pixel-grid squares;
//   * every on/off control is an Apple-style iOS toggle (OriginUi.switchAt);
//   * all MENU text is Inter (OriginText) — in-world/HUD text stays vanilla;
//   * the menu background is a solid colour whose opacity the player controls
//     (Settings → Menu), from fully solid to fully clear.
//
// Word text goes through OriginText (Inter). Pure UI GLYPHS (star, arrows,
// close ×) stay on the vanilla font — the bundled Inter face doesn't carry
// those codepoints, and a missing-glyph box would look worse than a clean
// vanilla symbol next to Inter words.
public class OriginModMenuScreen extends Screen {
	private static final long SLIDE_MS = 180;
	private static final long PAGE_MS = 170;

	private final long openedAt = System.currentTimeMillis();
	private long closingAt = -1;

	// page navigation: null = the section grid/list, otherwise the open mod's id
	private String page = null;
	private long pageChangedAt = 0;

	private String search = "";
	private double scroll = 0, scrollTarget = 0;
	private long lastFrameNanos = 0;

	// settings-page (per-mod) scroll + search
	private String settingsSearch = "";
	private double settingsScroll = 0, settingsScrollTarget = 0;
	private int settingsMaxScroll = 0;
	private final java.util.List<SRow> srows = new java.util.ArrayList<>();

	private record SRow(ModOption o, int y, int h, boolean indent) {
	}

	private String dragMod = null, dragKey = null;
	private ModOption dragOpt = null;
	private int dragTrackX0, dragTrackX1;
	private String capMod = null, capKey = null;

	public OriginModMenuScreen() {
		super(Component.literal("Origin Mods"));
	}

	@Override
	protected void init() {
		super.init();
		HudElements.editorPreview = true;
	}

	@Override
	public void removed() {
		HudElements.editorPreview = false;
		super.removed();
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
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int pw() {
		return (int) (width * 0.78);
	}

	private int ph() {
		return (int) (height * 0.76);
	}

	/** Sidebar width — HALVED from the old 104–150px rail (Will: "make the left
	 *  part 50% smaller"). Still clamped so it never eats content on small windows;
	 *  the 3 nav labels stay centered in the narrower rail (drawNavItem). */
	private int sbW() {
		return Math.max(52, Math.min(76, pw() * 15 / 100));
	}

	/** Content-region left edge (the divider sits here). */
	private int contentX() {
		return px() + sbW();
	}

	private int cx0() {
		return contentX() + 14;
	}

	private int cx1() {
		return px() + pw() - 14;
	}

	private static final int COLS = 4, GAP = 8, CELL_H = 74, BAR_H = 17;
	private int cellW = 100;
	private final List<Mods.Mod> filtered = new ArrayList<>();

	// section state
	enum Nav {MODS, SETTINGS}

	private Nav nav = Nav.MODS;

	enum SubTab {GENERAL, PERFORMANCE, MENU}

	private SubTab subTab = SubTab.GENERAL;
	private double settingsTabScroll = 0, settingsTabScrollTarget = 0;
	private int settingsTabMaxScroll = 0;

	// transparent-menu mode (opacity ~0): content switches to dark translucent
	// fills so it stays legible with no panel behind it.
	private boolean clear = false;

	private int chipFill(boolean hover) {
		return clear ? (hover ? 0xE0181818 : 0xC8101010) : (hover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL);
	}

	// Card name-bar tones: sage when the mod is ON, neutral gray when OFF (the
	// theme's sanctioned enabled/disabled colour language, kept subtle).
	private static final int BAR_ON = 0xFF2F7D53, BAR_ON_HOVER = 0xFF3A9466;
	private static final int BAR_OFF = 0xFF2A2A2A, BAR_OFF_HOVER = 0xFF3A3A3A;

	private java.util.List<ModOption> subOpts() {
		return subTab == SubTab.GENERAL ? Mods.GENERAL_SETTINGS : Mods.PERFORMANCE_SETTINGS;
	}

	private String subId() {
		return subTab == SubTab.GENERAL ? Mods.GENERAL_ID : Mods.PERFORMANCE_ID;
	}

	// ---- value backing dispatch (unchanged) ----
	private static boolean isJei(String id) {
		return "jei".equals(id);
	}

	private java.util.List<ModOption> optionsFor(Mods.Mod mod) {
		return isJei(mod.id()) ? JeiSettings.options() : mod.options();
	}

	private boolean vBool(String id, String key) {
		return isJei(id) ? JeiSettings.getBool(key) : Mods.bool(id, key);
	}

	private void vSetBool(String id, String key, boolean v) {
		if (isJei(id)) {
			JeiSettings.setBool(key, v);
		} else {
			Mods.set(id, key, v);
		}
	}

	private double vNum(String id, String key) {
		return isJei(id) ? JeiSettings.getNum(key) : Mods.num(id, key);
	}

	private void vSetNum(String id, String key, double v) {
		if (isJei(id)) {
			JeiSettings.setNum(key, v);
		} else {
			Mods.set(id, key, v);
		}
	}

	private String vMode(String id, String key) {
		return isJei(id) ? JeiSettings.getMode(key) : Mods.mode(id, key);
	}

	private void vSetMode(String id, String key, String v) {
		if (isJei(id)) {
			JeiSettings.setMode(key, v);
		} else {
			Mods.set(id, key, v);
		}
	}

	private String vMulti(String id, String key) {
		return isJei(id) ? JeiSettings.getMulti(key) : Mods.mode(id, key);
	}

	private void vSetMulti(String id, String key, String csv) {
		if (isJei(id)) {
			JeiSettings.setMulti(key, csv);
		} else {
			Mods.set(id, key, csv);
		}
	}

	private int multiBtnW(int x0, int x1) {
		return Math.min(180, Math.max(80, (x1 - x0) / 2));
	}

	private static java.util.List<String> splitCsv(String csv) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (csv != null) {
			for (String p : csv.split(",")) {
				String t = p.trim();
				if (!t.isEmpty()) {
					out.add(t);
				}
			}
		}
		return out;
	}

	private static String joinCsv(java.util.List<String> tokens) {
		return String.join(", ", tokens);
	}

	// ---- mods grid layout ----

	private void layout() {
		int gridW = cx1() - cx0();
		cellW = Math.max(48, (gridW - (COLS - 1) * GAP) / COLS);
		filtered.clear();
		String q = search.toLowerCase();
		for (Mods.Mod m : Mods.ALL) {
			if (q.isEmpty() || m.name().toLowerCase().contains(q)) {
				filtered.add(m);
			}
		}
		filtered.sort((a, b) -> Boolean.compare(
				Mods.metaBool("fav:" + b.id(), false), Mods.metaBool("fav:" + a.id(), false)));
	}

	private int gridTop() {
		return py() + 52;
	}

	private int gridLeft() {
		return cx0();
	}

	private int[] cellRect(int i) {
		int col = i % COLS, row = i / COLS;
		int x = gridLeft() + col * (cellW + GAP);
		int y = gridTop() + row * (CELL_H + GAP) - (int) scroll;
		return new int[]{x, y, x + cellW, y + CELL_H};
	}

	private double maxScroll() {
		int rows = (filtered.size() + COLS - 1) / COLS;
		return Math.max(0, rows * (CELL_H + GAP) - GAP - (py() + ph() - 14 - gridTop()));
	}

	// ---- render ----

	@Override
	public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		Gfx g = new Gfx(poseStack);
		HudElements.renderAll(g);
		layout();
		hoverTip = null;
		long now = System.currentTimeMillis();

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

		// Solid menu background, on/off (Settings → Menu): 1 = solid, 0 = clear.
		double op = Mods.metaNum("menuBgOpacity", 1.0);
		clear = op <= 0.02;
		if (!clear) {
			int a = (int) Math.round(op * 255);
			OriginUi.panel(g, px(), py(), pw(), ph(), 12, (a << 24) | 0x0E0E0E, OriginTheme.STROKE);
		}

		float t = (float) OriginTheme.easeOut(Math.min(1.0, (now - pageChangedAt) / (double) PAGE_MS));
		if (pageChangedAt == 0) {
			t = 1f;
		}

		// Sidebar is always present; its own subtle scale-in rides the same page t.
		renderSidebar(g, mouseX, mouseY, (float) p);

		pose.pushPose();
		float s = 0.985f + 0.015f * t;
		pose.translate(contentX() + (pw() - sbW()) / 2.0, py() + ph() / 2.0, 0);
		pose.scale(s, s, 1f);
		pose.translate(-(contentX() + (pw() - sbW()) / 2.0), -(py() + ph() / 2.0), 0);

		if (page != null) {
			renderSettings(g, mouseX, mouseY, now, t, Mods.byId(page));
		} else {
			switch (nav) {
				case MODS -> renderMods(g, mouseX, mouseY, now, t);
				case SETTINGS -> renderSettingsPage(g, mouseX, mouseY, now, t);
			}
		}
		pose.popPose();

		// version stamp
		String ver = "Origin Client " + VERSION;
		OriginText.draw(g, font, ver, cx1() - OriginText.width(font, ver), py() + ph() - 12,
				withAlpha(OriginTheme.MUTED, (float) p * 0.8f), false);

		pose.popPose();

		if (hoverTip != null && !OriginColorPicker.isOpen() && !OriginMultiSelect.isOpen() && closingAt < 0) {
			drawTooltip(g, hoverTipX, hoverTipY, hoverTip);
		}

		OriginMultiSelect.render(g, mouseX, mouseY);
		OriginColorPicker.render(g, mouseX, mouseY);
	}

	// ---- sidebar ----

	private void renderSidebar(Gfx g, int mx, int my, float alpha) {
		int x = px() + 14;
		int w = sbW() - 28;

		// brand: Origin mark + wordmark, CENTERED at the top of the narrow rail
		// (the old left-anchored logo + "ORIGIN" no longer fits the halved width).
		int railCx = x + w / 2;
		OriginUi.logo(g, railCx, py() + 24, 20, alpha);
		int brandW = OriginText.widthBold(font, "ORIGIN");
		OriginText.drawBold(g, font, "ORIGIN", x + (w - brandW) / 2, py() + 38, withAlpha(OriginTheme.TEXT, alpha), clear);

		// divider between sidebar and content
		g.fill(contentX(), py() + 10, contentX() + 1, py() + ph() - 10, withAlpha(OriginTheme.STROKE, alpha));

		// nav items
		String[] labels = {"Mods", "Settings"};
		Nav[] navs = {Nav.MODS, Nav.SETTINGS};
		int y = py() + 64;
		for (int i = 0; i < labels.length; i++) {
			boolean active = nav == navs[i] && page == null || (navs[i] == Nav.MODS && page != null);
			boolean hover = in(mx, my, x, y, x + w, y + NAV_H);
			drawNavItem(g, x, y, w, labels[i], active, hover, alpha);
			y += NAV_STEP;
		}

		// bottom actions: Edit + Close, pinned bottom with a TIGHT gap (Will). "Edit"
		// (not "Edit HUD") so the label fits the halved rail without clipping.
		int by2 = py() + ph() - 26;
		int by1 = by2 - 20;
		drawSidebarButton(g, x, by1, w, "Edit", in(mx, my, x, by1, x + w, by1 + 18), alpha, false);
		drawSidebarButton(g, x, by2, w, "Close", in(mx, my, x, by2, x + w, by2 + 18), alpha, true);
	}

	// Sidebar nav rows: a short box that HUGS the label (text ~8px + small padding)
	// rather than a tall block (Will). NAV_STEP leaves a slim gap between rows.
	private static final int NAV_H = 20, NAV_STEP = 24;

	private void drawNavItem(Gfx g, int x, int y, int w, String label, boolean active, boolean hover, float alpha) {
		// Faint hover feedback only; the active state now reads through the UNDERLINE
		// (Will), not a filled selection panel.
		if (hover && !active) {
			OriginUi.panel(g, x, y, w, NAV_H, 7, withAlpha(clear ? 0xB0181818 : OriginTheme.BOX_FILL, alpha), 0);
		}
		// Text is always WHITE (matching the Origin mark) — selection is shown by the
		// underline, never by colour. Label centered both ways in the short button.
		int tw = OriginText.widthBold(font, label);
		int lx = x + (w - tw) / 2;
		OriginText.drawBold(g, font, label, lx, y + NAV_H / 2 - 4, withAlpha(0xFFFFFFFF, alpha), clear);
		// Underline placeholder: ALWAYS present under the label — bright white when
		// this is the active section, dimmed grey otherwise.
		int uy = y + NAV_H - 2;
		int uCol = active ? 0xFFFFFFFF : 0x40FFFFFF;
		g.fill(lx, uy, lx + tw, uy + 1, withAlpha(uCol, alpha));
	}

	private void drawSidebarButton(Gfx g, int x, int y, int w, String label, boolean hover, float alpha, boolean danger) {
		// No box (Will): a small vector icon + label. The icon is LEFT-anchored at a
		// fixed x so Edit's pencil and Close's × stack in a clean vertical line (labels
		// may differ in length); each icon is vertically CENTERED on its own label's
		// mid-line. WHITE like the rest of the menu (Will), brightening on hover.
		int col = hover ? 0xFFFFFFFF : 0xCCFFFFFF;
		int icon = 11, gap = 6;
		int iconX = x + 4;                               // same x for both buttons
		int textX = iconX + icon + gap;
		// label optical centre sits ~4px below its draw-top (after the MATCH_DY
		// re-centre); centre the icon box on that so the × no longer rides high.
		int iconY = y + 4 - icon / 2;
		if (danger) {
			OriginUi.iconClose(g, iconX, iconY, icon, withAlpha(col, alpha));
		} else {
			OriginUi.iconEdit(g, iconX, iconY, icon, withAlpha(col, alpha));
		}
		OriginText.draw(g, font, label, textX, y, withAlpha(col, alpha), clear);
	}

	private boolean clickSidebar(double mx, double my) {
		int x = px() + 14;
		int w = sbW() - 28;
		String[] labels = {"Mods", "Settings"};
		Nav[] navs = {Nav.MODS, Nav.SETTINGS};
		int y = py() + 64;
		for (int i = 0; i < labels.length; i++) {
			if (in(mx, my, x, y, x + w, y + NAV_H)) {
				nav = navs[i];
				page = null;
				pageChangedAt = System.currentTimeMillis();
				searchFocused = false;
				scrollTarget = scroll = 0;
				return true;
			}
			y += NAV_STEP;
		}
		int by2 = py() + ph() - 26;
		int by1 = by2 - 20;
		if (in(mx, my, x, by1, x + w, by1 + 18)) {
			Minecraft.getInstance().setScreen(new HudEditorScreen());
			return true;
		}
		if (in(mx, my, x, by2, x + w, by2 + 18)) {
			beginClose();
			return true;
		}
		return false;
	}

	// ---- MODS page ----

	private boolean searchFocused = false;

	private void renderMods(Gfx g, int mouseX, int mouseY, long now, float alpha) {
		// search bar (content top)
		int sy = py() + 18;
		int sx = cx0();
		int sw = cx1() - cx0();
		OriginUi.panel(g, sx, sy, sw, 22, 8,
				withAlpha(clear ? 0xC8101010 : (searchFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL), alpha),
				withAlpha(searchFocused ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER, alpha));
		OriginUi.icon(g, "@search", sx + 5, sy + 3, 15, withAlpha(clear ? OriginTheme.TEXT_DIM : OriginTheme.MUTED, alpha));
		if (search.isEmpty() && !searchFocused) {
			OriginText.draw(g, font, "Search mods", sx + 24, sy + 7,
					withAlpha(clear ? OriginTheme.TEXT_DIM : OriginTheme.MUTED, alpha), clear);
		} else {
			OriginText.draw(g, font, search, sx + 24, sy + 7, withAlpha(OriginTheme.TEXT, alpha), clear);
		}
		if (searchFocused) {
			float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(now / 350.0));
			int cw = OriginText.width(font, search);
			g.fill(sx + 24 + cw + 1, sy + 6, sx + 24 + cw + 2, sy + 16, withAlpha(OriginTheme.TEXT, alpha * pulse));
		}

		g.enableScissor(contentX(), gridTop(), px() + pw(), py() + ph() - 12);
		for (int i = 0; i < filtered.size(); i++) {
			int[] r = cellRect(i);
			if (r[3] < gridTop() - CELL_H || r[1] > py() + ph()) {
				continue;
			}
			renderCard(g, filtered.get(i), r, mouseX, mouseY, alpha);
		}
		g.disableScissor();
	}

	// Compact 4-per-row card: icon on top, colored name bar below, favourite
	// star in the bottom-right corner of the bar.
	private void renderCard(Gfx g, Mods.Mod mod, int[] r, int mx, int my, float alpha) {
		boolean inBand = my >= gridTop() && my < py() + ph() - 12;
		int cx = r[0], cy = r[1], x2 = r[2], y2 = r[3];
		boolean cardHover = inBand && in(mx, my, cx, cy, x2, y2);
		int barY = y2 - BAR_H;
		boolean iconHover = cardHover && my < barY;
		boolean on = Mods.on(mod.id());

		// card body
		OriginUi.panel(g, cx, cy, cellW, CELL_H, 7,
				withAlpha(clear ? (iconHover ? 0xD8141414 : 0xC8101010) : (iconHover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL), alpha),
				withAlpha(cardHover ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER, alpha));

		// icon centered in the upper area
		int iconSize = 30;
		int iconAreaH = CELL_H - BAR_H;
		OriginUi.icon(g, mod.id(), cx + (cellW - iconSize) / 2, cy + (iconAreaH - iconSize) / 2 - 1, iconSize,
				withAlpha(OriginTheme.TEXT, alpha));

		// name bar (bottom) — sage when enabled, gray when disabled; click toggles.
		// INSET 1px inside the card so its rounded bottom corners nest INSIDE the
		// card's radius-7 corners (concentric, radius 6) instead of poking past the
		// card outline — that overhang was the "bulging" corners. The card's 1px
		// border then frames the bar cleanly on every side.
		boolean barHover = inBand && in(mx, my, cx, barY, x2, y2);
		int barFill = on ? (barHover ? BAR_ON_HOVER : BAR_ON) : (barHover ? BAR_OFF_HOVER : BAR_OFF);
		int inX = cx + 1, inW = cellW - 2;
		OriginUi.panel(g, inX, barY, inW, BAR_H - 1, 6, withAlpha(barFill, alpha), 0);
		// square off the bar's TOP corners so it reads as a bar seated in the card,
		// not a floating pill — redraw the top strip flat over the rounded fill.
		g.fill(inX, barY, inX + inW, barY + 6, withAlpha(barFill, alpha));

		String name = OriginText.ellipsize(font, mod.name(), cellW - 20);
		OriginText.draw(g, font, name, cx + 6, barY + (BAR_H - 8) / 2,
				withAlpha(on ? 0xFFFFFFFF : OriginTheme.TEXT_DIM, alpha), false);

		// favourite star, bottom-right corner of the bar — a baked HQ texture, not
		// the pixelated font glyph. Hit box (star click) matches sx/sy below.
		boolean fav = Mods.metaBool("fav:" + mod.id(), false);
		int starSize = 10;
		int sx = x2 - starSize - 4, sy = y2 - starSize - 3;
		boolean sHover = cardHover && in(mx, my, sx - 2, sy - 2, sx + starSize + 2, sy + starSize + 2);
		if (fav || cardHover) {
			int starCol = fav ? 0xFFFFD700 : (sHover ? 0xFFFFFFFF : 0xAAFFFFFF);
			OriginUi.star(g, sx, sy, starSize, withAlpha(starCol, alpha));
		}
	}

	// ---- SETTINGS page ----

	private void renderSettingsPage(Gfx g, int mx, int my, long now, float alpha) {
		int x0 = cx0(), x1 = cx1();
		int sty = py() + 18;
		String[] labels = {"GENERAL", "PERFORMANCE", "MENU"};
		SubTab[] subs = {SubTab.GENERAL, SubTab.PERFORMANCE, SubTab.MENU};
		int tx = x0;
		for (int i = 0; i < labels.length; i++) {
			int w = OriginText.widthBold(font, labels[i]) + 24;
			boolean active = subTab == subs[i];
			boolean hover = in(mx, my, tx, sty, tx + w, sty + 20);
			drawTab(g, tx, sty, w, 20, labels[i], active, hover, alpha);
			tx += w + 8;
		}

		int top = py() + 46;
		int bottom = py() + ph() - 12;
		if (subTab == SubTab.MENU) {
			renderMenuSettings(g, x0, x1, top, mx, my, now, alpha);
			return;
		}
		settingsTabMaxScroll = layoutRows(subOpts(), subId(), top, settingsTabScroll, "");
		g.enableScissor(contentX(), top, px() + pw(), bottom);
		drawRows(g, subId(), x0, x1, top, bottom, mx, my, alpha);
		g.disableScissor();
	}

	// The MENU appearance sub-tab: a single on/off toggle for the solid background.
	// (The old opacity slider and the "Smooth Text & Curves" toggle are gone — the
	// background is simply solid-or-clear, and vector text/curves are always on.)
	private void renderMenuSettings(Gfx g, int x0, int x1, int top, int mx, int my, long now, float alpha) {
		int y = top + 4;
		OriginUi.panel(g, x0, y, x1 - x0, 26, 8, withAlpha(clear ? 0xC0101010 : OriginTheme.BOX_FILL, alpha),
				withAlpha(OriginTheme.BOX_BORDER, alpha));
		OriginText.draw(g, font, "Solid Menu Background", x0 + 10, y + 9,
				withAlpha(clear ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), clear);
		boolean solid = Mods.metaNum("menuBgOpacity", 1.0) > 0.5;
		OriginUi.switchAt(g, "@bgop", x1 - 40, y + 5, 30, solid, true);
		OriginText.draw(g, font, solid ? "On — a solid backdrop behind the menu." : "Off — a fully see-through menu.",
				x0 + 2, y + 30, withAlpha(OriginTheme.MUTED, alpha), clear);
	}

	private void drawTab(Gfx g, int tx, int ty, int w, int h, String label, boolean active, boolean hover, float alpha) {
		// White text always; the underline is the sole active indicator — white when
		// active, a dimmed grey placeholder otherwise (always present).
		OriginText.drawBold(g, font, label, tx + (w - OriginText.widthBold(font, label)) / 2, ty + (h - 8) / 2,
				withAlpha(0xFFFFFFFF, alpha), clear);
		int underY = ty + h - 2;
		int under = active ? 0xFFFFFFFF : (hover ? 0x80FFFFFF : 0x40FFFFFF);
		g.fill(tx + 4, underY, tx + w - 4, underY + 2, withAlpha(under, alpha));
	}

	private boolean clickSettingsPage(double mx, double my) {
		int x0 = cx0(), x1 = cx1();
		int sty = py() + 18;
		String[] labels = {"GENERAL", "PERFORMANCE", "MENU"};
		SubTab[] subs = {SubTab.GENERAL, SubTab.PERFORMANCE, SubTab.MENU};
		int tx = x0;
		for (int i = 0; i < labels.length; i++) {
			int w = OriginText.widthBold(font, labels[i]) + 24;
			if (in(mx, my, tx, sty, tx + w, sty + 20)) {
				subTab = subs[i];
				settingsTabScroll = settingsTabScrollTarget = 0;
				return true;
			}
			tx += w + 8;
		}
		int top = py() + 46, bottom = py() + ph() - 12;
		if (subTab == SubTab.MENU) {
			int y = top + 4;
			// Solid-background on/off toggle (drawn at x1-40, y+5, 30 wide, 16 tall):
			// on = fully solid, off = fully clear.
			if (in(mx, my, x1 - 40, y + 5, x1 - 10, y + 21)) {
				boolean solid = Mods.metaNum("menuBgOpacity", 1.0) > 0.5;
				Mods.setMetaNum("menuBgOpacity", solid ? 0.0 : 1.0);
			}
			return true;
		}
		settingsTabMaxScroll = layoutRows(subOpts(), subId(), top, settingsTabScroll, "");
		if (my >= top && my <= bottom) {
			clickRows(subId(), x0, x1, mx, my);
		}
		return true;
	}

	// ---- per-mod settings page ----

	private void renderSettings(Gfx g, int mouseX, int mouseY, long now, float alpha, Mods.Mod mod) {
		if (mod == null) {
			page = null;
			return;
		}
		int x0 = cx0(), x1 = cx1();
		int hy = py() + 16;

		boolean backHover = in(mouseX, mouseY, x0, hy, x0 + 24, hy + 20);
		OriginUi.panel(g, x0, hy, 24, 20, 6,
				withAlpha(backHover ? 0x2EFFFFFF : 0x16FFFFFF, alpha),
				withAlpha(backHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE, alpha));
		OriginUi.iconChevron(g, x0 + 7, hy + 5, 10, withAlpha(OriginTheme.TEXT, alpha), true);

		OriginUi.icon(g, mod.id(), x0 + 32, hy - 3, 26, withAlpha(OriginTheme.TEXT, alpha));
		OriginText.drawBold(g, font, mod.name(), x0 + 64, hy + 2, withAlpha(OriginTheme.TEXT, alpha), false);
		if (!mod.description().isEmpty()) {
			OriginText.draw(g, font, mod.description(), x0 + 64, hy + 13, withAlpha(OriginTheme.MUTED, alpha), false);
		}

		OriginUi.switchAt(g, mod.id(), x1 - 34, hy + 1, 34, Mods.on(mod.id()), true);

		int sby = hy + 34;
		int sbw = Math.min(240, x1 - x0);
		OriginUi.panel(g, x0, sby, sbw, 20, 8, withAlpha(0x66000000, alpha), withAlpha(OriginTheme.STROKE, alpha));
		OriginUi.icon(g, "@search", x0 + 4, sby + 2, 14, withAlpha(OriginTheme.MUTED, alpha));
		float sPulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(now / 350.0));
		int caretX = x0 + 22 + OriginText.width(font, settingsSearch);
		if (!settingsSearch.isEmpty()) {
			OriginText.draw(g, font, settingsSearch, x0 + 22, sby + 6, withAlpha(OriginTheme.TEXT, alpha), false);
		}
		g.fill(caretX + 1, sby + 5, caretX + 2, sby + 15, withAlpha(OriginTheme.TEXT, alpha * sPulse));

		java.util.List<ModOption> opts = optionsFor(mod);
		int top = hy + 62;
		int bottom = py() + ph() - 10;
		settingsMaxScroll = layoutRows(opts, mod.id(), top, settingsScroll, settingsSearch);
		g.enableScissor(contentX(), top, px() + pw(), bottom);
		drawRows(g, mod.id(), x0, x1, top, bottom, mouseX, mouseY, alpha);
		g.disableScissor();

		if (opts.isEmpty()) {
			String empty = isJei(mod.id())
					? "JEI settings load once you're in a world."
					: "No additional settings — the switch is everything.";
			OriginText.draw(g, font, empty, x0, top + 6, withAlpha(OriginTheme.MUTED, alpha), false);
		}
	}

	// ---- shared row machinery (layout / draw / click) ----

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
			if (o.dependsOn != null && !vBool(id, o.dependsOn)) {
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

	private void drawRows(Gfx g, String id, int x0, int x1, int top, int bottom, int mouseX, int mouseY, float alpha) {
		for (SRow r : srows) {
			if (r.y() + r.h() < top - 6 || r.y() > bottom) {
				continue;
			}
			if (r.o().kind == ModOption.Kind.HEADER) {
				OriginText.drawBold(g, font, r.o().label.toUpperCase(java.util.Locale.ROOT), x0 + 2, r.y() + 5,
						withAlpha(OriginTheme.MUTED, alpha), false);
				g.fill(x0 + 2, r.y() + 16, x1, r.y() + 17, withAlpha(OriginTheme.STROKE, alpha));
			} else {
				int rx0 = r.indent() ? x0 + 16 : x0;
				renderRow(g, id, r.o(), rx0, x1, r.y(), mouseX, mouseY, alpha);
			}
		}
	}

	private boolean clickRows(String id, int x0, int x1, double mx, double my) {
		for (SRow r : srows) {
			if (r.o().kind == ModOption.Kind.HEADER) {
				continue;
			}
			int rx0 = r.indent() ? x0 + 16 : x0;
			if (my >= r.y() && my < r.y() + r.h() && clickRow(id, r.o(), rx0, x1, r.y(), mx, my)) {
				return true;
			}
		}
		return false;
	}

	private void renderRow(Gfx g, String modId, ModOption o, int x0, int x1, int y, int mx, int my, float alpha) {
		OriginUi.panel(g, x0, y, x1 - x0, 26, 8,
				withAlpha(clear ? 0xC0101010 : OriginTheme.BOX_FILL, alpha), withAlpha(OriginTheme.BOX_BORDER, alpha));
		OriginText.draw(g, font, o.label, x0 + 10, y + 9,
				withAlpha(clear ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), clear);

		if (o.tooltip != null && in(mx, my, x0, y, x1, y + 26)) {
			hoverTip = o.tooltip;
			hoverTipX = mx;
			hoverTipY = my;
		}

		switch (o.kind) {
			case TOGGLE -> OriginUi.switchAt(g, modId + ":" + o.key, x1 - 40, y + 5, 30, vBool(modId, o.key), true);
			case SLIDER -> {
				double v = vNum(modId, o.key);
				double tt = (v - o.min) / (o.max - o.min);
				int tw = Math.min(140, (x1 - x0) / 3);
				int tx = x1 - 10 - tw;
				// pill top y+10 → knob centers on the row mid-line (y+13), matching
				// the toggle / swatch / dropdown controls for one consistent baseline.
				OriginUi.slider(g, tx, y + 10, tw, tt, modId.equals(dragMod) && o.key.equals(dragKey));
				boolean pctOfFraction = o.format.contains("%%") && o.max <= 1.0;
				String val = pctOfFraction ? String.format(o.format, v * 100) : String.format(o.format, v);
				OriginText.draw(g, font, val, tx - OriginText.width(font, val) - 10, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case COLOR -> {
				int cur = Mods.color(modId, o.key);
				String hex = String.format("#%06X", cur & 0xFFFFFF);
				int sw = 16, cx = x1 - 10 - sw;
				OriginUi.panel(g, cx, y + 5, sw, sw, 5, cur, 0x40FFFFFF);
				OriginText.draw(g, font, hex, cx - 8 - OriginText.width(font, hex), y + 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);
			}
			case HEADER -> {
			}
			case KEYBIND -> {
				boolean capturing = modId.equals(capMod) && o.key.equals(capKey);
				String name = capturing ? "press a key" : keyName(Mods.keyCode(modId, o.key));
				int bw = Math.max(40, OriginText.width(font, name) + 16);
				boolean kHover = in(mx, my, x1 - 10 - bw, y + 4, x1 - 10, y + 22);
				OriginUi.panel(g, x1 - 10 - bw, y + 4, bw, 18, 6,
						withAlpha(capturing ? 0x40FFFFFF : 0x1EFFFFFF, alpha),
						withAlpha(kHover || capturing ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE, alpha));
				OriginText.draw(g, font, name, x1 - 10 - bw + 8, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
			case DROPDOWN -> {
				String v = vMode(modId, o.key);
				int bw = Math.max(70, OriginText.width(font, v) + 34);
				int bx = x1 - 10 - bw;
				boolean dHover = in(mx, my, bx, y + 4, bx + bw, y + 22);
				OriginUi.panel(g, bx, y + 4, bw, 18, 6, withAlpha(0x1EFFFFFF, alpha),
						withAlpha(dHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE, alpha));
				OriginUi.iconChevron(g, bx + 5, y + 8, 9, withAlpha(OriginTheme.TEXT_DIM, alpha), true);
				OriginText.draw(g, font, v, bx + (bw - OriginText.width(font, v)) / 2, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
				OriginUi.iconChevron(g, bx + bw - 14, y + 8, 9, withAlpha(OriginTheme.TEXT_DIM, alpha), false);
			}
			case MULTISELECT -> {
				java.util.List<String> sel = splitCsv(vMulti(modId, o.key));
				String summary = sel.isEmpty() ? "None"
						: String.join(", ", sel.stream().map(JeiSettings::prettify).toList());
				int bw = multiBtnW(x0, x1);
				int bx = x1 - 10 - bw;
				boolean bHover = in(mx, my, bx, y + 4, bx + bw, y + 22);
				OriginUi.panel(g, bx, y + 4, bw, 18, 6,
						withAlpha(bHover ? 0x2EFFFFFF : 0x1EFFFFFF, alpha),
						withAlpha(bHover ? OriginTheme.STROKE_HOVER : OriginTheme.STROKE, alpha));
				String shown = OriginText.ellipsize(font, summary, bw - 16);
				OriginText.draw(g, font, shown, bx + 8, y + 9, withAlpha(OriginTheme.TEXT, alpha), false);
			}
		}
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (OriginMultiSelect.isOpen()) {
			return OriginMultiSelect.mouseClicked(mx, my, button);
		}
		if (OriginColorPicker.isOpen()) {
			return OriginColorPicker.mouseClicked(mx, my, button);
		}
		if (button != 0 || closingAt > 0) {
			return super.mouseClicked(mx, my, button);
		}
		layout();

		// sidebar clicks first (always present)
		if (clickSidebar(mx, my)) {
			return true;
		}

		if (page != null) {
			return clickModPage(mx, my);
		}

		switch (nav) {
			case MODS -> {
				return clickMods(mx, my);
			}
			case SETTINGS -> {
				searchFocused = false;
				return clickSettingsPage(mx, my);
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickMods(double mx, double my) {
		int sy = py() + 18;
		int sx = cx0();
		int sw = cx1() - cx0();
		searchFocused = in(mx, my, sx, sy, sx + sw, sy + 22);
		if (searchFocused) {
			return true;
		}
		int gTop = gridTop(), gBot = py() + ph() - 12;
		if (my >= gTop && my < gBot) {
			for (int i = 0; i < filtered.size(); i++) {
				int[] r = cellRect(i);
				if (!in(mx, my, r[0], r[1], r[2], r[3])) {
					continue;
				}
				Mods.Mod mod = filtered.get(i);
				int x2 = r[2], y2 = r[3];
				int barY = y2 - BAR_H;
				// star (bottom-right)
				if (in(mx, my, x2 - 14, y2 - 14, x2 - 1, y2 - 1)) {
					Mods.setMetaBool("fav:" + mod.id(), !Mods.metaBool("fav:" + mod.id(), false));
					return true;
				}
				// name bar → toggle enable
				if (my >= barY) {
					Mods.setOn(mod.id(), !Mods.on(mod.id()));
					return true;
				}
				// icon area → open the mod's settings page
				page = mod.id();
				pageChangedAt = System.currentTimeMillis();
				settingsSearch = "";
				settingsScroll = settingsScrollTarget = 0;
				return true;
			}
		}
		return true;
	}

	private boolean clickModPage(double mx, double my) {
		Mods.Mod mod = Mods.byId(page);
		if (mod == null) {
			page = null;
			return true;
		}
		int x0 = cx0(), x1 = cx1();
		int hy = py() + 16;
		if (in(mx, my, x0, hy, x0 + 24, hy + 20)) { // back
			page = null;
			pageChangedAt = System.currentTimeMillis();
			return true;
		}
		if (in(mx, my, x1 - 34, hy + 1, x1, hy + 19)) { // master switch
			Mods.setOn(mod.id(), !Mods.on(mod.id()));
			return true;
		}
		int top = hy + 62;
		int bottom = py() + ph() - 10;
		settingsMaxScroll = layoutRows(optionsFor(mod), mod.id(), top, settingsScroll, settingsSearch);
		if (my >= top && my <= bottom && clickRows(mod.id(), x0, x1, mx, my)) {
			return true;
		}
		return true;
	}

	private boolean clickRow(String modId, ModOption o, int x0, int x1, int y, double mx, double my) {
		switch (o.kind) {
			case TOGGLE -> {
				if (in(mx, my, x1 - 40, y + 5, x1 - 10, y + 21)) {
					vSetBool(modId, o.key, !vBool(modId, o.key));
					if (Mods.PERFORMANCE_ID.equals(modId) && "shaderPerformanceMode".equals(o.key)) {
						com.origin.client.client.shaders.IrisBridge.reloadIfPackActive();
					}
					if ("fullbright".equals(modId) && "fullBright".equals(o.key)
							&& Mods.bool(modId, o.key)
							&& com.origin.client.client.shaders.IrisBridge.currentPack() != null) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.player != null) {
							mc.player.displayClientMessage(Component.literal(
									"Full Bright needs shaders OFF — your shaderpack does its own lighting."), false);
						}
					}
					return true;
				}
			}
			case SLIDER -> {
				int tw = Math.min(140, (x1 - x0) / 3);
				int tx = x1 - 10 - tw;
				if (mx >= tx && mx <= tx + tw) {
					dragMod = modId;
					dragKey = o.key;
					dragOpt = o;
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
				int bw = Math.max(70, OriginText.width(font, vMode(modId, o.key)) + 34);
				int bx = x1 - 10 - bw;
				if (mx >= bx && mx <= x1 - 10) {
					int dir = mx < bx + bw / 2.0 ? -1 : 1;
					String cur = vMode(modId, o.key);
					int idx = 0;
					for (int i = 0; i < o.modes.length; i++) {
						if (o.modes[i].equals(cur)) {
							idx = i;
						}
					}
					vSetMode(modId, o.key, o.modes[(idx + dir + o.modes.length) % o.modes.length]);
					return true;
				}
			}
			case MULTISELECT -> {
				int bw = multiBtnW(x0, x1);
				int bx = x1 - 10 - bw;
				if (mx >= bx && mx <= x1 - 10) {
					java.util.List<String> allChoices = java.util.Arrays.asList(o.modes);
					java.util.List<String> sel = splitCsv(vMulti(modId, o.key));
					String mid = modId;
					String mkey = o.key;
					OriginMultiSelect.open(o.label, allChoices, sel,
							chosen -> vSetMulti(mid, mkey, joinCsv(chosen)));
					return true;
				}
			}
		}
		return false;
	}

	private void applySlider(ModOption o, double mx) {
		double t = Math.max(0, Math.min(1, (mx - dragTrackX0) / (double) (dragTrackX1 - dragTrackX0)));
		double v = o.min + t * (o.max - o.min);
		vSetNum(dragMod, o.key, Math.round(v / o.step) * o.step);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (OriginColorPicker.mouseDragged(mx, my, button)) {
			return true;
		}
		if (dragMod != null && dragOpt != null) {
			applySlider(dragOpt, mx);
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
		dragOpt = null;
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (OriginColorPicker.isOpen() || OriginMultiSelect.isOpen()) {
			return true;
		}
		if (page != null) {
			settingsScrollTarget = Math.max(0, Math.min(settingsMaxScroll, settingsScrollTarget - amount * 30));
		} else if (nav == Nav.MODS) {
			scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - amount * 30));
		} else {
			settingsTabScrollTarget = Math.max(0, Math.min(settingsTabMaxScroll, settingsTabScrollTarget - amount * 30));
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (OriginMultiSelect.isOpen()) {
			return OriginMultiSelect.keyPressed(keyCode);
		}
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
			if (page == null && nav == Nav.MODS && !search.isEmpty()) {
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
		if (page == null && nav == Nav.MODS && chr >= 32 && search.length() < 24) {
			search += chr;
			searchFocused = true;
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

	// ---- tooltip + helpers ----

	private String hoverTip;
	private int hoverTipX, hoverTipY;

	private void drawTooltip(Gfx g, int mx, int my, String text) {
		int maxW = 190;
		List<String> lines = wrapText(text, maxW);
		int tw = 0;
		for (String l : lines) {
			tw = Math.max(tw, OriginText.width(font, l));
		}
		int lh = font.lineHeight + 1;
		int th = lines.size() * lh + 7;
		int bx = mx + 12, by = my + 10;
		if (bx + tw + 12 > width) {
			bx = Math.max(4, width - tw - 12);
		}
		if (by + th > height) {
			by = Math.max(4, height - th - 2);
		}
		OriginUi.panel(g, bx, by, tw + 12, th, 6, 0xF01A1A1A, OriginTheme.STROKE_STRONG);
		int ty = by + 5;
		for (String l : lines) {
			OriginText.draw(g, font, l, bx + 6, ty, OriginTheme.TEXT, false);
			ty += lh;
		}
	}

	private List<String> wrapText(String s, int maxW) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : s.split(" ")) {
			String test = cur.length() == 0 ? word : cur + " " + word;
			if (OriginText.width(font, test) > maxW && cur.length() > 0) {
				out.add(cur.toString());
				cur = new StringBuilder(word);
			} else {
				cur = new StringBuilder(test);
			}
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		return out;
	}

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
