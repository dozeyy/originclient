package com.origin.client.client.waypoints;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The Waypoints manager + editor.
//
// SAVE MODEL (Will): editing an EXISTING waypoint auto-saves live — every
// keystroke/toggle/colour/slider change persists immediately. CREATING is a
// draft: "＋ Create Waypoint" opens the editor on a detached waypoint that is
// NOT in the list and NOT rendered in-world; it only becomes real when the
// player clicks Done. ESC (or closing) discards an uncommitted draft.
//
// The editor is organised into labelled sections — name/group, LOCATION (pos +
// dimension), STYLE (waypoint/text/background colours + scale), DISPLAY (icon /
// text / beam / distance / highlight) — with a Done button at the bottom.
//
// List: ungrouped waypoints at top level; each group is a collapsible header row
// (big filled triangle, click to expand/collapse) with its waypoints nested.
// The list scrolls SMOOTHLY (eased toward a scroll target, like the mod menu).
public class WaypointScreen extends Screen {
	private static final String[] DIMS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
	// scratch mod-option keys the shared colour picker edits (one per colour target)
	private static final String KEY_COLOR = "editColor";
	private static final String KEY_ICON = "editIconColor";
	private static final String KEY_TEXT = "editTextColor";
	private static final String KEY_TEXTBG = "editTextBg";
	// Collapsed group names — static so the collapse state survives reopening the menu.
	private static final Set<String> COLLAPSED = new HashSet<>();

	// form layout (offsets from the form panel's top edge)
	private static final int ROW_NAME = 6, EB_LOC = 26, ROW_POS = 36, ROW_DIM = 54,
			EB_STYLE = 74, ROW_COLOR = 84, ROW_SCALE = 102, EB_DISP = 122, ROW_TOG = 132, ROW_DONE = 152;
	private static final int FORM_H = 180;

	// smooth scroll: target moves on wheel input, the drawn offset eases toward it
	private double scroll = 0, scrollTarget = 0;
	private long lastFrameNanos = 0;

	private Waypoints.Waypoint editing = null;   // non-null → the editor is open on this waypoint
	private boolean isNew = false;               // true → `editing` is an uncommitted draft
	// text buffers (typing "12" char-by-char or a lone "-" needs a buffer; every
	// parseable change is applied to `editing` immediately)
	private String nameStr = "", groupStr = "", xStr = "", yStr = "", zStr = "";
	// which scale bar is being dragged: -1 none, 0 icon, 1 text, 2 distance
	private int dragSlider = -1;
	// focused text field: 0 name, 1 group, 2 X, 3 Y, 4 Z, -1 none
	private int focus = -1;
	private Waypoints.Waypoint pendingDelete = null;
	private boolean openToCreate = false;

	// One row in the visible list: either a group header or a waypoint.
	private record Row(String group, Waypoints.Waypoint wp) {
	}

	public WaypointScreen() {
		super(Component.literal("Waypoints"));
	}

	/** Opens the manager; if startCreating, opens the editor on a fresh DRAFT
	 *  (committed only when the player clicks Done) — the create keybind. */
	public WaypointScreen(boolean startCreating) {
		super(Component.literal("Waypoints"));
		this.openToCreate = startCreating;
	}

	@Override
	protected void init() {
		super.init();
		if (openToCreate) {
			openToCreate = false;
			createDraft();
		}
	}

	@Override
	public void removed() {
		// Existing-waypoint edits were saved live; an uncommitted draft is dropped.
		Waypoints.save();
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.close();
		}
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- current position ----
	private int standX() {
		var p = Minecraft.getInstance().player;
		return p != null ? p.blockPosition().getX() : 0;
	}

	private int standY() {
		var p = Minecraft.getInstance().player;
		return p != null ? p.blockPosition().getY() : 64;
	}

	private int standZ() {
		var p = Minecraft.getInstance().player;
		return p != null ? p.blockPosition().getZ() : 0;
	}

