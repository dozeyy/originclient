package com.origin.client.client.waypoints;

import com.mojang.blaze3d.platform.InputConstants;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginDeck;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.gui.OriginWindow;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.origin.client.client.gui.OriginDeck.*;

// The Waypoints manager + editor, on the shared Origin window frame (gui/OriginWindow,
// the redesigned mod menu's panel/header/widgets; Will 2026-10-12).
//
// SAVE MODEL (Will): editing an EXISTING waypoint auto-saves live — every
// keystroke/toggle/colour/slider change persists immediately. CREATING is a
// draft: "Create waypoint" opens the editor on a detached waypoint that is
// NOT in the list and NOT rendered in-world; it only becomes real when the
// player clicks Done. Esc (or closing, or Cancel) discards an uncommitted draft.
//
// Separators are "•": the MSDF Inter atlas has no "·" or "—" glyph.
//
// Layout (design px, see OriginWindow):
//   header   waypoints icon, "Waypoints", count | [Cancel] Create / Done  [x]
//   toolbar  the mod's global switches: Death waypoints, Locator bar, Separate bar
//   body     the list (full width), or — while editing — the list as a left column
//            and the editor card on the right: NAME/GROUP, LOCATION (X Y Z, Use
//            current, dimension), STYLE (four colours, three size sliders),
//            DISPLAY (icon / text / beam / distance / highlight).
//
// List: ungrouped waypoints at top level; each group is a collapsible header row
// with its waypoints nested under it. The list scrolls smoothly (the frame's
// eased scroll). Esc closes the whole screen (to the game), as it always has.
//
// PER-VERSION DELTA (1.21.11): input callbacks take event objects --
// mouseClicked(MouseButtonEvent, boolean doubled), mouseDragged(MouseButtonEvent,
// double, double), mouseReleased(MouseButtonEvent), keyPressed(KeyEvent),
// charTyped(CharacterEvent); x/y/button/keyCode are unpacked off the event at the
// top of each method so every body is the 1.21.1 logic. mouseScrolled still takes
// raw doubles. Level.getMinBuildHeight() is getMinY() since 1.21.2. The toolbar
// is its own row here, so this module's old "toggles drop below Create" fix is moot.
public class WaypointScreen extends OriginWindow {
	private static final String[] DIMS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
	// scratch mod-option keys the shared colour picker edits (one per colour target)
	private static final String KEY_COLOR = "editColor";
	private static final String KEY_ICON = "editIconColor";
	private static final String KEY_TEXT = "editTextColor";
	private static final String KEY_TEXTBG = "editTextBg";
	// Collapsed group names — static so the collapse state survives reopening the menu.
	private static final Set<String> COLLAPSED = new HashSet<>();

	// ---- layout (design px; 24px grid) ----
	private static final float TOOL_Y = BODY_Y;                 // global switches row, 32 tall
	private static final float LIST_Y = BODY_Y + 32 + 16;       // list / editor top
	private static final float LIST_BOTTOM = H - M;
	private static final float ROW_H = 44, ROW_GAP = 8, NEST = 16;
	private static final float LIST_W_EDITING = 312;            // list column while the editor is open
	private static final float ED_X = M + LIST_W_EDITING + 24;  // editor card
	private static final float ED_W = W - M - ED_X;
	private static final float PAD = 20;                        // editor card inner padding
	private static final float IN_X = ED_X + PAD, IN_W = ED_W - 2 * PAD;

	private Waypoints.Waypoint editing = null;   // non-null → the editor is open on this waypoint
	private boolean isNew = false;               // true → `editing` is an uncommitted draft
	// text buffers (typing "12" char-by-char or a lone "-" needs a buffer; every
	// parseable change is applied to `editing` immediately)
	private String nameStr = "", groupStr = "", xStr = "", yStr = "", zStr = "";
	// which scale bar is being dragged: -1 none, 0 icon, 1 text, 2 distance
	private int dragSlider = -1;
	private float dragX0, dragX1;
	// focused text field: 0 name, 1 group, 2 X, 3 Y, 4 Z, -1 none
	private int focus = -1;
	private Waypoints.Waypoint pendingDelete = null;
	private boolean openToCreate = false;

	// One row in the visible list: either a group header or a waypoint.
	private record Row(String group, Waypoints.Waypoint wp) {
	}

	public WaypointScreen() {
		this(false);
	}

