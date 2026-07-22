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

// Frost-style widget skin (Will, 2026-07-21): every vanilla button, slider,
// checkbox and header tab is redrawn as a FLAT, SQUARE, translucent-dark
// rectangle with a hairline border and a white centered label -- the look from
// the Frost client screenshot Will referenced. This deliberately reverses the
// "TRUE VANILLA buttons" decision from the 2026-07-15 redesign for 1.21.1 only,
// on Will's direct instruction.
//
// Everything draws through OriginUi.panel -- the one square fill+border choke
// point the rest of the Origin UI already uses -- so buttons stay visually
// identical to the mod menu / HUD surfaces by construction (no rounded corners,
// no glow, no textures). The Origin blurred-panorama background is untouched.
//
// Restyling happens in place from the widget mixins (renderWidget cancelled):
// widgets keep their positions, actions and clicks; only the drawing changes.
// Fail-soft: if any Origin draw throws (e.g. a GUI API that changed shape),
// `broken` latches and every widget reverts to vanilla for the rest of the
// session instead of crashing -- the mixins only cancel vanilla when these
// entry points return true.
public final class OriginButtonRenderer {

	// Frost palette. FILL_NORMAL is the theme's existing translucent-panel token
	// (rgba(16,16,16,0.55)) -- the "same opacity" Will asked to keep. Hover
	// lightens and firms up the fill so a hovered control reads as lit; the
	// border also brightens (house rule: hover feedback on everything, but no
	// vertical lift -- Will).
	// Fill (the "center"): LIGHT/see-through (Will 2026-07-21 — "more clear, not
	// more opaque"), so the panorama shows through the box like glass. Hover
	// fills it in a bit for feedback.
	private static final int FILL_NORMAL = 0x59161616;
	private static final int FILL_HOVER = 0x99303030;
	private static final int FILL_DISABLED = 0x40101010;
	// Outline: a near-black frame kept DARKER THAN THE CENTER (Will 2026-07-21,
	// "even darker outline") so the edge always reads darker than the see-through
	// fill; hover lifts it slightly but it stays the dark frame.
	private static final int BORDER_NORMAL = 0xF00A0A0A;
	private static final int BORDER_HOVER = 0xFF1A1A1A;
	private static final int BORDER_DISABLED = 0x99080808;
	private static final int LABEL_COLOR = OriginTheme.TEXT;
	private static final int LABEL_DISABLED = 0xFFA0A0A0;
	// A near-white accent for slider handles / checkbox ticks, matching Frost's
	// bright controls; brightens a touch on hover.
	private static final int HANDLE = 0xFFB4B4B4;
	private static final int HANDLE_HOVER = 0xFFE0E0E0;
	// Short + eased = a snappy, tactile hover.
	private static final double HOVER_MS = 90.0;

	// Fail-soft master switch: latches on the first draw failure and never
	// resets for the session, so a broken GUI API can't spam-crash.
	private static volatile boolean broken = false;

	// Per-widget hover easing state (buttons, sliders, checkboxes, tabs).
	private static final Map<Object, State> STATE = new WeakHashMap<>();

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin widget rendering failed; falling back to vanilla widgets for this session", t);
		return false;
	}

	// ---- buttons (Button, CycleButton, ... -- anything AbstractButton) ----

	/** Frost-styled button. Returns true only if it drew (callers cancel vanilla on true). */
	public static boolean render(GuiGraphics g, AbstractButton button) {
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
			box(g, x, y, w, h, enabled, hv);
			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, button.getMessage(),
					enabled ? LABEL_COLOR : LABEL_DISABLED);
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
		if (broken) {
			return false;
		}
		try {
			double hv = hoverEase(key, hovered);
			// Selected pins the hover look; unselected eases with the cursor.
			double lit = selected ? 1.0 : hv;
			int fill = OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, lit);
			int border = OriginTheme.lerpColor(BORDER_NORMAL, OriginTheme.STROKE_HOVER, lit);
			OriginUi.bevelPanel(g, x, y, w, h, 3, fill, border);
			if (selected) {
				int uw = Math.max(16, Math.min(w - 8, (int) Math.round(w * 0.55)));
				int ux = x + (w - uw) / 2;
				g.fill(ux, y + h - 2, ux + uw, y + h - 1, OriginTheme.TEXT);
			}
			int labelColor = selected ? LABEL_COLOR
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
			button.renderString(g, Minecraft.getInstance().font, enabled ? LABEL_COLOR : LABEL_DISABLED);

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
					enabled ? OriginTheme.lerpColor(HANDLE, HANDLE_HOVER, hv) : 0x66808080);

			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, slider.getMessage(),
					enabled ? LABEL_COLOR : LABEL_DISABLED);
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
						enabled ? OriginTheme.lerpColor(HANDLE, HANDLE_HOVER, hv) : 0x669A9A9A);
			}
			Font font = Minecraft.getInstance().font;
			g.drawString(font, checkbox.getMessage(), x + box + 5, y + (box - 8) / 2 + 1,
					enabled ? LABEL_COLOR : LABEL_DISABLED, true);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- shared drawing ----

	/** The Frost box: a flat translucent-dark fill + hairline border, eased
	 *  between resting and hover. Corners are a small 3px angled CUT (bevel), not
	 *  square and not round (Will, 2026-07-21) — via OriginUi.bevelPanel. Hover
	 *  brightens the border to bright white. */
	private static void box(GuiGraphics g, int x, int y, int w, int h, boolean enabled, double hv) {
		int fill = enabled ? OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(BORDER_NORMAL, OriginTheme.STROKE_HOVER, hv) : BORDER_DISABLED;
		OriginUi.bevelPanel(g, x, y, w, h, 3, fill, border);
	}

	/** Centered label in the default Minecraft font, WITH the standard drop
	 *  shadow (Will's choice, to match Frost exactly). Raw label text is kept
	 *  as-is -- no dot-stripping -- so "Options..." reads like vanilla/Frost. */
	private static void drawLabelCentered(GuiGraphics g, double cx, double cy, Component message, int color) {
		Font font = Minecraft.getInstance().font;
		int tw = font.width(message);
		g.drawString(font, message, (int) (cx - tw / 2.0), (int) (cy - 4), color, true);
	}

	/** Shared eased hover progress (0..1) for any widget, on wall-clock time. */
	private static double hoverEase(Object widget, boolean hovered) {
		State st = STATE.computeIfAbsent(widget, k -> new State());
		long now = System.nanoTime();
		double dtMs = st.lastNanos == 0 ? 0 : (now - st.lastNanos) / 1_000_000.0;
		st.lastNanos = now;
		double target = hovered ? 1.0 : 0.0;
		if (st.hover < target) {
			st.hover = Math.min(target, st.hover + dtMs / HOVER_MS);
		} else if (st.hover > target) {
			st.hover = Math.max(target, st.hover - dtMs / HOVER_MS);
		}
		return OriginTheme.easeOut(st.hover);
	}
}
