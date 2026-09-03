package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.hud.HudEditorScreen;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.mods.ModOption;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.Profiles;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// The Right Shift panel — 2026-09 "Command Deck" redesign (Slate identity):
//
//   * a wide, full-bleed glass panel (86% × 84% of the screen);
//   * a left CATEGORY RAIL: All / HUD / Visual / Gameplay filters, a divider,
//     then Profiles / Settings; the active row is an accent pill with a vertical
//     accent bar; Edit HUD + Close pinned at the rail's foot;
//   * the MODS page opens on a HERO SEARCH ROW (search-first, live result count)
//     over a scrolling list of DESCRIPTIVE ROW CARDS — icon · bold name + one-
//     line description · favourite star · inline iOS toggle — laid out 2-up when
//     the content is wide enough; "Pinned" / "All mods" section headers in the
//     All view. Clicking the toggle flips the mod, the star pins it, and the rest
//     of the row opens its settings page;
//   * everything drawn with smooth, anti-aliased ROUNDED corners (OriginUi.panel);
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

	// Profiles tab: the name currently being typed into the "new profile" field.
	private String profileInput = "";
	private boolean profileFocused = false;

	// Profiles feedback: a transient bottom-right "Profile changed" toast that
	// fades after ~1s (set on Apply), and the profile name currently awaiting a
	// delete confirmation ("Are you sure?" dialog). Both null/‑1 when idle.
	private long profileToastAt = -1;
	private String confirmDeleteName = null;

	public OriginModMenuScreen() {
		super(Component.literal("Origin Mods"));
	}

	@Override
	protected void init() {
		super.init();
		HudElements.editorPreview = true;
		HudElements.suppressScoreboard = true;
	}

	@Override
	public void removed() {
		HudElements.editorPreview = false;
		HudElements.suppressScoreboard = false;
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

	// 2026-09 "Command Deck" layout: a wide, full-bleed panel (not the old 78×76%
	// box) so the row cards have room to breathe on every GUI scale.
	private int pw() {
		return (int) (width * 0.86);
	}

	private int ph() {
		return (int) (height * 0.84);
	}

	/** Category rail width — a real rail with an icon-free label per row (All /
	 *  HUD / Visual / Gameplay, then Profiles / Settings), clamped so it never
	 *  eats content on small windows. */
	private int sbW() {
		return Math.max(88, Math.min(120, pw() * 17 / 100));
	}

	/** Content-region left edge (the divider sits here). */
	private int contentX() {
		return px() + sbW();
	}

	private int cx0() {
		return contentX() + 16;
	}

	private int cx1() {
		return px() + pw() - 16;
	}

	// ---- MODS page model: descriptive ROW cards (icon · name + one-line
	// description · favourite star · inline iOS toggle), laid out 2-up when the
	// content is wide enough, 1-up otherwise. Replaces the 4-per-row icon tiles.
	private static final int ROW_H = 40, GAP = 6, HEAD_H = 18;
	private int rowCols = 2, rowW = 200;
	private final List<Mods.Mod> filtered = new ArrayList<>();

	/** One laid-out thing on the mods page: a section header (mod == null) or a
	 *  mod row. y is relative to gridTop() BEFORE scroll, so render and hit-test
	 *  share exactly one geometry. */
	private record Item(Mods.Mod mod, String header, int x, int y, int w, int h) {
	}

	private final List<Item> items = new ArrayList<>();
	private int itemsH = 0;

	// section state
	enum Nav {MODS, PROFILES, SETTINGS}

	private Nav nav = Nav.MODS;

	// ---- Mod categories (the rail's filters). Static by id: the registry has no
	// category field, and the mapping is a design decision about how a PLAYER
	// thinks of each mod, not a data property. Anything unmapped lands in
	// Gameplay so a new mod never disappears from the menu.
	private enum Cat {
		ALL("All"), HUD("HUD"), VISUAL("Visual"), GAMEPLAY("Gameplay");

		final String label;

		Cat(String label) {
			this.label = label;
		}
	}

	private Cat cat = Cat.ALL;
	private static final java.util.Map<String, Cat> CATS = new java.util.HashMap<>();

	static {
		for (String id : new String[]{"fps", "cps", "armorhud", "keystrokes", "coords", "potionhud",
				"serveraddress", "scoreboard", "tablist"}) {
			CATS.put(id, Cat.HUD);
		}
		for (String id : new String[]{"zoom", "fullbright", "blockoverlay", "chunkborders", "hitboxes", "nametags",
				"itemsize", "weather", "timechanger", "motionblur", "colorsaturation", "particles"}) {
			CATS.put(id, Cat.VISUAL);
		}
		for (String id : new String[]{"togglesprint", "freelook", "chat", "waypoints", "jei"}) {
			CATS.put(id, Cat.GAMEPLAY);
		}
	}

	private static Cat catOf(Mods.Mod m) {
		return CATS.getOrDefault(m.id(), Cat.GAMEPLAY);
	}

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
	private static final int BAR_ON = 0xFF3DBE7A, BAR_ON_HOVER = 0xFF4CCB89;
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
		filtered.clear();
		String q = search.toLowerCase();
		for (Mods.Mod m : Mods.ALL) {
			if (cat != Cat.ALL && catOf(m) != cat) {
				continue;
			}
			if (q.isEmpty() || m.name().toLowerCase().contains(q)) {
				filtered.add(m);
			}
		}
		// Pinned (favourite) mods first, then registry order.
		filtered.sort((a, b) -> Boolean.compare(
				Mods.metaBool("fav:" + b.id(), false), Mods.metaBool("fav:" + a.id(), false)));

		// Lay the rows out ONCE per frame into `items`; render + click both read it.
		int gridW = cx1() - cx0();
		rowCols = gridW >= 400 ? 2 : 1;
		rowW = (gridW - (rowCols - 1) * GAP) / rowCols;
		items.clear();
		int y = 0, col = 0;
		String lastHeader = null;
		for (Mods.Mod m : filtered) {
			// Section headers only in the All view: "Pinned" (when any) then "All mods".
			String hdr = cat == Cat.ALL
					? (Mods.metaBool("fav:" + m.id(), false) ? "Pinned" : "All mods") : null;
			if (hdr != null && !hdr.equals(lastHeader)) {
				if (col != 0) {          // close the open row before a header
					y += ROW_H + GAP;
					col = 0;
				}
				if (y > 0) {
					y += 4;
				}
				items.add(new Item(null, hdr, cx0(), y, gridW, HEAD_H));
				y += HEAD_H;
				lastHeader = hdr;
			}
			items.add(new Item(m, null, cx0() + col * (rowW + GAP), y, rowW, ROW_H));
			col++;
			if (col == rowCols) {
				col = 0;
				y += ROW_H + GAP;
			}
		}
		if (col != 0) {
			y += ROW_H + GAP;
		}
		itemsH = Math.max(0, y - GAP);
	}

	/** Top of the scrolling row list — below the hero search row. */
	private int gridTop() {
		return py() + 58;
	}

	private double maxScroll() {
		return Math.max(0, itemsH - (py() + ph() - 14 - gridTop()));
	}

	// ---- render ----

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// NOTE: the live HUD preview over this menu is drawn ONCE, by the
		// ScreenEvents.afterRender hook registered in OriginClientMod. Do NOT also
		// call HudElements.renderAll(g) here — that double-drew every HUD element,
		// so translucent backings and text shadows blended onto themselves and read
		// darker/bolder than in-game (perf-audit + bug-audit finding).
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
				case PROFILES -> renderProfiles(g, mouseX, mouseY, now, t);
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

		// Profiles feedback overlays, drawn last so nothing paints over them.
		drawProfileToast(g, now);
		drawDeleteConfirm(g, mouseX, mouseY);
	}

	// Bottom-right "Profile changed" toast — fades in fast, holds, then fades out
	// over the last 300ms of its ~1s life. Positioned clear of the version stamp.
	private void drawProfileToast(GuiGraphics g, long now) {
		if (profileToastAt < 0) {
			return;
		}
		long dt = now - profileToastAt;
		long life = 1000;
		if (dt >= life) {
			profileToastAt = -1;
			return;
		}
		float a = dt < 120 ? dt / 120f : (dt > life - 300 ? (life - dt) / 300f : 1f);
		a = Math.max(0f, Math.min(1f, a));
		String msg = "Profile changed";
		int tw = OriginText.width(font, msg);
		int w = tw + 30, h = 26;
		int x = width - w - 14, y = height - h - 14;
		OriginUi.panel(g, x, y, w, h, 8, withAlpha(0xF01A1A1A, a), withAlpha(OriginTheme.STROKE_STRONG, a));
		OriginUi.star(g, x + 9, y + 8, 10, withAlpha(0xFF7ACF9E, a)); // small confirmation mark
		OriginText.draw(g, font, msg, x + 24, y + 9, withAlpha(0xFFDDE7E0, a), false);
	}

	// Centered "Are you sure?" modal for profile deletion. Geometry is shared with
	// clickDeleteConfirm so the hit boxes match exactly.
	private int[] confirmRect() {
		int w = 264, h = 98;
		return new int[]{(width - w) / 2, (height - h) / 2, w, h};
	}

	private void drawDeleteConfirm(GuiGraphics g, int mx, int my) {
		if (confirmDeleteName == null) {
			return;
		}
		g.fill(0, 0, width, height, 0x99000000); // scrim
		int[] r = confirmRect();
		int x = r[0], y = r[1], w = r[2], h = r[3];
		OriginUi.panel(g, x, y, w, h, 10, 0xF0121212, OriginTheme.STROKE_STRONG);
		OriginText.drawBold(g, font, "Delete this profile?", x + 16, y + 16, OriginTheme.TEXT, false);
		String sub = OriginText.ellipsize(font, "\"" + confirmDeleteName + "\" will be removed permanently.", w - 32);
		OriginText.draw(g, font, sub, x + 16, y + 36, OriginTheme.MUTED, false);

		int bw = 96, bh = 26, by = y + h - bh - 14;
		int cancelX = x + 16, delX = x + w - bw - 16;
		boolean cH = in(mx, my, cancelX, by, cancelX + bw, by + bh);
		boolean dH = in(mx, my, delX, by, delX + bw, by + bh);
		OriginUi.panel(g, cancelX, by, bw, bh, 7,
				cH ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL, cH ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER);
		OriginText.draw(g, font, "Cancel", cancelX + (bw - OriginText.width(font, "Cancel")) / 2, by + 9, OriginTheme.TEXT, false);
		OriginUi.panel(g, delX, by, bw, bh, 7,
				dH ? 0x66D4474F : 0x33D4474F, dH ? OriginTheme.STROKE_HOVER : 0xB3D4474F);
		OriginText.draw(g, font, "Delete", delX + (bw - OriginText.width(font, "Delete")) / 2, by + 9, 0xFFF08A90, false);
	}

	private boolean clickDeleteConfirm(double mx, double my) {
		int[] r = confirmRect();
		int x = r[0], y = r[1], w = r[2], h = r[3];
		int bw = 96, bh = 26, by = y + h - bh - 14;
		int cancelX = x + 16, delX = x + w - bw - 16;
		if (in(mx, my, delX, by, delX + bw, by + bh)) {
			Profiles.delete(confirmDeleteName);
			confirmDeleteName = null;
			return true;
		}
		if (in(mx, my, cancelX, by, cancelX + bw, by + bh)) {
			confirmDeleteName = null;
			return true;
		}
		if (in(mx, my, x, y, x + w, y + h)) {
			return true; // click inside the dialog but off the buttons — keep it open
		}
		confirmDeleteName = null; // click outside cancels
		return true;
	}

	// ---- sidebar ----

	// The rail's rows, top to bottom: 4 category filters, a divider, then the two
	// non-mod sections. railY(i) is THE geometry for both drawing and clicking.
	private static final String[] RAIL_CATS = {"All", "HUD", "Visual", "Gameplay"};
	private static final Cat[] RAIL_CAT_VALUES = {Cat.ALL, Cat.HUD, Cat.VISUAL, Cat.GAMEPLAY};
	private static final String[] RAIL_NAVS = {"Profiles", "Settings"};
	private static final Nav[] RAIL_NAV_VALUES = {Nav.PROFILES, Nav.SETTINGS};

	/** y of rail row `i` (0–3 categories, 4–5 Profiles/Settings, with a divider gap). */
	private int railY(int i) {
		return py() + 60 + i * RAIL_STEP + (i >= RAIL_CATS.length ? 12 : 0);
	}

	private void renderSidebar(GuiGraphics g, int mx, int my, float alpha) {
		int x = px() + 10;
		int w = sbW() - 20;

		// brand: Origin mark + wordmark, centered at the top of the rail
		int railCx = x + w / 2;
		OriginUi.logo(g, railCx, py() + 24, 20, alpha);
		int brandW = OriginText.widthBold(font, "ORIGIN");
		OriginText.drawBold(g, font, "ORIGIN", x + (w - brandW) / 2, py() + 38, withAlpha(OriginTheme.TEXT, alpha), clear);

		// divider between rail and content
		g.fill(contentX(), py() + 10, contentX() + 1, py() + ph() - 10, withAlpha(OriginTheme.STROKE, alpha));

		// category filters — active = the one whose mods are listed (kept lit while
		// one of its mods' settings page is open, so the player knows where they are)
		for (int i = 0; i < RAIL_CATS.length; i++) {
			int y = railY(i);
			boolean active = nav == Nav.MODS && cat == RAIL_CAT_VALUES[i];
			boolean hover = in(mx, my, x, y, x + w, y + RAIL_H);
			drawRailItem(g, x, y, w, RAIL_CATS[i], active, hover, alpha);
		}
		// divider, then Profiles / Settings
		int dy = railY(RAIL_CATS.length) - 6;
		g.fill(x + 6, dy, x + w - 6, dy + 1, withAlpha(OriginTheme.STROKE, alpha));
		for (int i = 0; i < RAIL_NAVS.length; i++) {
			int y = railY(RAIL_CATS.length + i);
			boolean active = nav == RAIL_NAV_VALUES[i] && page == null;
			boolean hover = in(mx, my, x, y, x + w, y + RAIL_H);
			drawRailItem(g, x, y, w, RAIL_NAVS[i], active, hover, alpha);
		}

		// bottom actions: Edit HUD + Close, pinned to the rail's foot.
		int by2 = py() + ph() - 26;
		int by1 = by2 - 20;
		drawSidebarButton(g, x, by1, w, "Edit HUD", in(mx, my, x, by1, x + w, by1 + 18), alpha, false);
		drawSidebarButton(g, x, by2, w, "Close", in(mx, my, x, by2, x + w, by2 + 18), alpha, true);
	}

	// Rail rows: a compact pill per row. The ACTIVE row is an accent-washed pill
	// with a vertical accent bar on its left edge (the accent's value
	// moment); hover is a faint glass fill; labels are left-aligned.
	private static final int RAIL_H = 20, RAIL_STEP = 23;

	private void drawRailItem(GuiGraphics g, int x, int y, int w, String label, boolean active, boolean hover, float alpha) {
		if (active) {
			OriginUi.panel(g, x, y, w, RAIL_H, 6,
					withAlpha(clear ? 0xC0181818 : OriginTheme.ACCENT_SOFT, alpha),
					withAlpha(OriginTheme.ACCENT_BORDER, alpha));
			int bx = x + 3, by0 = y + 5, bh = RAIL_H - 10;
			for (int s = 0; s < 4; s++) {
				int y0 = by0 + bh * s / 4, y1 = by0 + bh * (s + 1) / 4;
				g.fill(bx, y0, bx + 2, y1, withAlpha(OriginTheme.aurora(s / 3.0), alpha));
			}
		} else if (hover) {
			OriginUi.panel(g, x, y, w, RAIL_H, 6, withAlpha(clear ? 0xB0181818 : OriginTheme.BOX_FILL, alpha), 0);
		}
		OriginText.drawBold(g, font, label, x + 11, y + RAIL_H / 2 - 4,
				withAlpha(active ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), clear);
	}

	private void drawSidebarButton(GuiGraphics g, int x, int y, int w, String label, boolean hover, float alpha, boolean danger) {
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
		int x = px() + 10;
		int w = sbW() - 20;
		// category filters → the mods list, filtered (search is kept)
		for (int i = 0; i < RAIL_CATS.length; i++) {
			int y = railY(i);
			if (in(mx, my, x, y, x + w, y + RAIL_H)) {
				nav = Nav.MODS;
				cat = RAIL_CAT_VALUES[i];
				page = null;
				pageChangedAt = System.currentTimeMillis();
				profileFocused = false;
				scrollTarget = scroll = 0;
				return true;
			}
		}
		for (int i = 0; i < RAIL_NAVS.length; i++) {
			int y = railY(RAIL_CATS.length + i);
			if (in(mx, my, x, y, x + w, y + RAIL_H)) {
				nav = RAIL_NAV_VALUES[i];
				page = null;
				pageChangedAt = System.currentTimeMillis();
				searchFocused = false;
				profileFocused = false;
				scrollTarget = scroll = 0;
				return true;
			}
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

	// Search row geometry — shared by render + click.
	private static final int SEARCH_H = 26;

	private void renderMods(GuiGraphics g, int mouseX, int mouseY, long now, float alpha) {
		// HERO SEARCH ROW: the page opens on search (Lunar/OneConfig: search-first).
		// A taller glass field spanning the content, the placeholder naming the
		// active category, and the live result count right-aligned inside it.
		int sy = py() + 18;
		int sx = cx0();
		int sw = cx1() - cx0();
		OriginUi.panel(g, sx, sy, sw, SEARCH_H, 9,
				withAlpha(clear ? 0xC8101010 : (searchFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL), alpha),
				withAlpha(searchFocused ? OriginTheme.ACCENT_BORDER : OriginTheme.BOX_BORDER, alpha));
		OriginUi.icon(g, "@search", sx + 7, sy + 5, 16, withAlpha(clear ? OriginTheme.TEXT_DIM : OriginTheme.MUTED, alpha));
		String placeholder = "Search " + (cat == Cat.ALL ? "all mods" : cat.label.toLowerCase() + " mods");
		if (search.isEmpty() && !searchFocused) {
			OriginText.draw(g, font, placeholder, sx + 28, sy + 9,
					withAlpha(clear ? OriginTheme.TEXT_DIM : OriginTheme.MUTED, alpha), clear);
		} else {
			OriginText.draw(g, font, search, sx + 28, sy + 9, withAlpha(OriginTheme.TEXT, alpha), clear);
		}
		if (searchFocused) {
			float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(now / 350.0));
			int cw = OriginText.width(font, search);
			g.fill(sx + 28 + cw + 1, sy + 8, sx + 28 + cw + 2, sy + 18, withAlpha(OriginTheme.ACCENT, alpha * pulse));
		}
		String count = filtered.size() + (filtered.size() == 1 ? " mod" : " mods");
		OriginText.draw(g, font, count, sx + sw - 9 - OriginText.width(font, count), sy + 9,
				withAlpha(OriginTheme.MUTED, alpha), clear);

		// ROW LIST (scrolling): section headers + descriptive row cards.
		g.enableScissor(contentX(), gridTop(), px() + pw(), py() + ph() - 12);
		int top = gridTop(), off = (int) scroll;
		for (Item it : items) {
			int y = top + it.y() - off;
			if (y + it.h() < top - ROW_H || y > py() + ph()) {
				continue;
			}
			if (it.header() != null) {
				String hdr = it.header().toUpperCase();
				OriginText.drawBold(g, font, hdr, it.x(), y + 3, withAlpha(OriginTheme.MUTED, alpha), clear);
				int tw = OriginText.widthBold(font, hdr);
				g.fill(it.x() + tw + 8, y + 7, it.x() + it.w(), y + 8, withAlpha(OriginTheme.STROKE, alpha));
			} else {
				renderRow(g, it.mod(), it.x(), y, it.w(), mouseX, mouseY, alpha);
			}
		}
		g.disableScissor();
	}

	// Row-card geometry (right end), shared by render + click: the iOS toggle sits
	// at the right edge, the favourite star just left of it.
	private static final int SW_W = 30, STAR = 10;

	private int rowSwitchX(int x, int w) {
		return x + w - 10 - SW_W;
	}

	private int rowStarX(int x, int w) {
		return rowSwitchX(x, w) - 8 - STAR;
	}

	/** A descriptive row card: icon · bold name + one-line description · star ·
	 *  inline toggle. Enabled rows carry a faint accent wash so what's ON reads
	 *  at a glance; hover firms the glass and tints the border toward the accent. */
	private void renderRow(GuiGraphics g, Mods.Mod mod, int x, int y, int w, int mx, int my, float alpha) {
		boolean inBand = my >= gridTop() && my < py() + ph() - 12;
		boolean hover = inBand && in(mx, my, x, y, x + w, y + ROW_H);
		boolean on = Mods.on(mod.id());

		int fill = clear ? (hover ? 0xD8141414 : 0xC8101010) : (hover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL);
		OriginUi.panel(g, x, y, w, ROW_H, 9, withAlpha(fill, alpha),
				withAlpha(hover ? OriginTheme.BOX_BORDER_HOVER : OriginTheme.BOX_BORDER, alpha));
		if (on && !clear) {
			OriginUi.panel(g, x, y, w, ROW_H, 9, withAlpha(OriginTheme.ACCENT_SOFT, alpha * 0.6f), 0);
		}

		// icon, vertically centred at the left
		int ic = 24;
		OriginUi.icon(g, mod.id(), x + 10, y + (ROW_H - ic) / 2, ic, withAlpha(OriginTheme.TEXT, alpha));

		// name (bold) over a one-line description; both ellipsized to the text track
		int tx = x + 10 + ic + 10;
		int starX = rowStarX(x, w);
		int textW = Math.max(20, starX - 8 - tx);
		OriginText.drawBold(g, font, OriginText.ellipsize(font, mod.name(), textW), tx, y + 8,
				withAlpha(on ? OriginTheme.TEXT : OriginTheme.TEXT_DIM, alpha), clear);
		String desc = mod.description() == null || mod.description().isEmpty() ? catOf(mod).label : mod.description();
		OriginText.draw(g, font, OriginText.ellipsize(font, desc, textW), tx, y + 21,
				withAlpha(OriginTheme.MUTED, alpha), clear);

		// favourite star — gold when pinned, otherwise only on hover
		boolean fav = Mods.metaBool("fav:" + mod.id(), false);
		int starY = y + (ROW_H - STAR) / 2;
		boolean sHover = hover && in(mx, my, starX - 3, starY - 3, starX + STAR + 3, starY + STAR + 3);
		if (fav || hover) {
			OriginUi.star(g, starX, starY, STAR, withAlpha(fav ? 0xFFE3C15C : (sHover ? 0xFFFFFFFF : 0x80FFFFFF), alpha));
		}

		// inline iOS toggle (mint on / coral off)
		int swX = rowSwitchX(x, w), swH = SW_W * 8 / 15;
		OriginUi.switchAt(g, mod.id(), swX, y + (ROW_H - swH) / 2, SW_W, on, true);
	}

	// ---- PROFILES page ----

	private void renderProfiles(GuiGraphics g, int mouseX, int mouseY, long now, float alpha) {
		int x0 = cx0(), x1 = cx1();
		int y = py() + 18;

		OriginText.drawBold(g, font, "PROFILES", x0, y - 1, withAlpha(OriginTheme.MUTED, alpha), clear);
		y += 14;
		OriginText.draw(g, font, "Save the whole current loadout under a name, then switch instantly.",
				x0, y, withAlpha(OriginTheme.MUTED, alpha), clear);
		y += 16;

		// new-profile name field + Save button
		int btnW = 70;
		int fieldW = x1 - x0 - btnW - 8;
		OriginUi.panel(g, x0, y, fieldW, 22, 8,
				withAlpha(clear ? 0xC8101010 : (profileFocused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL), alpha),
				withAlpha(profileFocused ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER, alpha));
		if (profileInput.isEmpty() && !profileFocused) {
			OriginText.draw(g, font, "New profile name…", x0 + 8, y + 7, withAlpha(OriginTheme.MUTED, alpha), clear);
		} else {
			OriginText.draw(g, font, profileInput, x0 + 8, y + 7, withAlpha(OriginTheme.TEXT, alpha), clear);
			if (profileFocused) {
				float pulse = 0.35f + 0.65f * (float) Math.abs(Math.sin(now / 350.0));
				int cw = OriginText.width(font, profileInput);
				g.fill(x0 + 8 + cw + 1, y + 6, x0 + 8 + cw + 2, y + 16, withAlpha(OriginTheme.TEXT, alpha * pulse));
			}
		}
		boolean canSave = !profileInput.trim().isEmpty();
		int saveX = x1 - btnW;
		boolean saveHover = canSave && in(mouseX, mouseY, saveX, y, saveX + btnW, y + 22);
		OriginUi.panel(g, saveX, y, btnW, 22, 8,
				withAlpha(saveHover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL, alpha),
				withAlpha(saveHover ? OriginTheme.STROKE_HOVER : OriginTheme.BOX_BORDER, alpha));
		OriginText.draw(g, font, "Save", saveX + (btnW - OriginText.width(font, "Save")) / 2, y + 7,
				withAlpha(canSave ? OriginTheme.TEXT : OriginTheme.MUTED, alpha), clear);
		y += 34;

		OriginText.drawBold(g, font, "SAVED", x0, y, withAlpha(OriginTheme.MUTED, alpha), clear);
		g.fill(x0 + OriginText.widthBold(font, "SAVED") + 8, y + 4, x1, y + 5, withAlpha(OriginTheme.STROKE, alpha));
		y += 14;

		java.util.List<String> names = Profiles.names();
		int top = y;
		int bottom = py() + ph() - 14;
		g.enableScissor(contentX(), top, px() + pw(), bottom);
		int ry = top - (int) settingsTabScroll;
		if (names.isEmpty()) {
			OriginText.draw(g, font, "No profiles yet — type a name above and hit Save.", x0, ry + 4,
					withAlpha(OriginTheme.MUTED, alpha), clear);
		}
		for (String nm : names) {
			if (ry + 30 >= top && ry <= bottom) {
				renderProfileRow(g, nm, x0, x1, ry, mouseX, mouseY, alpha);
			}
			ry += 34;
		}
		g.disableScissor();
		settingsTabMaxScroll = Math.max(0, names.size() * 34 - (bottom - top));
	}

	private void renderProfileRow(GuiGraphics g, String name, int x0, int x1, int y, int mx, int my, float alpha) {
		OriginUi.panel(g, x0, y, x1 - x0, 30, 8, withAlpha(clear ? 0xC0101010 : OriginTheme.BOX_FILL, alpha),
				withAlpha(OriginTheme.BOX_BORDER, alpha));
		OriginText.drawBold(g, font, OriginText.ellipsize(font, name, x1 - x0 - 150), x0 + 10, y + 11,
				withAlpha(OriginTheme.TEXT, alpha), clear);

		int delW = 58, appW = 60;
		int delX = x1 - 8 - delW;
		int appX = delX - 6 - appW;
		boolean appHover = in(mx, my, appX, y + 5, appX + appW, y + 25);
		boolean delHover = in(mx, my, delX, y + 5, delX + delW, y + 25);
		OriginUi.panel(g, appX, y + 5, appW, 20, 6,
				withAlpha(appHover ? 0x463DBE7A : 0x2E3DBE7A, alpha),
				withAlpha(appHover ? OriginTheme.STROKE_HOVER : 0xB33DBE7A, alpha));
		OriginText.draw(g, font, "Apply", appX + (appW - OriginText.width(font, "Apply")) / 2, y + 11, withAlpha(0xFF7ACF9E, alpha), false);
		OriginUi.panel(g, delX, y + 5, delW, 20, 6,
				withAlpha(delHover ? 0x46D4474F : 0x2ED4474F, alpha),
				withAlpha(delHover ? OriginTheme.STROKE_HOVER : 0xB3D4474F, alpha));
		OriginText.draw(g, font, "Delete", delX + (delW - OriginText.width(font, "Delete")) / 2, y + 11, withAlpha(0xFFF08A90, alpha), false);
	}

	private boolean clickProfiles(double mx, double my) {
		int x0 = cx0(), x1 = cx1();
		int y = py() + 18 + 14 + 16;
		int btnW = 70;
		int fieldW = x1 - x0 - btnW - 8;
		// name field focus
		profileFocused = in(mx, my, x0, y, x0 + fieldW, y + 22);
		if (profileFocused) {
			return true;
		}
		// save
		int saveX = x1 - btnW;
		if (in(mx, my, saveX, y, saveX + btnW, y + 22) && !profileInput.trim().isEmpty()) {
			Profiles.save(profileInput);
			profileInput = "";
			return true;
		}
		// list
		int top = y + 34 + 14;
		int bottom = py() + ph() - 14;
		if (my >= top && my <= bottom) {
			java.util.List<String> names = Profiles.names();
			int ry = top - (int) settingsTabScroll;
			for (String nm : names) {
				if (my >= ry && my < ry + 30) {
					int delW = 58, appW = 60;
					int delX = x1 - 8 - delW;
					int appX = delX - 6 - appW;
					if (in(mx, my, appX, ry + 5, appX + appW, ry + 25)) {
						Profiles.apply(nm);
						profileToastAt = System.currentTimeMillis(); // "Profile changed" toast
						return true;
					}
					if (in(mx, my, delX, ry + 5, delX + delW, ry + 25)) {
						confirmDeleteName = nm; // opens the "Are you sure?" dialog
						return true;
					}
					return true;
				}
				ry += 34;
			}
		}
		return true; // profiles clicks never fall through to cards
	}

	// ---- SETTINGS page ----

	private void renderSettingsPage(GuiGraphics g, int mx, int my, long now, float alpha) {
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
	private void renderMenuSettings(GuiGraphics g, int x0, int x1, int top, int mx, int my, long now, float alpha) {
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

	private void drawTab(GuiGraphics g, int tx, int ty, int w, int h, String label, boolean active, boolean hover, float alpha) {
		// White text always; the underline is the sole active indicator — white when
		// active, a dimmed grey placeholder otherwise (always present).
		OriginText.drawBold(g, font, label, tx + (w - OriginText.widthBold(font, label)) / 2, ty + (h - 8) / 2,
				withAlpha(0xFFFFFFFF, alpha), clear);
		int underY = ty + h - 2;
		if (active) {
			auroraUnderline(g, tx + 4, underY, w - 8, 2, alpha);
		} else {
			g.fill(tx + 4, underY, tx + w - 4, underY + 2, withAlpha(hover ? 0x80FFFFFF : 0x40FFFFFF, alpha));
		}
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

	private void renderSettings(GuiGraphics g, int mouseX, int mouseY, long now, float alpha, Mods.Mod mod) {
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

	private void drawRows(GuiGraphics g, String id, int x0, int x1, int top, int bottom, int mouseX, int mouseY, float alpha) {
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

	private void renderRow(GuiGraphics g, String modId, ModOption o, int x0, int x1, int y, int mx, int my, float alpha) {
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
		// The delete-confirmation dialog is modal: it swallows every click until
		// the player picks Delete or Cancel (a click outside cancels).
		if (confirmDeleteName != null) {
			if (button == 0) {
				return clickDeleteConfirm(mx, my);
			}
			return true;
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
			case PROFILES -> {
				searchFocused = false;
				return clickProfiles(mx, my);
			}
			case SETTINGS -> {
				searchFocused = false;
				profileFocused = false;
				return clickSettingsPage(mx, my);
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickMods(double mx, double my) {
		int sy = py() + 18;
		int sx = cx0();
		int sw = cx1() - cx0();
		searchFocused = in(mx, my, sx, sy, sx + sw, sy + SEARCH_H);
		if (searchFocused) {
			return true;
		}
		int gTop = gridTop(), gBot = py() + ph() - 12;
		if (my >= gTop && my < gBot) {
			int off = (int) scroll;
			for (Item it : items) {
				if (it.mod() == null) {
					continue;              // section header — not clickable
				}
				int x = it.x(), y = gTop + it.y() - off, w = it.w();
				if (!in(mx, my, x, y, x + w, y + ROW_H)) {
					continue;
				}
				Mods.Mod mod = it.mod();
				// favourite star (left of the toggle) → pin / unpin
				int starX = rowStarX(x, w), starY = y + (ROW_H - STAR) / 2;
				if (in(mx, my, starX - 3, starY - 3, starX + STAR + 3, starY + STAR + 3)) {
					Mods.setMetaBool("fav:" + mod.id(), !Mods.metaBool("fav:" + mod.id(), false));
					return true;
				}
				// toggle (right end, with a little slack) → enable / disable
				if (mx >= rowSwitchX(x, w) - 4) {
					Mods.setOn(mod.id(), !Mods.on(mod.id()));
					return true;
				}
				// anywhere else on the row → open the mod's settings page
				// (waypoints + item size own full screens)
				if (mod.id().equals("waypoints")) {
					Minecraft.getInstance().setScreen(new com.origin.client.client.waypoints.WaypointScreen());
				} else if (mod.id().equals("itemsize")) {
					Minecraft.getInstance().setScreen(new OriginItemSizeScreen());
				} else {
					page = mod.id();
					pageChangedAt = System.currentTimeMillis();
					settingsSearch = "";
					settingsScroll = settingsScrollTarget = 0;
				}
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
					String k = modId + ":" + o.key;
					if (OriginColorPicker.isOpen() && k.equals(OriginColorPicker.openKey())) {
						OriginColorPicker.close();
					} else {
						OriginColorPicker.open(modId, o.key, o.label, x0, y + 26);
					}
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
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (OriginColorPicker.isOpen() || OriginMultiSelect.isOpen()) {
			return true;
		}
		if (page != null) {
			settingsScrollTarget = Math.max(0, Math.min(settingsMaxScroll, settingsScrollTarget - sy * 30));
		} else if (nav == Nav.MODS) {
			scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - sy * 30));
		} else {
			settingsTabScrollTarget = Math.max(0, Math.min(settingsTabMaxScroll, settingsTabScrollTarget - sy * 30));
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
			if (confirmDeleteName != null) { // ESC cancels the delete dialog first
				confirmDeleteName = null;
				return true;
			}
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
		if (keyCode == GLFW.GLFW_KEY_ENTER && profileFocused && !profileInput.trim().isEmpty()) {
			Profiles.save(profileInput);
			profileInput = "";
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			if (page == null && nav == Nav.MODS && !search.isEmpty()) {
				search = search.substring(0, search.length() - 1);
				scrollTarget = 0;
				return true;
			}
			if (page == null && nav == Nav.PROFILES && profileFocused && !profileInput.isEmpty()) {
				profileInput = profileInput.substring(0, profileInput.length() - 1);
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
		if (page == null && nav == Nav.PROFILES && profileFocused && chr >= 32 && profileInput.length() < 28) {
			profileInput += chr;
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

	private void drawTooltip(GuiGraphics g, int mx, int my, String text) {
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

	/** The accent underline: the accent's value ramp (one hue) `wpx` wide starting at
	 *  (x,y), `th` tall, faded by alpha. The one gradient moment on the active nav
	 *  item / sub-tab — a few flat segments so the highlight drifts across it
	 *  without a shader. */
	private static void auroraUnderline(GuiGraphics g, int x, int y, int wpx, int th, float alpha) {
		int seg = 8;
		for (int s = 0; s < seg; s++) {
			int sx = x + (int) Math.round(wpx * (s / (double) seg));
			int ex = x + (int) Math.round(wpx * ((s + 1) / (double) seg));
			g.fill(sx, y, ex, y + th, withAlpha(OriginTheme.aurora(s / (double) (seg - 1)), alpha));
		}
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