	/** Opens the manager; if startCreating, opens the editor on a fresh DRAFT
	 *  (committed only when the player clicks Done) — the create keybind. */
	public WaypointScreen(boolean startCreating) {
		// returnTo null: closing always went straight back to the game.
		super(Component.literal("Waypoints"), null);
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

	// Ground snap for "Use current": the block the player is STANDING ON — i.e. the
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
		int min = level.getMinY();   // getMinBuildHeight() before 1.21.2
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
		closeEditor();
	}

	/** Leaves the editor; an uncommitted draft is simply dropped. */
	private void closeEditor() {
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.close();
		}
		editing = null;
		isNew = false;
		focus = -1;
		dragSlider = -1;
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

	// ---- the frame's slots ----

	@Override
	protected String heading() {
		return "Waypoints";
	}

	@Override
	protected String subheading() {
		if (editing != null && isNew) {
			return "New waypoint • Done adds it, Esc discards it";
		}
		if (editing != null) {
			String n = editing.name.length() > 24 ? editing.name.substring(0, 23) + "…" : editing.name;
			return "Editing " + n + " • changes save as you go";
		}
		int total = Waypoints.all().size();
		return total == 0 ? "No waypoints yet" : total + (total == 1 ? " waypoint" : " waypoints");
	}

	@Override
	protected String headingIcon() {
		return "waypoints";
	}

	@Override
	protected void drawHeaderActions(OriginDeck d, float right) {
		if (editing == null) {
			float w = buttonW(d, "Create waypoint", "@plus");
			button(d, "Create waypoint", "@plus", right - w, 16, w, 32, Style.PRIMARY, true, this::createDraft);
			return;
		}
		float w = buttonW(d, "Done", "@check");
		float x = right - w;
		button(d, "Done", "@check", x, 16, w, 32, Style.PRIMARY, true, this::commitDone);
		if (isNew) {
			float cw = buttonW(d, "Cancel", null);
			button(d, "Cancel", null, x - 8 - cw, 16, cw, 32, Style.SECONDARY, true, this::closeEditor);
		}
	}

	@Override
	protected void drawBody(OriginDeck d) {
		syncPicker();
		drawToolbar(d);
		drawList(d);
		if (editing != null) {
			try {
				drawEditor(d);
			} catch (RuntimeException e) {
				// fail soft: a bad waypoint value must never take the screen down
				closeEditor();
			}
		}
		if (pendingDelete != null) {
			drawConfirm(d);
		}
	}

	@Override
	protected void renderOverlays(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// The colour picker draws in plain GUI px, last, so it floats over everything.
		OriginColorPicker.render(g, mouseX, mouseY);
	}

	// ---- toolbar: the mod's global switches ----

	private void drawToolbar(OriginDeck d) {
		float x = M;
		x += switchChip(d, "Death waypoints", "deathWaypoints", x) + 8;
		x += switchChip(d, "Locator bar", "locatorBar", x) + 8;
		// Separate Bar only means something while the Locator Bar is on.
		if (Mods.bool("waypoints", "locatorBar")) {
			switchChip(d, "Separate bar", "separateBar", x);
		}
	}

