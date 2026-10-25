package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.WeakHashMap;

import com.origin.client.client.theme.OriginTheme;

// Two skins, one entry point per widget kind (the widget mixins call render /
// renderTab / renderIconButton / renderSlider / renderCheckbox and cancel vanilla
// only when they return true):
//
//  1. ORIGIN'S OWN WINDOWS (the Mod Menu family: OriginModMenuScreen, OriginDeck,
//     the HUD editor, cosmetics, ...; any com.origin.* screen -- originOwnsScreen()).
//     Every vanilla-class widget there is redrawn as the Ion Jade glass control:
//     OriginUi.panel + the OriginTheme.BOX_* tokens, eased hover, accent wash.
//     This path is PROTECTED (overhaul rule S4: the Mod Menu must not change by a
//     pixel) -- do not touch box(), the BOX_* tokens or RADIUS for a title change.
//
//  2. THE TITLE SCREEN (Phase 4, Workbench). Minecraft's other menus keep stock
//     widgets (Will, 2026-10-19), but the title's controls are registered by
//     TitleScreenMixin (setTitleButton / setTitleDock) and draw through
//     titleControl(): a soft rounded panel (radius 4 GUI px, OriginTheme.TITLE_*
//     tokens) for the worded buttons, and a bare glyph in its hue with a hover
//     plate for the dock -- never a box inside a box. A widget that was not
//     registered is untouched vanilla.
//
// Restyling happens in place from the widget mixins: widgets keep their
// positions, actions and clicks; only the drawing changes.
//
// Fail-soft, two latches: `broken` (skin 1) and `titleBroken` (skin 2) flip the
// affected widgets back to vanilla for the rest of the session when an Origin
// draw throws (e.g. a GUI API that changed shape). They are separate so a title
// draw failure can never alter the Mod Menu, and the reverse.
public final class OriginButtonRenderer {

	// Glass palette — one material with the rest of the Origin UI. Fill, border, label
	// and handle colours are read live from the OriginTheme tokens at every draw (never
	// copied into a static final here): an equipped theme repaints them at runtime, and a
	// copy taken at class load would keep the default green hover forever. Hover firms
	// fill and border and adds a faint accent wash (see box()).
	private static final int FILL_DISABLED = 0x40171C19;
	private static final int BORDER_DISABLED = 0x99080B09;
	// Corner radius for every widget — soft premium glass (matches RADIUS_SM cards).
	private static final int RADIUS = OriginTheme.RADIUS_SM;
	// Short + eased = a snappy, tactile hover.
	private static final double HOVER_IN_MS = OriginTheme.HOVER_IN_MS;
	private static final double HOVER_OUT_MS = OriginTheme.HOVER_OUT_MS;

	// Fail-soft master switch: latches on the first draw failure and never
	// resets for the session, so a broken GUI API can't spam-crash.
	private static volatile boolean broken = false;
	// The title skin's own latch (see the header): a failed title draw hands only
	// the title's widgets back to vanilla.
	private static volatile boolean titleBroken = false;