	// Ground snap for "Use Current": the block the player is STANDING ON — i.e. the
	// first solid ground at/below the feet. blockPosition() is the feet cell (air),
	// so this starts there and walks DOWN, skipping leaves/logs and passing through
	// water/lava, and lands on the first block with a real collision shape (grass,
	// stone, gravel, sand, …). On flat ground that's feet-1 (fixes the off-by-one:
	// the waypoint sits on the ground, not one block up in the air).
	private int groundY() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return standY();
		}
		net.minecraft.world.level.Level level = mc.level;
		net.minecraft.core.BlockPos.MutableBlockPos p = mc.player.blockPosition().mutable();
		int min = level.getMinBuildHeight();
		for (int i = 0; i < 384 && p.getY() >= min; i++) {
			if (isGround(level, p)) {
				return p.getY();
			}
			p.move(net.minecraft.core.Direction.DOWN);
		}
		return mc.player.blockPosition().getY() - 1;   // fallback: block under the feet
	}

	private static boolean isGround(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos p) {
		net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
		if (st.isAir()) {
			return false;
		}
		if (!st.getFluidState().isEmpty()) {
			return false;   // water / lava → pass through
		}
		if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)) {
			return false;   // don't land on a tree
		}
		return !st.getCollisionShape(level, p).isEmpty();   // solid ground only
	}

	// ---- geometry ----
	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return (height - ph()) / 2;
	}

	private int pw() {
		return Math.min(430, (int) (width * 0.72));
	}

	private int ph() {
		return (int) (height * 0.84);
	}

	private int formY() {
		return py() + 60;
	}

	private int listTop() {
		return py() + (editing != null ? 60 + FORM_H + 8 : 62);
	}

	private int listBottom() {
		return py() + ph() - 12;
	}

	// The visible list rows: ungrouped waypoints first, then each group as a header
	// with its members nested (skipped while collapsed). A create-draft is not in
	// Waypoints.all(), so it never shows here until committed.
	private List<Row> rows() {
		List<Row> out = new ArrayList<>();
		Map<String, List<Waypoints.Waypoint>> groups = new LinkedHashMap<>();
		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (w.group == null || w.group.isEmpty()) {
				out.add(new Row(null, w));
			} else {
				groups.computeIfAbsent(w.group, k -> new ArrayList<>()).add(w);
			}
		}
		for (var e : groups.entrySet()) {
			out.add(new Row(e.getKey(), null));
			if (!COLLAPSED.contains(e.getKey())) {
				for (Waypoints.Waypoint w : e.getValue()) {
					out.add(new Row(null, w));
				}
			}
		}
		return out;
	}

	// ---- edit plumbing ----
	// New waypoint = a detached draft: NOT added to the list (so it doesn't render
	// or save) until Done commits it.
	private void createDraft() {
		Waypoints.Waypoint w = new Waypoints.Waypoint();
		w.name = Waypoints.nextName("Waypoint");
		w.x = standX();
		w.y = groundY();
		w.z = standZ();
		w.dimension = Waypoints.currentDim();
		editing = w;
		isNew = true;
		fillBuffers(w);
	}

	private void beginEdit(Waypoints.Waypoint w) {
		editing = w;
		isNew = false;
		fillBuffers(w);
	}

	private void fillBuffers(Waypoints.Waypoint w) {
		focus = -1;
		nameStr = w.name;
		groupStr = w.group == null ? "" : w.group;
		xStr = String.valueOf(w.x);
		yStr = String.valueOf(w.y);
		zStr = String.valueOf(w.z);
	}

	/** Persist — but only for live edits of an existing waypoint. Draft changes
	 *  stay in memory until Done commits them. */
	private void persist() {
		if (!isNew) {
			Waypoints.save();
		}
	}

	/** Done: commit a draft into the list, or just close the editor for an
	 *  existing waypoint (its edits are already saved). */
	private void commitDone() {
		if (editing != null && isNew) {
			Waypoints.add(editing);
			isNew = false;
		}
		editing = null;
		focus = -1;
	}

	// Push the text buffers onto the live waypoint (called on every keystroke).
	// Coord buffers only apply when they parse; a lone "-" or empty field just
	// waits for more typing.
	private void applyBuffers() {
		if (editing == null) {
			return;
		}
		editing.name = nameStr.isEmpty() ? editing.name : nameStr;
		editing.group = groupStr;
		Integer x = tryParse(xStr), y = tryParse(yStr), z = tryParse(zStr);
		if (x != null) {
			editing.x = x;
		}
		if (y != null) {
			editing.y = y;
		}
		if (z != null) {
			editing.z = z;
		}
		persist();
	}

	private static Integer tryParse(String s) {
		if (s.isEmpty() || s.equals("-")) {
			return null;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// The shared picker edits a scratch mod-option key; mirror it back onto the
	// waypoint field it's targeting, every frame it's open.
	private void syncPicker() {
		if (editing == null || !OriginColorPicker.isOpen()) {
			return;
		}
		String k = OriginColorPicker.openKey();
		if (("waypoints:" + KEY_COLOR).equals(k)) {
			editing.color = Mods.color("waypoints", KEY_COLOR);
		} else if (("waypoints:" + KEY_ICON).equals(k)) {
			editing.iconColor = Mods.color("waypoints", KEY_ICON);
		} else if (("waypoints:" + KEY_TEXT).equals(k)) {
			editing.textColor = Mods.color("waypoints", KEY_TEXT);
		} else if (("waypoints:" + KEY_TEXTBG).equals(k)) {
			editing.textBgColor = Mods.color("waypoints", KEY_TEXTBG);
		}
	}

	// ---- render ----
	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		syncPicker();
		// smooth scroll: ease the drawn offset toward the wheel target
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 16.7 : Math.min(64.0, (now - lastFrameNanos) / 1_000_000.0);
		lastFrameNanos = now;
		scroll += (scrollTarget - scroll) * Math.min(1.0, dt / 60.0);

		int x = px(), y = py(), w = pw(), h = ph();
		OriginUi.panel(g, x, y, w, h, 10, 0xC80E0E0E, OriginTheme.STROKE);
		OriginUi.logo(g, x + 22, y + 20, 22, 1f);
		g.drawString(font, "Waypoints", x + 42, y + 10, OriginTheme.TEXT, true);
		int total = Waypoints.all().size();
		g.drawString(font, total + (total == 1 ? " waypoint" : " waypoints"), x + 42, y + 22, 0xFFB0B0B0, true);

		int by = y + 36;
		button(g, x + 12, by, 90, 18, editing != null ? "Done" : "＋ Create", mouseX, mouseY);
		// right-aligned global-toggle switches: Deaths · Locator Bar · (Separate Bar
		// appears only when Locator Bar is on).
		for (Tog t : globalToggles()) {
			g.drawString(font, t.label(), t.x0(), by + 5, 0xFFFFFFFF, true);
			OriginUi.switchAt(g, "gt:" + t.key(), t.x0() + font.width(t.label()) + 4, by + 1, 22, t.on(), true);
		}

		if (editing != null) {
			renderForm(g, x, formY(), w, mouseX, mouseY);
		}

		int top = listTop(), bottom = listBottom();
		g.enableScissor(x, top, x + w, bottom);
		int ry = top - (int) Math.round(scroll);
		for (Row row : rows()) {
			if (ry + 24 >= top && ry <= bottom) {
				if (row.group() != null) {
					renderGroupRow(g, row.group(), x + 12, ry, w - 24, mouseX, mouseY);
				} else {
					boolean nested = row.wp().group != null && !row.wp().group.isEmpty();
					int indent = nested ? 14 : 0;
					renderRow(g, row.wp(), x + 12 + indent, ry, w - 24 - indent, mouseX, mouseY);
				}
			}
			ry += 28;
		}
		g.disableScissor();
		if (Waypoints.all().isEmpty() && editing == null) {
			g.drawString(font, "No waypoints yet — click Create Waypoint.", x + 14, top + 6, 0xFFB0B0B0, true);
		}

		if (pendingDelete != null) {
			renderConfirm(g, mouseX, mouseY);
		}
		String hint = editing != null && isNew ? "Done saves · Esc discards" : "Esc saves + closes";
		g.drawString(font, hint, x + w - 10 - font.width(hint), y + h - 12, 0xFF9A9A9A, true);

		// The colour picker draws last so it floats over everything.
		OriginColorPicker.render(g, mouseX, mouseY);
	}

	private void eyebrow(GuiGraphics g, int x, int y, String label) {
		g.drawString(font, label, x, y, 0xFF8A8A8A, true);
	}

	// A right-aligned header toggle switch (label + switch), computed once and shared
	// by render + click so their hit-boxes always match.
	private record Tog(int x0, int x1, String key, boolean on, String label) {
	}

	private java.util.List<Tog> globalToggles() {
		java.util.List<Tog> out = new java.util.ArrayList<>();
		int rx = px() + pw() - 12;
		rx = addTog(out, rx, "deathWaypoints", "Deaths");
		rx = addTog(out, rx, "locatorBar", "Locator");
		if (Mods.bool("waypoints", "locatorBar")) {
			addTog(out, rx, "separateBar", "Separate");
		}
		return out;
	}

	private int addTog(java.util.List<Tog> out, int rx, String key, String label) {
		int total = font.width(label) + 4 + 22;
		int x0 = rx - total;
		out.add(new Tog(x0, rx, key, Mods.bool("waypoints", key), label));
		return x0 - 8;   // gap before the next toggle (to the left)
	}

	// Small "1.3x" readout centered under a scale bar.
	private void scaleValue(GuiGraphics g, int sliderX, int formTop, double v) {
		// 2 decimals when the 0.05 steps need it (e.g. 0.25x), else 1.
		String s = (Math.round(v * 100) % 10 == 0 ? String.format("%.1fx", v) : String.format("%.2fx", v));
		g.drawString(font, s, sliderX + (64 - font.width(s)) / 2, formTop + ROW_SCALE + 9, 0xFF9A9A9A, true);
	}

	// The editor, organised into sections: name/group → LOCATION → STYLE → DISPLAY.
	private void renderForm(GuiGraphics g, int x, int y, int w, int mx, int my) {
		OriginUi.panel(g, x + 12, y, w - 24, FORM_H, 8, 0xD8101010, OriginTheme.STROKE_STRONG);
		// name + group (one row)
		g.drawString(font, "Name", x + 20, y + ROW_NAME + 3, 0xFFFFFFFF, true);
		field(g, x + 56, y + ROW_NAME, 118, 14, nameStr, focus == 0, "name");
		g.drawString(font, "Group", x + 184, y + ROW_NAME + 3, 0xFFFFFFFF, true);
		field(g, x + 222, y + ROW_NAME, 88, 14, groupStr, focus == 1, "(none)");
		// LOCATION
		eyebrow(g, x + 20, y + EB_LOC, "LOCATION");
		g.drawString(font, "Pos", x + 20, y + ROW_POS + 3, 0xFFFFFFFF, true);
		field(g, x + 44, y + ROW_POS, 56, 14, xStr, focus == 2, "X");
		field(g, x + 104, y + ROW_POS, 56, 14, yStr, focus == 3, "Y");
		field(g, x + 164, y + ROW_POS, 56, 14, zStr, focus == 4, "Z");
		button(g, x + 226, y + ROW_POS, 84, 14, "Use Current", mx, my);
		g.drawString(font, "Dim", x + 20, y + ROW_DIM + 3, 0xFFFFFFFF, true);
		button(g, x + 44, y + ROW_DIM, 130, 14, "< " + dimDisplay(editing.dimension) + " >", mx, my);
		// STYLE — a colour per part (beam/highlight, icon, text, label background)
		// and a scale bar per part (icon, text, distance).
		eyebrow(g, x + 20, y + EB_STYLE, "STYLE");
		g.drawString(font, "Color", x + 20, y + ROW_COLOR + 3, 0xFFFFFFFF, true);
		OriginUi.panel(g, x + 56, y + ROW_COLOR, 30, 14, 5, editing.color, OriginTheme.STROKE_HOVER);
		g.drawString(font, "Icon", x + 94, y + ROW_COLOR + 3, 0xFFFFFFFF, true);
		OriginUi.panel(g, x + 122, y + ROW_COLOR, 30, 14, 5, editing.iconColor, OriginTheme.STROKE_HOVER);
		g.drawString(font, "Text", x + 160, y + ROW_COLOR + 3, 0xFFFFFFFF, true);
		OriginUi.panel(g, x + 188, y + ROW_COLOR, 30, 14, 5, editing.textColor, OriginTheme.STROKE_HOVER);
		g.drawString(font, "BG", x + 226, y + ROW_COLOR + 3, 0xFFFFFFFF, true);
		OriginUi.panel(g, x + 246, y + ROW_COLOR, 30, 14, 5, editing.textBgColor, OriginTheme.STROKE_HOVER);
		// scale bars: 0.5x .. 2.0x each, with a live value readout under each bar
		g.drawString(font, "Icon", x + 20, y + ROW_SCALE + 2, 0xFFFFFFFF, true);
		OriginUi.slider(g, x + 48, y + ROW_SCALE, 64, fracFromScale(editing.iconScale), dragSlider == 0);
		g.drawString(font, "Text", x + 124, y + ROW_SCALE + 2, 0xFFFFFFFF, true);
		OriginUi.slider(g, x + 152, y + ROW_SCALE, 64, fracFromScale(editing.textScale), dragSlider == 1);
		g.drawString(font, "Dist", x + 228, y + ROW_SCALE + 2, 0xFFFFFFFF, true);
		OriginUi.slider(g, x + 256, y + ROW_SCALE, 64, fracFromScale(editing.distScale), dragSlider == 2);
		scaleValue(g, x + 48, y, editing.iconScale);
		scaleValue(g, x + 152, y, editing.textScale);
		scaleValue(g, x + 256, y, editing.distScale);
		// DISPLAY
		eyebrow(g, x + 20, y + EB_DISP, "DISPLAY");
		toggle(g, x + 20, y + ROW_TOG, "Icon", editing.showIcon);
		toggle(g, x + 80, y + ROW_TOG, "Text", editing.showText);
		toggle(g, x + 140, y + ROW_TOG, "Beam", editing.showBeam);
		toggle(g, x + 204, y + ROW_TOG, "Distance", editing.showDistance);
		toggle(g, x + 286, y + ROW_TOG, "Highlight", editing.highlightBlock);
		// Done
		button(g, x + 20, y + ROW_DONE, 130, 20, "Done", mx, my);
	}

	private void field(GuiGraphics g, int x, int y, int w, int h, String val, boolean focused, String placeholder) {
		OriginUi.panel(g, x, y, w, h, 5, focused ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				focused ? 0xFFFFFFFF : OriginTheme.BOX_BORDER);
		if (val.isEmpty() && !focused) {
			g.drawString(font, placeholder, x + 4, y + (h - 8) / 2, 0xFF9A9A9A, true);
		} else {
			g.drawString(font, focused ? val + "_" : val, x + 4, y + (h - 8) / 2, 0xFFFFFFFF, true);
		}
	}

	// Big filled expand/collapse triangle (Will: the old glyph arrow was too small).
	private void triangle(GuiGraphics g, int x, int y, boolean open, int color) {
		if (open) {
			// down-pointing: 11 wide tapering over 6 rows
			for (int i = 0; i < 6; i++) {
				g.fill(x + i, y + i, x + 11 - i, y + i + 1, color);
			}
		} else {
			// right-pointing: 6 columns tapering from 11 tall
			for (int i = 0; i < 6; i++) {
				g.fill(x + i, y + i, x + i + 1, y + 11 - i, color);
			}
		}
	}

	private void renderGroupRow(GuiGraphics g, String group, int x, int y, int w, int mx, int my) {
		boolean hover = mx >= x && mx <= x + w && my >= y && my < y + 24;
		OriginUi.panel(g, x, y, w, 24, 8, hover ? OriginTheme.BOX_FILL_HOVER : 0x40101010,
				hover ? 0xFFFFFFFF : OriginTheme.BOX_BORDER);
		boolean open = !COLLAPSED.contains(group);
		triangle(g, x + 8, y + (open ? 9 : 7), open, 0xFFFFFFFF);
		g.drawString(font, group, x + 26, y + 8, 0xFFFFFFFF, true);
		int count = 0;
		for (Waypoints.Waypoint wp : Waypoints.all()) {
			if (group.equals(wp.group)) {
				count++;
			}
		}
		String n = count + (count == 1 ? " waypoint" : " waypoints");
		g.drawString(font, n, x + w - 10 - font.width(n), y + 8, 0xFF9A9A9A, true);
	}

	private void renderRow(GuiGraphics g, Waypoints.Waypoint wp, int x, int y, int w, int mx, int my) {
		boolean hover = mx >= x && mx <= x + w && my >= y && my < y + 24;
		boolean isEditing = wp == editing;
		OriginUi.panel(g, x, y, w, 24, 8, hover || isEditing ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				hover || isEditing ? 0xFFFFFFFF : OriginTheme.BOX_BORDER);
		OriginUi.panel(g, x + 6, y + 7, 10, 10, 3, wp.color, 0x40FFFFFF);
		g.drawString(font, wp.name, x + 22, y + 3, wp.enabled ? 0xFFFFFFFF : 0xFF9A9A9A, true);
		String sub = wp.x + ", " + wp.y + ", " + wp.z + "  ·  " + dimDisplay(wp.dimension);
		g.drawString(font, sub, x + 22, y + 13, 0xFF9A9A9A, true);
		OriginUi.switchAt(g, "wp:" + wp.id, x + w - 74, y + 4, 30, wp.enabled, true);
		boolean dh = mx >= x + w - 22 && mx <= x + w - 6 && my >= y + 6 && my <= y + 20;
		g.drawString(font, "✕", x + w - 18, y + 8, dh ? 0xFFC77A73 : 0x99C77A73, false);
	}

	private void renderConfirm(GuiGraphics g, int mx, int my) {
		int cw = 240, ch = 80;
		int x = (width - cw) / 2, y = (height - ch) / 2;
		g.fill(0, 0, width, height, 0x88000000);
		OriginUi.panel(g, x, y, cw, ch, 10, 0xF01A1A1A, OriginTheme.STROKE_STRONG);
		g.drawString(font, "Delete \"" + pendingDelete.name + "\"?", x + 12, y + 14, 0xFFFFFFFF, true);
		button(g, x + 12, y + 46, 100, 20, "Delete", mx, my);
		button(g, x + cw - 112, y + 46, 100, 20, "Cancel", mx, my);
	}

	private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
		boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
		OriginUi.bevelPanel(g, x, y, w, h, 3, hover ? OriginTheme.BOX_FILL_HOVER : OriginTheme.BOX_FILL,
				hover ? 0xFFFFFFFF : OriginTheme.BOX_BORDER);
		g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
	}

	private void toggle(GuiGraphics g, int x, int y, String label, boolean on) {
		g.drawString(font, label, x, y + 2, 0xFFFFFFFF, true);
		OriginUi.switchAt(g, "cf:" + label, x + font.width(label) + 4, y - 1, 22, on, true);
	}

	// ---- input ----
	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseClicked(mx, my, button);
			syncPicker();
			persist();
			return true;
		}
		int x = px(), y = py(), w = pw();
		if (pendingDelete != null) {
			int cw = 240, ch = 80, dx = (width - cw) / 2, dy = (height - ch) / 2;
			if (in(mx, my, dx + 12, dy + 46, dx + 112, dy + 66)) {
				if (pendingDelete == editing) {
					editing = null;
				}
				Waypoints.remove(pendingDelete);
				pendingDelete = null;
			} else if (in(mx, my, dx + cw - 112, dy + 46, dx + cw - 12, dy + 66)) {
				pendingDelete = null;
			}
			return true;
		}
		int by = y + 36;
		if (in(mx, my, x + 12, by, x + 102, by + 18)) {          // Create / Done
			if (editing != null) {
				commitDone();
			} else {
				createDraft();
			}
			return true;
		}
		for (Tog t : globalToggles()) {
			if (in(mx, my, t.x0(), by, t.x1(), by + 18)) {
				Mods.set("waypoints", t.key(), !t.on());
				return true;
			}
		}
		if (editing != null && clickForm(mx, my, x, formY(), button)) {
			return true;
		}
		focus = -1;
		int top = listTop(), bottom = listBottom();
		if (my >= top && my <= bottom) {
			int ry = top - (int) Math.round(scroll);
			for (Row row : rows()) {
				if (my >= ry && my < ry + 24) {
					if (row.group() != null) {
						if (in(mx, my, x + 12, ry, x + w - 12, ry + 24)) {
							if (!COLLAPSED.remove(row.group())) {
								COLLAPSED.add(row.group());
							}
							return true;
						}
					} else {
						Waypoints.Waypoint wp = row.wp();
						boolean nested = wp.group != null && !wp.group.isEmpty();
						int rx = x + 12 + (nested ? 14 : 0), rw = w - 24 - (nested ? 14 : 0);
						if (in(mx, my, rx + rw - 74, ry + 4, rx + rw - 44, ry + 20)) {
							wp.enabled = !wp.enabled;
							Waypoints.save();
							return true;
						}
						if (in(mx, my, rx + rw - 22, ry + 6, rx + rw - 6, ry + 20)) {
							if (Mods.bool("waypoints", "confirmDelete")) {
								pendingDelete = wp;
							} else {
								if (wp == editing) {
									editing = null;
								}
								Waypoints.remove(wp);
							}
							return true;
						}
						if (in(mx, my, rx, ry, rx + rw, ry + 24)) {
							beginEdit(wp);   // row body → edit (live-save)
							return true;
						}
					}
				}
				ry += 28;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	private boolean clickForm(double mx, double my, int x, int y, int button) {
		if (in(mx, my, x + 56, y + ROW_NAME, x + 174, y + ROW_NAME + 14)) {
			focus = 0;
			return true;
		}
		if (in(mx, my, x + 222, y + ROW_NAME, x + 310, y + ROW_NAME + 14)) {
			focus = 1;
			return true;
		}
		if (in(mx, my, x + 44, y + ROW_POS, x + 100, y + ROW_POS + 14)) {
			focus = 2;
			return true;
		}
		if (in(mx, my, x + 104, y + ROW_POS, x + 160, y + ROW_POS + 14)) {
			focus = 3;
			return true;
		}
		if (in(mx, my, x + 164, y + ROW_POS, x + 220, y + ROW_POS + 14)) {
			focus = 4;
			return true;
		}
		if (in(mx, my, x + 226, y + ROW_POS, x + 310, y + ROW_POS + 14)) {   // Use Current
			xStr = String.valueOf(standX());
			yStr = String.valueOf(groundY());
			zStr = String.valueOf(standZ());
			applyBuffers();
			focus = -1;
			return true;
		}
		if (in(mx, my, x + 44, y + ROW_DIM, x + 174, y + ROW_DIM + 14)) {    // dimension cycle
			cycleDim(button == 1 ? -1 : 1);
			persist();
			return true;
		}
		// STYLE: the four colour swatches open the full shared picker
		if (in(mx, my, x + 56, y + ROW_COLOR, x + 86, y + ROW_COLOR + 14)) {
			openPicker(KEY_COLOR, editing.color, "Waypoint Color", x + 56, y + ROW_COLOR + 16);
			return true;
		}
		if (in(mx, my, x + 122, y + ROW_COLOR, x + 152, y + ROW_COLOR + 14)) {
			openPicker(KEY_ICON, editing.iconColor, "Icon Color", x + 122, y + ROW_COLOR + 16);
			return true;
		}
		if (in(mx, my, x + 188, y + ROW_COLOR, x + 218, y + ROW_COLOR + 14)) {
			openPicker(KEY_TEXT, editing.textColor, "Text Color", x + 188, y + ROW_COLOR + 16);
			return true;
		}
		if (in(mx, my, x + 246, y + ROW_COLOR, x + 276, y + ROW_COLOR + 14)) {
			openPicker(KEY_TEXTBG, editing.textBgColor, "Text Background", x + 246, y + ROW_COLOR + 16);
			return true;
		}
		// scale bars
		if (in(mx, my, x + 48, y + ROW_SCALE - 3, x + 112, y + ROW_SCALE + 12)) {
			dragSlider = 0;
			applyScale(mx);
			return true;
		}
		if (in(mx, my, x + 152, y + ROW_SCALE - 3, x + 216, y + ROW_SCALE + 12)) {
			dragSlider = 1;
			applyScale(mx);
			return true;
		}
		if (in(mx, my, x + 256, y + ROW_SCALE - 3, x + 320, y + ROW_SCALE + 12)) {
			dragSlider = 2;
			applyScale(mx);
			return true;
		}
		// DISPLAY toggles
		if (in(mx, my, x + 20, y + ROW_TOG, x + 74, y + ROW_TOG + 14)) {
			editing.showIcon = !editing.showIcon;
			persist();
			return true;
		}
		if (in(mx, my, x + 80, y + ROW_TOG, x + 134, y + ROW_TOG + 14)) {
			editing.showText = !editing.showText;
			persist();
			return true;
		}
		if (in(mx, my, x + 140, y + ROW_TOG, x + 198, y + ROW_TOG + 14)) {
			editing.showBeam = !editing.showBeam;
			persist();
			return true;
		}
		if (in(mx, my, x + 204, y + ROW_TOG, x + 280, y + ROW_TOG + 14)) {
			editing.showDistance = !editing.showDistance;
			persist();
			return true;
		}
		if (in(mx, my, x + 286, y + ROW_TOG, x + 364, y + ROW_TOG + 14)) {
			editing.highlightBlock = !editing.highlightBlock;
			persist();
			return true;
		}
		if (in(mx, my, x + 20, y + ROW_DONE, x + 150, y + ROW_DONE + 20)) {  // Done
			commitDone();
			return true;
		}
		focus = -1;
		return false;
	}

	private void openPicker(String key, int current, String label, int ax, int ay) {
		Mods.set("waypoints", key, current);
		OriginColorPicker.open("waypoints", key, label, ax, ay);
		focus = -1;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dxx, double dyy) {
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseDragged(mx, my, button);
			syncPicker();
			return true;
		}
		if (dragSlider >= 0 && editing != null) {
			applyScale(mx);
			return true;
		}
		return super.mouseDragged(mx, my, button, dxx, dyy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragSlider >= 0) {
			dragSlider = -1;
			persist();
		}
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseReleased();
			persist();
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	// Each scale bar is 64px wide (see renderForm), mapping to 0.5x .. 2.0x.
	private void applyScale(double mx) {
		int sliderX = px() + switch (dragSlider) {
			case 0 -> 48;
			case 1 -> 152;
			default -> 256;
		};
		double t = clamp01((mx - sliderX) / 64.0);
		double v = Math.round(scaleFromFrac(t) * 20) / 20.0;   // snap to 0.05
		switch (dragSlider) {
			case 0 -> editing.iconScale = v;
			case 1 -> editing.textScale = v;
			case 2 -> editing.distScale = v;
		}
	}

	// Scale slider mapping: the DEFAULT 1.0× sits at the MIDDLE of the bar, the
	// minimum is 0.25× (smaller than the old 0.5×), and the maximum is 2.0×.
	// Piecewise-linear about the centre so both halves are equal travel.
	static double scaleFromFrac(double f) {
		return f <= 0.5 ? 0.25 + f * 1.5 : 1.0 + (f - 0.5) * 2.0;
	}

	static double fracFromScale(double v) {
		return v <= 1.0 ? clamp01((v - 0.25) / 1.5) : clamp01(0.5 + (v - 1.0) * 0.5);
	}

	private static String dimDisplay(String d) {
		return switch (d) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> {
				String p = d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
				yield p.replace('_', ' ');
			}
		};
	}

	private void cycleDim(int dir) {
		int idx = -1;
		for (int i = 0; i < DIMS.length; i++) {
			if (DIMS[i].equals(editing.dimension)) {
				idx = i;
			}
		}
		editing.dimension = idx < 0 ? DIMS[0] : DIMS[(idx + dir + DIMS.length) % DIMS.length];
	}

	// ---- typing ----
	private String focused() {
		return switch (focus) {
			case 0 -> nameStr;
			case 1 -> groupStr;
			case 2 -> xStr;
			case 3 -> yStr;
			case 4 -> zStr;
			default -> null;
		};
	}

	private void setFocused(String v) {
		switch (focus) {
			case 0 -> nameStr = v;
			case 1 -> groupStr = v;
			case 2 -> xStr = v;
			case 3 -> yStr = v;
			case 4 -> zStr = v;
			default -> {
			}
		}
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (OriginColorPicker.isOpen() || focus < 0 || editing == null) {
			return super.charTyped(chr, modifiers);
		}
		String cur = focused();
		if (cur == null || cur.length() >= 32) {
			return true;
		}
		boolean coord = focus >= 2;
		if (coord) {
			if ((chr >= '0' && chr <= '9') || (chr == '-' && cur.isEmpty())) {
				setFocused(cur + chr);
				applyBuffers();
			}
		} else if (chr >= 32) {
			setFocused(cur + chr);
			applyBuffers();
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.keyPressed(keyCode);
			return true;
		}
		if (focus >= 0 && keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			String cur = focused();
			if (cur != null && !cur.isEmpty()) {
				setFocused(cur.substring(0, cur.length() - 1));
				applyBuffers();
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_TAB && focus >= 0) {
			focus = focus >= 4 ? 0 : focus + 1;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			// ESC: existing edits are already saved; an uncommitted create-draft is
			// DISCARDED (creating requires Done). Then exit.
			if (isNew) {
				editing = null;
				isNew = false;
			}
			Waypoints.save();
			Minecraft.getInstance().setScreen(null);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int content = rows().size() * 28;
		int maxScroll = Math.max(0, content - (listBottom() - listTop()));
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - sy * 28));
		return true;
	}

	private static boolean in(double mx, double my, double x0, double y0, double x1, double y1) {
		return mx >= x0 && mx < x1 && my >= y0 && my < y1;
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