	/** A caps label + switch in one 32-tall chip; the whole chip flips it. Returns its width. */
	private float switchChip(OriginDeck d, String label, String key, float x) {
		boolean on = Mods.bool("waypoints", key);
		String caps = label.toUpperCase(Locale.ROOT);
		float w = 12 + d.textW(caps, 11, true, CAPS_TRACK) + 12 + 36 + 8;
		boolean hov = d.over(x, TOOL_Y, w, 32);
		float hp = OriginUi.hover("wp-gt:" + key, hov);
		d.rect(x, TOOL_Y, w, 32, 6, lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
		d.textMid(caps, x + 12, TOOL_Y + 16, 11, true, CAPS_TRACK, lerp(DIM, TEXT, hp));
		d.toggle("wp-gt:" + key, x + w - 8 - 36, TOOL_Y + 6, on);
		d.hit(x, TOOL_Y, w, 32, () -> Mods.set("waypoints", key, !on));
		return w;
	}

	// ---- the list ----

	private float listW() {
		return editing != null ? LIST_W_EDITING : BODY_W;
	}

	private void drawList(OriginDeck d) {
		float lw = listW();
		List<Row> rows = rows();
		float contentH = rows.isEmpty() ? 0 : rows.size() * (ROW_H + ROW_GAP) - ROW_GAP;
		maxScroll = Math.max(0, contentH - (LIST_BOTTOM - LIST_Y));
		scrollTarget = Math.min(scrollTarget, maxScroll);
		scroll = Math.min(scroll, maxScroll);

		if (Waypoints.all().isEmpty()) {
			drawEmpty(d, lw);
			return;
		}
		d.clip(M - 4, LIST_Y, lw + 8, LIST_BOTTOM - LIST_Y);
		float ry = LIST_Y - (float) scroll;
		for (Row row : rows) {
			if (ry + ROW_H >= LIST_Y && ry <= LIST_BOTTOM) {
				if (row.group() != null) {
					drawGroupRow(d, row.group(), M, ry, lw);
				} else {
					boolean nested = row.wp().group != null && !row.wp().group.isEmpty();
					float indent = nested ? NEST : 0;
					drawRow(d, row.wp(), M + indent, ry, lw - indent);
				}
			}
			ry += ROW_H + ROW_GAP;
		}
		d.unclip();
		// thin scrollbar just right of the list column (the gutter before the editor)
		if (maxScroll > 0) {
			float sx = editing != null ? M + lw + 10 : W - 10;
			float track = LIST_BOTTOM - LIST_Y;
			float thumb = Math.max(28, track * track / (float) (track + maxScroll));
			float ty = LIST_Y + (track - thumb) * (float) (scroll / maxScroll);
			d.rect(sx, ty, 3, thumb, 1.5f, 0x38F1E9D2);
		}
	}

	private void drawEmpty(OriginDeck d, float lw) {
		float cx = M + lw / 2, cy = LIST_Y + 96;
		d.icon("waypoints", cx - 20, cy - 52, 40, MUTE);
		d.textCenter("No waypoints yet", cx, cy + 4, 15, true, 0, DIM);
		if (editing == null) {
			d.textCenter("Create one and it is placed where you are standing.", cx, cy + 30, 13, false, 0, MUTE);
		}
	}

	private void drawGroupRow(OriginDeck d, String group, float x, float y, float w) {
		boolean hov = d.over(x, y, w, ROW_H);
		float hp = OriginUi.hover("wp-group:" + group, hov);
		d.rect(x, y, w, ROW_H, 7, lerp(0x08FFFFFF, 0x12FFFFFF, hp), LINE);
		boolean open = !COLLAPSED.contains(group);
		d.icon(open ? "@chev-down" : "@chev-right", x + 14, y + ROW_H / 2 - 7, 14, lerp(DIM, TEXT, hp));
		int count = 0;
		for (Waypoints.Waypoint wp : Waypoints.all()) {
			if (group.equals(wp.group)) {
				count++;
			}
		}
		String n = count + (count == 1 ? " waypoint" : " waypoints");
		float nw = d.textW(n, 12, false, 0);
		d.textMid(d.fit(group, w - 40 - nw - 28, 13.5f, true, 0), x + 38, y + ROW_H / 2, 13.5f, true, 0, TEXT);
		d.textRight(n, x + w - 14, y + ROW_H / 2, 12, false, 0, MUTE);
		d.hit(x, y, w, ROW_H, () -> {
			if (!COLLAPSED.remove(group)) {
				COLLAPSED.add(group);
			}
		});
	}

	private void drawRow(OriginDeck d, Waypoints.Waypoint wp, float x, float y, float w) {
		boolean isEditing = wp == editing;
		boolean hov = d.over(x, y, w, ROW_H);
		float hp = isEditing ? 1f : OriginUi.hover("wp-row:" + wp.id, hov);
		d.rect(x, y, w, ROW_H, 7, lerp(CARD, OriginDeck.CARD_H, hp),
				isEditing ? mul(ACC, 0.55f) : lerp(LINE, 0x29F1E9D2, hp));
		d.hit(x, y, w, ROW_H, () -> beginEdit(wp));   // row body → edit (live-save)

		d.rect(x + 14, y + ROW_H / 2 - 6, 12, 12, 3, wp.color | 0xFF000000, 0x40FFFFFF);
		float textMax = w - 38 - 12 - 36 - 12 - 28 - 8;
		d.textMid(d.fit(wp.name, textMax, 13.5f, true, 0), x + 38, y + 16, 13.5f, true, 0, wp.enabled ? TEXT : DIM);
		String sub = wp.x + ", " + wp.y + ", " + wp.z + "  •  " + dimDisplay(wp.dimension);
		d.textMid(d.fit(sub, textMax, 11.5f, false, 0), x + 38, y + 31, 11.5f, false, 0, MUTE);

		// trash (far right), then the on/off switch to its left
		float tx = x + w - 12 - 20;
		boolean th = d.over(tx - 4, y + 8, 28, 28);
		d.icon("@trash", tx + 2, y + ROW_H / 2 - 8, 16, th ? RED : mul(RED, 0.55f));
		d.hit(tx - 4, y + 8, 28, 28, () -> deleteRequested(wp));
		float sx = tx - 12 - 36;
		d.toggle("wp:" + wp.id, sx, y + ROW_H / 2 - 10, wp.enabled);
		d.hit(sx - 4, y + 6, 44, 32, () -> {
			wp.enabled = !wp.enabled;
			Waypoints.save();
		});
	}

	private void deleteRequested(Waypoints.Waypoint wp) {
		if (Mods.bool("waypoints", "confirmDelete")) {
			pendingDelete = wp;
		} else {
			deleteNow(wp);
		}
	}

	private void deleteNow(Waypoints.Waypoint wp) {
		if (wp == editing) {
			closeEditor();
		}
		Waypoints.remove(wp);
	}

	// ---- the editor card ----

	private void drawEditor(OriginDeck d) {
		float top = LIST_Y;
		d.rect(ED_X, top, ED_W, LIST_BOTTOM - top, 8, CARD, LINE);
		// swallow clicks on the card's empty space (they'd otherwise fall through to nothing)
		d.hit(ED_X, top, ED_W, LIST_BOTTOM - top, () -> {
		});

		// NAME + GROUP
		float nameW = Math.round(IN_W * 0.6f), groupX = IN_X + nameW + 12, groupW = IN_X + IN_W - groupX;
		label(d, "Name", IN_X, top + 28);
		label(d, "Group", groupX, top + 28);
		textField(d, 0, IN_X, top + 36, nameW, nameStr, "Waypoint name", null);
		textField(d, 1, groupX, top + 36, groupW, groupStr, "No group", null);

		// LOCATION: X Y Z + Use current, then the dimension
		label(d, "Location", IN_X, top + 100);
		float fy = top + 108, cw = 96;
		textField(d, 2, IN_X, fy, cw, xStr, "", "X");
		textField(d, 3, IN_X + cw + 8, fy, cw, yStr, "", "Y");
		textField(d, 4, IN_X + 2 * (cw + 8), fy, cw, zStr, "", "Z");
		float ux = IN_X + 3 * (cw + 8) + 4;
		button(d, "Use current", "@user", ux, fy, IN_X + IN_W - ux, 32, Style.SECONDARY, true, () -> {
			xStr = String.valueOf(standX());
			yStr = String.valueOf(groundY());
			zStr = String.valueOf(standZ());
			applyBuffers();
			focus = -1;
		});
		dimensionPicker(d, IN_X, top + 148);

		// STYLE: a colour per part (beam/highlight, icon, text, label background),
		// then a size slider per part (icon, text, distance).
		label(d, "Style", IN_X, top + 212);
		float sx = IN_X, sy = top + 220;
		sx += swatch(d, "Waypoint", sx, sy, editing.color, KEY_COLOR, "Waypoint Color") + 8;
		sx += swatch(d, "Icon", sx, sy, editing.iconColor, KEY_ICON, "Icon Color") + 8;
		sx += swatch(d, "Text", sx, sy, editing.textColor, KEY_TEXT, "Text Color") + 8;
		swatch(d, "Label background", sx, sy, editing.textBgColor, KEY_TEXTBG, "Text Background");

		float colW = (IN_W - 2 * 24) / 3f, ly = top + 274;
		scaleSlider(d, 0, "Icon size", IN_X, ly, colW, editing.iconScale);
		scaleSlider(d, 1, "Text size", IN_X + colW + 24, ly, colW, editing.textScale);
		scaleSlider(d, 2, "Distance size", IN_X + 2 * (colW + 24), ly, colW, editing.distScale);

		// DISPLAY
		label(d, "Display", IN_X, top + 340);
		float cx = IN_X, cy = top + 348;
		Waypoints.Waypoint w = editing;
		cx += flagChip(d, "Icon", cx, cy, w.showIcon, () -> w.showIcon = !w.showIcon) + 8;
		cx += flagChip(d, "Text", cx, cy, w.showText, () -> w.showText = !w.showText) + 8;
		cx += flagChip(d, "Beam", cx, cy, w.showBeam, () -> w.showBeam = !w.showBeam) + 8;
		cx += flagChip(d, "Distance", cx, cy, w.showDistance, () -> w.showDistance = !w.showDistance) + 8;
		flagChip(d, "Highlight", cx, cy, w.highlightBlock, () -> w.highlightBlock = !w.highlightBlock);
	}

	/** A text field bound to focus slot `slot`; `axis` (X/Y/Z) is shown at its right edge. */
	private void textField(OriginDeck d, int slot, float x, float y, float w, String value, String placeholder,
			String axis) {
		field(d, x, y, w, 32, value, placeholder, focus == slot, null);
		if (axis != null) {
			d.textRight(axis, x + w - 12, y + 16, 11, true, 0, MUTE);
		}
		d.hit(x, y, w, 32, () -> focus = slot);
	}

	/** Overworld / Nether / End as one segmented control (plus the waypoint's own
	 *  dimension when it's a modded one, shown selected). */
	private void dimensionPicker(OriginDeck d, float x, float y) {
		List<String> dims = new ArrayList<>(List.of(DIMS));
		if (!dims.contains(editing.dimension)) {
			dims.add(editing.dimension);
		}
		float total = 0;
		float[] ws = new float[dims.size()];
		for (int i = 0; i < dims.size(); i++) {
			ws[i] = 28 + d.textW(dimDisplay(dims.get(i)).toUpperCase(Locale.ROOT), 11, true, CAPS_TRACK);
			total += ws[i];
		}
		d.rect(x, y, total + 8, 32, 6, 0x0AFFFFFF, LINE);
		float sx = x + 4;
		for (int i = 0; i < dims.size(); i++) {
			String dim = dims.get(i);
			boolean on = dim.equals(editing.dimension);
			boolean hov = d.over(sx, y + 4, ws[i], 24);
			if (on) {
				d.rect(sx, y + 4, ws[i], 24, 5, 0x1AFFFFFF);
			} else if (hov) {
				d.rect(sx, y + 4, ws[i], 24, 5, 0x0AFFFFFF);
			}
			d.textCenter(dimDisplay(dim).toUpperCase(Locale.ROOT), sx + ws[i] / 2, y + 16, 11, true, CAPS_TRACK,
					on ? TEXT : hov ? DIM : MUTE);
			d.hit(sx, y + 4, ws[i], 24, () -> {
				editing.dimension = dim;
				persist();
			});
			sx += ws[i];
		}
	}

	/** A colour swatch + its name in one chip; click opens the shared picker. Returns its width. */
	private float swatch(OriginDeck d, String label, float x, float y, int color, String key, String pickerTitle) {
		float w = 8 + 20 + 10 + d.textW(label, 13, false, 0) + 12;
		boolean hov = d.over(x, y, w, 32);
		boolean open = ("waypoints:" + key).equals(OriginColorPicker.openKey());
		d.rect(x, y, w, 32, 6, open || hov ? 0x14FFFFFF : 0x0AFFFFFF, open ? 0x40FFFFFF : 0);
		d.rect(x + 8, y + 6, 20, 20, 4, color | 0xFF000000, LINE2);
		d.textMid(label, x + 38, y + 16, 13, false, 0, hov || open ? TEXT : DIM);
		d.hit(x, y, w, 32, () -> {
			if (open) {
				OriginColorPicker.close();
			} else {
				Mods.set("waypoints", key, color);
				OriginColorPicker.open("waypoints", key, pickerTitle, d.guiX(x), d.guiY(y + 36));
			}
			focus = -1;
		});
		return w;
	}

	/** One size slider column: name + value on top, the mock's slider under it. */
	private void scaleSlider(OriginDeck d, int idx, String label, float x, float y, float w, double v) {
		d.textMid(label, x, y + 8, 13, false, 0, DIM);
		// 2 decimals when the 0.05 steps need it (e.g. 0.25x), else 1.
		String val = Math.round(v * 100) % 10 == 0 ? String.format(Locale.ROOT, "%.1fx", v)
				: String.format(Locale.ROOT, "%.2fx", v);
		d.textRight(val, x + w, y + 8, 13, false, 0, TEXT);
		float tx = x + 7, tw = w - 14;
		d.slider(tx, y + 30, tw, (float) fracFromScale(v), dragSlider == idx);
		d.hit(x, y + 18, w, 24, () -> {
			dragSlider = idx;
			dragX0 = tx;
			dragX1 = tx + tw;
			focus = -1;
		});
	}

	/** An on/off chip in the filter-chip style (accent when on). Returns its width. */
	private float flagChip(OriginDeck d, String label, float x, float y, boolean on, Runnable flip) {
		String caps = label.toUpperCase(Locale.ROOT);
		float w = 24 + d.textW(caps, 11, true, CAPS_TRACK);
		boolean hov = d.over(x, y, w, 32);
		float hp = OriginUi.hover("wp-flag:" + label, hov);
		d.rect(x, y, w, 32, 6, on ? lerp(ACC_SOFT, withAlpha(ACC, 0x38), hp) : lerp(0x0AFFFFFF, 0x14FFFFFF, hp));
		d.textMid(caps, x + 12, y + 16, 11, true, CAPS_TRACK, on ? ACC : lerp(MUTE, DIM, hp));
		d.hit(x, y, w, 32, () -> {
			flip.run();
			persist();
		});
		return w;
	}

	// ---- delete confirmation (the mod menu's dialog) ----

	private void drawConfirm(OriginDeck d) {
		d.hit(-10000, -10000, 20000, 20000, () -> pendingDelete = null);
		d.rect(0, 0, W, H, 8, 0x8C000000);
		float w = 340, h = 148, x = (W - w) / 2, y = (H - h) / 2;
		d.rect(x, y, w, h, 8, 0xFA181D1A, LINE2);
		d.hit(x, y, w, h, () -> {
		});
		d.textMid("Delete this waypoint?", x + 20, y + 30, 15, true, 0, TEXT);
		d.textMid(d.fit("\"" + pendingDelete.name + "\" will be removed for good.", w - 40, 13, false, 0),
				x + 20, y + 56, 13, false, 0, DIM);
		float bw = (w - 40 - 12) / 2, by = y + h - 20 - 34;
		button(d, "Cancel", null, x + 20, by, bw, 34, Style.SECONDARY, true, () -> pendingDelete = null);
		button(d, "Delete", null, x + 20 + bw + 12, by, bw, 34, Style.DANGER, true, () -> {
			Waypoints.Waypoint wp = pendingDelete;
			pendingDelete = null;
			if (wp != null) {
				deleteNow(wp);
			}
		});
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseClicked(mx, my, button);
			syncPicker();
			persist();
			return true;
		}
		if (button == 0) {
			focus = -1;   // a click on a field's hit focuses it again
		}
		boolean used = super.mouseClicked(event, doubled);
		if (dragSlider >= 0 && editing != null) {   // a press on a track jumps the thumb there
			applyScale(mx);
		}
		return used;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dxx, double dyy) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseDragged(mx, my, button);
			syncPicker();
			return true;
		}
		if (dragSlider >= 0 && editing != null) {
			applyScale(mx);
			return true;
		}
		return super.mouseDragged(event, dxx, dyy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragSlider >= 0) {
			dragSlider = -1;
			persist();
		}
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.mouseReleased();
			persist();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (OriginColorPicker.isOpen() || pendingDelete != null) {
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	// Each scale bar maps its track (recorded when it was pressed) to 0.25x .. 2.0x.
	private void applyScale(double guiMx) {
		if (dragX1 <= dragX0) {
			return;
		}
		double t = clamp01((dX(guiMx) - dragX0) / (double) (dragX1 - dragX0));
		double v = Math.round(scaleFromFrac(t) * 20) / 20.0;   // snap to 0.05
		switch (dragSlider) {
			case 0 -> editing.iconScale = v;
			case 1 -> editing.textScale = v;
			case 2 -> editing.distScale = v;
			default -> {
			}
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
	public boolean charTyped(CharacterEvent event) {
		char chr = (char) event.codepoint();
		if (OriginColorPicker.isOpen() || focus < 0 || editing == null) {
			return super.charTyped(event);
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
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		if (OriginColorPicker.isOpen()) {
			OriginColorPicker.keyPressed(keyCode);
			return true;
		}
		if (focus >= 0 && keyCode == InputConstants.KEY_BACKSPACE) {
			String cur = focused();
			if (cur != null && !cur.isEmpty()) {
				setFocused(cur.substring(0, cur.length() - 1));
				applyBuffers();
			}
			return true;
		}
		if (keyCode == InputConstants.KEY_TAB && focus >= 0) {
			focus = focus >= 4 ? 0 : focus + 1;
			return true;
		}
		if (keyCode == InputConstants.KEY_ESCAPE && pendingDelete != null) {
			pendingDelete = null;   // Esc first dismisses the delete dialog
			return true;
		}
		return super.keyPressed(event);   // Esc → back()
	}

	/** Esc / back chevron: an uncommitted create-draft is DISCARDED (creating
	 *  requires Done), existing edits are already saved; then back to the game. */
	@Override
	protected void back() {
		if (isNew) {
			closeEditor();
		}
		Waypoints.save();
		beginClose();
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