	// Per-widget hover easing state (buttons, sliders, checkboxes, tabs).
	private static final Map<Object, State> STATE = new WeakHashMap<>();

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	// Will, 2026-10-19 (final): Minecraft's own menus (pause, options, world and
	// server lists, inventory...) draw stock widgets, always. The glass skin (skin 1)
	// applies only inside Origin's OWN windows: the test is the screen's class package,
	// the same rule OriginWidgetOwnership uses for widgets (com.origin.* is ours,
	// anything else is not), so another mod's screen keeps its own look for free.
	// The title screen is NOT an Origin-owned screen: it answers false here, and its
	// controls take skin 2 only through the TITLE registry. This is the ONE gate
	// AbstractButtonMixin, AbstractSliderButtonMixin, CheckboxMixin, TabButtonMixin and
	// SpriteIconButtonMixin all pass through (OriginStyle.vanillaButtonsOn). It must keep
	// returning true for every Origin window (the Mod Menu, OriginDeck and every screen
	// they draw) and false for the title -- the menu_* dev-shot compare against
	// p0-baseline proves the first half pixel for pixel.
	private static boolean originOwnsScreen() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.screen != null && !com.origin.client.client.OriginStyle.vanillaButtonsOn(mc.screen);
	}

	// PRIMARY buttons (the one main action on a screen, e.g. Singleplayer on the
	// title) draw with the accent: an accent wash fill + accent border at rest,
	// firming to full accent on hover. Registered by the screen that owns the
	// widget; weak so a rebuilt screen's widgets are collected normally.
	private static final Map<Object, Boolean> PRIMARY = new WeakHashMap<>();
	private static final Map<Object, TitlePresentation> TITLE = new WeakHashMap<>();

	/** icon: an atlas id (null = none); iconOnly: a dock glyph (no panel) vs a worded panel button; hue: the dock glyph's colour. */
	private record TitlePresentation(String icon, boolean iconOnly, int hue) {
	}

	public static void markPrimary(Object widget) {
		if (widget != null) {
			PRIMARY.put(widget, Boolean.TRUE);
		}
	}

	/** A worded title button (Singleplayer, Options, Mods...): a soft panel with the label
	 *  centred and an optional 8 GUI px leading glyph (icon null = label only). */
	public static void setTitleButton(Object widget, String icon) {
		if (widget != null) {
			TITLE.put(widget, new TitlePresentation(icon, false, 0));
		}
	}

	/** A title dock action: no panel, just the glyph in its hue (OriginTheme.HUE_*),
	 *  with a plate behind it only while hovered. */
	public static void setTitleDock(Object widget, String icon, int hue) {
		if (widget != null && icon != null) {
			TITLE.put(widget, new TitlePresentation(icon, true, hue));
		}
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin widget rendering failed; falling back to vanilla widgets for this session", t);
		return false;
	}

	// ---- skin 2: the title screen's controls (Phase 4, Workbench) ----
	// Spec: docs/overhaul/DESIGN-SYSTEM.md 15.2. Worded buttons are a soft panel
	// (radius 4 GUI px, 1 GUI px edge) with the label centred and an optional 8 GUI px
	// leading glyph; the dock is bare glyphs (12 GUI px, in their hue, 85 % at rest)
	// with a faint plate that eases in on hover. Hover/press use the shared 48 / 64 ms
	// ease; geometry never moves under the pointer. One SDF quad per panel (the
	// edge is folded into the fill colour, see flatten), so this adds no draw calls
	// over the vanilla sprite it replaces.
	private static final int GLYPH = 12, LEAD_ICON = 8, LEAD_GAP = 6;

	/** Draws a registered title control; false = not ours (or latched): vanilla draws it. */
	private static boolean titleControl(GuiGraphics g, AbstractButton button) {
		TitlePresentation tp = TITLE.get(button);
		if (tp == null || titleBroken || com.origin.client.client.OriginStyle.vanillaTitle()) {
			return false;
		}
		try {
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			boolean pressed = enabled && button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed();
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			if (tp.iconOnly()) {
				drawDock(g, tp, x, y, w, h, enabled, hv, pressed);
			} else {
				drawPanelButton(g, button, tp, x, y, w, h, enabled, hv, pressed);
			}
			if (enabled && button.isFocused()) {
				// Keyboard focus: a 1 GUI px accentLine ring on the control's own box, never colour alone.
				OriginUi.roundRect(g, x, y, w, h, OriginTheme.TITLE_RADIUS, 0, OriginTheme.TITLE_FOCUS, 1f);
			}
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error(
					"Origin title controls failed; falling back to vanilla widgets on the title for this session", t);
			return false;
		}
	}

	private static void drawPanelButton(GuiGraphics g, AbstractButton button, TitlePresentation tp,
										int x, int y, int w, int h, boolean enabled, double hv, boolean pressed) {
		int fill, edge;
		if (!enabled) {
			fill = OriginTheme.withAlpha(OriginTheme.TITLE_BTN_FILL, 0x7D);   // 60 % of the rest fill
			edge = OriginTheme.TITLE_BTN_EDGE;
		} else if (pressed) {
			fill = OriginTheme.TITLE_BTN_FILL_PRESS;
			edge = OriginTheme.TITLE_BTN_EDGE_HOVER;
		} else {
			fill = OriginTheme.lerpColor(OriginTheme.TITLE_BTN_FILL, OriginTheme.TITLE_BTN_FILL_HOVER, hv);
			edge = OriginTheme.lerpColor(OriginTheme.TITLE_BTN_EDGE, OriginTheme.TITLE_BTN_EDGE_HOVER, hv);
		}
		OriginUi.roundRect(g, x, y, w, h, OriginTheme.TITLE_RADIUS, fill, flatten(fill, edge), 1f);

		Font font = Minecraft.getInstance().font;
		String label = button.getMessage().getString();
		int textW = font.width(label);
		int textColor = enabled ? OriginTheme.TEXT : OriginTheme.TITLE_TEXT_MUTED;
		boolean lead = tp.icon() != null;
		int groupW = lead ? LEAD_ICON + LEAD_GAP + textW : textW;
		int gx = x + (w - groupW) / 2;
		if (lead) {
			int iconColor = enabled ? OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hv) : OriginTheme.TITLE_TEXT_MUTED;
			ModIcons.drawFlat(g, tp.icon(), gx, y + (h - LEAD_ICON) / 2, LEAD_ICON, iconColor);
			gx += LEAD_ICON + LEAD_GAP;
		}
		g.drawString(font, label, gx, y + (h - 8) / 2, textColor, true);
	}

	private static void drawDock(GuiGraphics g, TitlePresentation tp, int x, int y, int w, int h,
								 boolean enabled, double hv, boolean pressed) {
		if (enabled && (hv > 0.01 || pressed)) {
			int plate = pressed ? 0x24FFFFFF : OriginTheme.withAlpha(OriginTheme.TITLE_PLATE_HOVER,
					(int) Math.round(((OriginTheme.TITLE_PLATE_HOVER >>> 24) & 0xFF) * hv));
			OriginUi.roundRect(g, x, y, w, h, OriginTheme.TITLE_RADIUS, plate, 0, 1f);
		}
		int color = enabled
				? OriginTheme.withAlpha(tp.hue(), (int) Math.round(0xD9 + (0xFF - 0xD9) * hv))   // 85 % -> 100 %
				: OriginTheme.TITLE_TEXT_MUTED;
		ModIcons.drawFlat(g, tp.icon(), x + (w - GLYPH) / 2, y + (h - GLYPH) / 2, GLYPH, color);
	}

	/** The edge colour a 1 px ring needs so it can replace (not overlay) the fill in the SDF
	 *  quad: `edge` composited over `fill`, so one draw reads as a translucent panel with a
	 *  hairline instead of a hole ringed by a near-invisible line. */
	private static int flatten(int fill, int edge) {
		double ea = ((edge >>> 24) & 0xFF) / 255.0, fa = ((fill >>> 24) & 0xFF) / 255.0;
		double oa = ea + fa * (1.0 - ea);
		if (oa <= 0.0) {
			return 0;
		}
		int out = (int) Math.round(oa * 255.0) << 24;
		for (int shift = 16; shift >= 0; shift -= 8) {
			double ec = ((edge >> shift) & 0xFF) / 255.0, fc = ((fill >> shift) & 0xFF) / 255.0;
			out |= (int) Math.round(Math.min(1.0, (ec * ea + fc * fa * (1.0 - ea)) / oa) * 255.0) << shift;
		}
		return out;
	}

	// ---- buttons (Button, CycleButton, ... -- anything AbstractButton) ----

	/** Frost-styled button. Returns true only if it drew (callers cancel vanilla on true). */
	public static boolean render(GuiGraphics g, AbstractButton button) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen) -- the title's
		// icon row gets vanilla buttons with its icon on top.
		if (!originOwnsScreen()) {
			return titleControl(g, button);
		}
		// Another mod's own widget class: its art is drawn inside the method
		// our mixins cancel, so restyling it leaves an empty Origin box. Return
		// false -> the mixin does not cancel -> the mod draws itself, untouched.
		if (OriginWidgetOwnership.isForeign(button)) {
			return false;
		}

		// The difficulty padlock draws a compact lock ICON, not text -- our label
		// restyle would print its full "Lock Difficulty" message oversized. Leave
		// it vanilla so the icon shows.
		if (button instanceof net.minecraft.client.gui.components.LockIconButton) {
			return false;
		}
		if (broken) {
			return false;
		}
		try {
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			box(g, x, y, w, h, enabled, hv, PRIMARY.containsKey(button));
			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, button.getMessage(),
					enabled ? OriginTheme.TEXT : OriginTheme.MUTED);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- header tabs (Game / World / More on Create World) ----

	/** Frost-styled tab. Selected reads as a lit box with a bright bottom accent;
	 *  unselected is the resting box with a muted label and brightens on hover. */
	public static boolean renderTab(GuiGraphics g, Object key, int x, int y, int w, int h,
									Component label, boolean selected, boolean hovered) {
		// Vanilla screens (Create World's header tabs) keep vanilla tabs.
		if (!originOwnsScreen()) {
			return false;
		}
		if (broken) {
			return false;
		}
		try {
			double hv = hoverEase(key, hovered);
			// Selected pins the hover look; unselected eases with the cursor.
			double lit = selected ? 1.0 : hv;
			int fill = OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, lit);
			int border = OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.STROKE_HOVER, lit);
			OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
			if (selected) {
				// Accent underline: the accent's own value ramp across the tab,
				// one jade tone with a soft highlight. Drawn as a few segments so the
				// hue drifts along its width without a shader.
				int uw = Math.max(16, Math.min(w - 8, (int) Math.round(w * 0.55)));
				int ux = x + (w - uw) / 2;
				int seg = 8;
				for (int i = 0; i < seg; i++) {
					int sx = ux + (int) Math.round(uw * (i / (double) seg));
					int ex = ux + (int) Math.round(uw * ((i + 1) / (double) seg));
					g.fill(sx, y + h - 2, ex, y + h - 1, OriginTheme.aurora(i / (double) (seg - 1)));
				}
			}
			int labelColor = selected ? OriginTheme.TEXT
					: OriginTheme.lerpColor(OriginTheme.MUTED, OriginTheme.TEXT, hv);
			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, label, labelColor);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- icon buttons (Accessibility = person, Language = globe, ...) ----

	/** Frost-styled sprite-icon button. These (SpriteIconButton.CenteredIcon /
	 *  TextAndIcon) override renderWidget, so the plain AbstractButton skin never
	 *  reaches them -- they'd otherwise keep the vanilla stone sprite. We draw the
	 *  same Frost box, delegate to the widget's own renderString for any text
	 *  (empty on the icon-only title buttons, the scrolling label on TextAndIcon),
	 *  then blit the icon centered exactly where vanilla puts it. `sprite` /
	 *  `spriteWidth` / `spriteHeight` are the widget's protected fields, passed in
	 *  from the mixin. */
	public static boolean renderIconButton(GuiGraphics g, SpriteIconButton button,
										   ResourceLocation sprite, int spriteWidth, int spriteHeight) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen) -- the title's icon row gets vanilla buttons with its icon on top.
		if (!originOwnsScreen()) {
			return titleControl(g, button);
		}
		// Another mod's own widget class: its art is drawn inside the method
		// our mixins cancel, so restyling it leaves an empty Origin box. Return
		// false -> the mixin does not cancel -> the mod draws itself, untouched.
		if (OriginWidgetOwnership.isForeign(button)) {
			return false;
		}

		if (broken) {
			return false;
		}
		try {
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			box(g, x, y, w, h, enabled, hv);

			// Per-subclass text: CenteredIcon.renderString is empty (icon only);
			// TextAndIcon.renderString draws its scrolling label at the vanilla
			// position. Calling it keeps both correct without re-implementing layout.
			button.renderString(g, Minecraft.getInstance().font, enabled ? OriginTheme.TEXT : OriginTheme.MUTED);

			// Icon centered, matching vanilla SpriteIconButton placement.
			int ix = x + w / 2 - spriteWidth / 2;
			int iy = y + h / 2 - spriteHeight / 2;
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			g.blitSprite(sprite, ix, iy, spriteWidth, spriteHeight);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- sliders (FOV, volumes, sensitivity, ...) ----

	/** Frost-styled slider: the same box as a button plus a bright handle bar at
	 *  the value position. Vanilla's drag/click logic is untouched -- `value` is
	 *  read live each frame, so dragging stays exactly as responsive as vanilla. */
	public static boolean renderSlider(GuiGraphics g, AbstractSliderButton slider, double value) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen).
		if (!originOwnsScreen()) {
			return false;
		}
		// Another mod's own widget class: its art is drawn inside the method
		// our mixins cancel, so restyling it leaves an empty Origin box. Return
		// false -> the mixin does not cancel -> the mod draws itself, untouched.
		if (OriginWidgetOwnership.isForeign(slider)) {
			return false;
		}

		if (broken) {
			return false;
		}
		try {
			int x = slider.getX(), y = slider.getY(), w = slider.getWidth(), h = slider.getHeight();
			boolean enabled = slider.active;
			double v = Math.max(0.0, Math.min(1.0, value));
			double hv = hoverEase(slider, enabled && slider.isHovered());
			box(g, x, y, w, h, enabled, hv);

			// Draggable handle: a thin vertical bar at the value position, inset so
			// it stays inside the box at both ends.
			int inset = 6, handleW = 4;
			int handleH = Math.max(6, h - 8);
			int handleY = y + (h - handleH) / 2;
			int travel = Math.max(0, w - 2 * inset - handleW);
			int handleX = x + inset + (int) Math.round(travel * v);
			g.fill(handleX, handleY, handleX + handleW, handleY + handleH,
					enabled ? OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hv) : 0x66808080);

			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, slider.getMessage(),
					enabled ? OriginTheme.TEXT : OriginTheme.MUTED);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- checkboxes ----

	/** Frost-styled checkbox: a square box (matching the buttons) with a bright
	 *  inner square when selected, and the label to the right. Toggle logic
	 *  untouched. */
	public static boolean renderCheckbox(GuiGraphics g, Checkbox checkbox) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen).
		if (!originOwnsScreen()) {
			return false;
		}
		// Another mod's own widget class: its art is drawn inside the method
		// our mixins cancel, so restyling it leaves an empty Origin box. Return
		// false -> the mixin does not cancel -> the mod draws itself, untouched.
		if (OriginWidgetOwnership.isForeign(checkbox)) {
			return false;
		}

		if (broken) {
			return false;
		}
		try {
			int x = checkbox.getX(), y = checkbox.getY(), h = checkbox.getHeight();
			int box = h;
			boolean enabled = checkbox.active;
			double hv = hoverEase(checkbox, enabled && checkbox.isHovered());
			box(g, x, y, box, box, enabled, hv);
			if (checkbox.selected()) {
				int inset = Math.max(3, box / 5);
				g.fill(x + inset, y + inset, x + box - inset, y + box - inset,
						enabled ? OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hv) : 0x669A9A9A);
			}
			Font font = Minecraft.getInstance().font;
			OriginText.draw(g, font, checkbox.getMessage().getString(), x + box + 5, y + (box - 8) / 2 + 1,
					enabled ? OriginTheme.TEXT : OriginTheme.MUTED, true);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- shared drawing ----

	/** The glass box: a rounded cool-tinted translucent fill + hairline frame,
	 *  eased between resting and hover. On hover the fill firms up AND takes a
	 *  faint accent wash (ACCENT_SOFT), and the border eases toward the
	 *  accent-tinted BOX_BORDER_HOVER — so a hovered control reads "accent-lit"
	 *  without any control ever glowing at rest. Rounded via OriginUi.panel. */
	private static void box(GuiGraphics g, int x, int y, int w, int h, boolean enabled, double hv) {
		box(g, x, y, w, h, enabled, hv, false);
	}

	private static void box(GuiGraphics g, int x, int y, int w, int h, boolean enabled, double hv, boolean primary) {
		if (primary && enabled) {
			// Accent-washed glass: the one accent moment on a screen.
			int fill = OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hv);
			int border = OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hv);
			OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
			int wash = OriginTheme.withAlpha(OriginTheme.ACCENT, (int) Math.round(0x2E + 0x1C * hv));
			OriginUi.panel(g, x, y, w, h, RADIUS, wash, 0);
			return;
		}
		int fill = enabled ? OriginTheme.lerpColor(OriginTheme.BOX_FILL, OriginTheme.BOX_FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(OriginTheme.BOX_BORDER, OriginTheme.BOX_BORDER_HOVER, hv) : BORDER_DISABLED;
		OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
		// Accent wash: a translucent accent fill that fades in with hover only.
		if (enabled && hv > 0.01) {
			int wash = OriginTheme.withAlpha(OriginTheme.ACCENT, (int) Math.round(0x24 * hv));
			OriginUi.panel(g, x, y, w, h, RADIUS, wash, 0);
		}
	}

	/** Centered label in Origin's Inter (SDF) font — semibold, with a soft drop
	 *  shadow — routed through OriginText so it matches every other menu label and
	 *  the whole client reads in one typeface (no vanilla pixel glyphs on widgets).
	 *  Raw label text is kept as-is (no dot-stripping) so "Options..." reads right. */
	private static void drawLabelCentered(GuiGraphics g, double cx, double cy, Component message, int color) {
		Font font = Minecraft.getInstance().font;
		String s = message.getString();
		int tw = OriginText.widthBold(font, s);
		OriginText.drawBold(g, font, s, (int) (cx - tw / 2.0), (int) (cy - 4), color, true);
	}

	/** Shared eased hover progress (0..1) for any widget, on wall-clock time. */
	private static double hoverEase(Object widget, boolean hovered) {
		long now = System.nanoTime();
		State st = STATE.get(widget);
		if (st == null) {
			st = new State();
			st.hover = hovered ? 1.0 : 0.0;
			st.lastNanos = now;
			STATE.put(widget, st);
			return OriginTheme.easeOut(st.hover);
		}
		double dtMs = Math.min(50.0, (now - st.lastNanos) / 1_000_000.0);
		st.lastNanos = now;
		double target = hovered ? 1.0 : 0.0;
		double duration = hovered ? HOVER_IN_MS : HOVER_OUT_MS;
		if (st.hover < target) {
			st.hover = Math.min(target, st.hover + dtMs / duration);
		} else if (st.hover > target) {
			st.hover = Math.max(target, st.hover - dtMs / duration);
		}
		return OriginTheme.easeOut(st.hover);
	}
}
