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

// Ion Jade widget skin (2026-09 redesign): every vanilla button, slider, checkbox
// and header tab is redrawn as Origin's premium GLASS control — a rounded,
// cool-tinted translucent surface with a hairline frame and an Inter (SDF)
// centred label. It reads as the exact same material as the mod-menu cards and
// HUD panels by construction (all of them draw through OriginUi.panel + the
// shared OriginTheme.BOX_* glass tokens). On hover the surface firms up, takes a
// faint accent wash + accent-tinted border, and lifts 1px — restrained, one
// element at a time, so the identity is felt without every button glowing.
//
// Restyling happens in place from the widget mixins (renderWidget cancelled):
// widgets keep their positions, actions and clicks; only the drawing changes.
// Fail-soft: if any Origin draw throws (e.g. a GUI API that changed shape),
// `broken` latches and every widget reverts to vanilla for the rest of the
// session instead of crashing -- the mixins only cancel vanilla when these
// entry points return true.
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

	// Per-widget hover easing state (buttons, sliders, checkboxes, tabs).
	private static final Map<Object, State> STATE = new WeakHashMap<>();

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	// Will, 2026-08-23: VANILLA MENUS KEEP VANILLA BUTTONS. The Frost widget skin
	// used to apply on every screen in the game; it now applies only inside
	// Origin's OWN screens -- the mod menu and its family (HUD editor, shader
	// browser, waypoints, item size). Title, pause, options, inventory and every
	// other vanilla screen render stock Minecraft widgets again.
	//
	// The test is the screen's class package, the same rule
	// OriginWidgetOwnership uses for widgets: com.origin.* is ours, anything else
	// is not. That also means another mod's screen keeps its own look for free.
	// Origin's title-screen BACKGROUND and wordmark are unaffected -- they are
	// drawn by OriginScreenRenderer, not by this widget skin.
	// Will, 2026-09-03: the Origin widget skin applies on EVERY screen again —
	// title, pause, options, world/server lists — so the whole client reads as
	// one design (the Lunar model). This lifts the 2026-08-23 "vanilla menus
	// keep vanilla buttons" rule on Will's instruction. Another mod's own widget
	// classes still keep their own art (OriginWidgetOwnership.isForeign).
	//
	// Will, 2026-10-19: Button Style = Minecraft hands every vanilla widget back to
	// vanilla -- except inside Origin's own windows (OriginStyle.vanillaButtonsOn).
	// This is the ONE gate AbstractButtonMixin, AbstractSliderButtonMixin,
	// CheckboxMixin, TabButtonMixin and SpriteIconButtonMixin all pass through.
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

	private record TitlePresentation(String icon, boolean iconOnly) {
	}

	public static void markPrimary(Object widget) {
		if (widget != null) {
			PRIMARY.put(widget, Boolean.TRUE);
		}
	}

	/** Gives a title-screen action its approved Origin symbol. Icon-only is used
	 * for the bottom utility dock; labelled actions keep a compact centred pair. */
	public static void setTitlePresentation(Object widget, String icon, boolean iconOnly) {
		if (widget != null && icon != null) {
			TITLE.put(widget, new TitlePresentation(icon, iconOnly));
		}
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
		// Vanilla screens get vanilla widgets (see originOwnsScreen).
		if (!originOwnsScreen()) {
			return false;
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
			if (cleanScreen()) {
				cleanButton(g, button, null, 0, 0);
				return true;
			}
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			TitlePresentation title = TITLE.get(button);
			if (title != null) {
				titleBox(g, button, enabled, hv);
				drawTitlePresentation(g, button, title, enabled, hv);
			} else {
				box(g, x, y, w, h, enabled, hv, PRIMARY.containsKey(button));
				drawLabelCentered(g, x + w / 2.0, y + h / 2.0, button.getMessage(),
						enabled ? OriginTheme.TEXT : OriginTheme.MUTED);
			}
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
			if (cleanScreen()) {
				cleanTab(g, key, x, y, w, h, label, selected, hovered);
				return true;
			}
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
		// Vanilla screens get vanilla widgets (see originOwnsScreen).
		if (!originOwnsScreen()) {
			return false;
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
			if (cleanScreen()) {
				cleanButton(g, button, sprite, spriteWidth, spriteHeight);
				return true;
			}
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			TitlePresentation title = TITLE.get(button);
			if (title != null) {
				titleBox(g, button, enabled, hv);
				drawTitlePresentation(g, button, title, enabled, hv);
				return true;
			}
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
			if (cleanScreen()) {
				cleanSlider(g, slider, value);
				return true;
			}
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
			if (cleanScreen()) {
				cleanCheckbox(g, checkbox);
				return true;
			}
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

	// ---- the clean in-game skin (Will's approved mockup, 2026-10-19) ----
	// Minecraft's OWN screens (title, pause, options, world/server lists,
	// loading, Create World ...) draw every control as OriginSurface: translucent
	// dark glass with a hairline, baked grain + soft top highlight, a soft blue
	// border on hover, and exactly one solid white PRIMARY per screen. Labels are
	// Inter semibold (MenuText), vanilla font only for text the atlas can't draw.
	// Origin's own screens (Right Shift menu, HUD editor, ...) keep the glass skin
	// below, untouched. Positions, sizes and actions are never changed here.

	/** True on a screen that takes the clean skin: a non-Origin screen with Origin buttons on. */
	private static boolean cleanScreen() {
		Minecraft mc = Minecraft.getInstance();
		return mc.screen != null && !OriginWidgetOwnership.isOriginOwnClass(mc.screen);
	}

	// The auto-picked primary of the screen being drawn (see beginFrame).
	private static Object framePrimary;

	// The one main action per screen, by its vanilla translation key, most
	// specific first. Only ever a plain Button whose label equals the key's text,
	// so another mod's button or an option row can never be picked.
	private static final String[] PRIMARY_KEYS = {
			"menu.returnToGame", "selectWorld.select", "selectServer.select", "gui.done", "selectWorld.create"
	};

	/**
	 * Called once per frame before a vanilla screen renders (MenuTextScopeMixin):
	 * finds that screen's primary button. Per frame, so widgets rebuilt on resize
	 * or by a mod are picked up with no cache to go stale; ~20 string compares.
	 */
	public static void beginFrame(net.minecraft.client.gui.screens.Screen screen) {
		framePrimary = null;
		try {
			for (String key : PRIMARY_KEYS) {
				String label = net.minecraft.client.resources.language.I18n.get(key);
				for (var child : screen.children()) {
					if (child instanceof net.minecraft.client.gui.components.Button b
							&& b.getClass() == net.minecraft.client.gui.components.Button.class
							&& b.visible && label.equals(b.getMessage().getString())) {
						framePrimary = b;
						return;
					}
				}
			}
		} catch (Throwable ignored) {
			framePrimary = null;
		}
	}

	private static boolean isPrimary(Object widget) {
		return widget == framePrimary || PRIMARY.containsKey(widget);
	}

	private static boolean keyboardFocus(net.minecraft.client.gui.components.AbstractWidget w) {
		return w.isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
	}

	/** Colours for one control state: {fill, border, text, detail*1000}. */
	private static int[] cleanColors(boolean enabled, boolean primary, double hv, boolean pressed) {
		if (!enabled) {
			return new int[]{OriginTheme.CLEAN_GLASS_OFF, OriginTheme.CLEAN_LINE_OFF, OriginTheme.CLEAN_TEXT_FAINT, 400};
		}
		if (primary) {
			int fill = pressed ? OriginTheme.CLEAN_PRIMARY_PRESS
					: OriginTheme.lerpColor(OriginTheme.CLEAN_PRIMARY, OriginTheme.CLEAN_PRIMARY_HI, hv);
			return new int[]{fill, 0, OriginTheme.CLEAN_PRIMARY_TEXT, 550};
		}
		int fill = pressed ? OriginTheme.CLEAN_GLASS_PRESS
				: OriginTheme.lerpColor(OriginTheme.CLEAN_GLASS, OriginTheme.CLEAN_GLASS_HI, hv);
		int border = OriginTheme.lerpColor(OriginTheme.CLEAN_LINE, OriginTheme.CLEAN_LINE_ACCENT, hv);
		return new int[]{fill, border, OriginTheme.CLEAN_TEXT, 1000};
	}

	private static boolean pressed(net.minecraft.client.gui.components.AbstractWidget w) {
		return w.active && w.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed();
	}

	/** Button / CycleButton / SpriteIconButton. sprite == null: not an icon button. */
	private static void cleanButton(GuiGraphics g, AbstractButton button, ResourceLocation sprite, int sw, int sh) {
		int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
		boolean enabled = button.active;
		double hv = devHover(button) ? 1.0 : hoverEase(button, enabled && button.isHovered());
		boolean primary = isPrimary(button);
		int[] c = cleanColors(enabled, primary, hv, pressed(button));
		OriginSurface.draw(g, x, y, w, h, c[0], c[1], c[3] / 1000f);
		if (keyboardFocus(button)) {
			OriginSurface.focusRing(g, x, y, w, h, OriginTheme.CLEAN_FOCUS);
		}
		TitlePresentation title = TITLE.get(button);
		if (title != null && title.iconOnly()) {
			int s = Math.max(8, Math.min(10, h - 6));
			ModIcons.draw(g, title.icon(), x + (w - s) / 2, y + (h - s) / 2, s, c[2]);
			return;
		}
		if (sprite != null) {
			// The widget's own text (empty on icon-only CenteredIcon) + Mojang's icon
			// sprite where vanilla puts it.
			button.renderString(g, Minecraft.getInstance().font, c[2]);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1f, 1f, 1f, enabled ? 1f : 0.5f);
			g.blitSprite(sprite, x + w / 2 - sw / 2, y + h / 2 - sh / 2, sw, sh);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			return;
		}
		String label = button.getMessage().getString();
		if (!MenuText.drawFitted(g, label, x + w / 2.0, y + h / 2.0, OriginTheme.CLEAN_EM_LABEL, w - 8, c[2])) {
			// Atlas can't draw it, or it's too long: vanilla's own clipped/scrolling label.
			button.renderString(g, Minecraft.getInstance().font, c[2]);
		}
	}

	/** Slider: the same glass, a faint value fill, a slim knob, the label over it. */
	private static void cleanSlider(GuiGraphics g, AbstractSliderButton slider, double value) {
		int x = slider.getX(), y = slider.getY(), w = slider.getWidth(), h = slider.getHeight();
		boolean enabled = slider.active;
		double v = Math.max(0.0, Math.min(1.0, value));
		double hv = hoverEase(slider, enabled && slider.isHovered());
		int[] c = cleanColors(enabled, false, hv, false);
		OriginSurface.draw(g, x, y, w, h, c[0], c[1], c[3] / 1000f);
		double inset = 3.0, knobW = 2.0;
		double travel = Math.max(0.0, w - 2 * inset - knobW);
		double kx = x + inset + travel * v;
		OriginSurface.fillPhysical(g, x + 1, y + 1, kx, y + h - 1, enabled ? 0x0FFFFFFF : 0x08FFFFFF);
		OriginSurface.fillPhysical(g, kx, y + 3, kx + knobW, y + h - 3,
				enabled ? OriginTheme.lerpColor(0xB3FFFFFF, 0xFFFFFFFF, hv) : 0x40FFFFFF);
		if (keyboardFocus(slider)) {
			OriginSurface.focusRing(g, x, y, w, h, OriginTheme.CLEAN_FOCUS);
		}
		String label = slider.getMessage().getString();
		if (!MenuText.drawFitted(g, label, x + w / 2.0, y + h / 2.0, OriginTheme.CLEAN_EM_LABEL, w - 8, c[2])) {
			MenuText.vanillaCentered(g, label, x + w / 2.0, y + h / 2.0, c[2]);
		}
	}

	/** Checkbox: a glass square; ticked = the white primary fill with a dark check. */
	private static void cleanCheckbox(GuiGraphics g, Checkbox checkbox) {
		int x = checkbox.getX(), y = checkbox.getY(), h = checkbox.getHeight();
		boolean enabled = checkbox.active;
		double hv = hoverEase(checkbox, enabled && checkbox.isHovered());
		boolean on = checkbox.selected();
		int[] c = cleanColors(enabled, on, hv, false);
		OriginSurface.draw(g, x, y, h, h, c[0], c[1], c[3] / 1000f);
		if (on) {
			int s = Math.max(6, h - 8);
			ModIcons.draw(g, "@check", x + (h - s) / 2, y + (h - s) / 2, s,
					enabled ? OriginTheme.CLEAN_PRIMARY_TEXT : OriginTheme.CLEAN_TEXT_FAINT);
		}
		if (keyboardFocus(checkbox)) {
			OriginSurface.focusRing(g, x, y, h, h, OriginTheme.CLEAN_FOCUS);
		}
		String label = checkbox.getMessage().getString();
		int textColor = enabled ? OriginTheme.CLEAN_TEXT : OriginTheme.CLEAN_TEXT_FAINT;
		if (MenuText.usable(label)) {
			MenuText.drawMid(g, label, x + h + 5, y + h / 2.0, OriginTheme.CLEAN_EM_LABEL, 0f, textColor);
		} else {
			OriginText.draw(g, Minecraft.getInstance().font, label, x + h + 4, y + (h - 8) / 2 + 1, textColor, true);
		}
	}

	/** Header tab (Create World): selected = lit glass with a white underline. */
	private static void cleanTab(GuiGraphics g, Object key, int x, int y, int w, int h,
								 Component label, boolean selected, boolean hovered) {
		double hv = hoverEase(key, hovered);
		double lit = selected ? 1.0 : hv * 0.6;
		int fill = OriginTheme.lerpColor(OriginTheme.CLEAN_GLASS, OriginTheme.CLEAN_GLASS_HI, lit);
		int border = selected ? OriginTheme.CLEAN_LINE_HI
				: OriginTheme.lerpColor(OriginTheme.CLEAN_LINE, OriginTheme.CLEAN_LINE_ACCENT, hv);
		OriginSurface.draw(g, x, y, w, h, fill, border, 1f);
		if (selected) {
			int uw = Math.max(12, Math.min(w - 8, (int) Math.round(w * 0.5)));
			OriginSurface.fillPhysical(g, x + (w - uw) / 2.0, y + h - 2, x + (w + uw) / 2.0, y + h - 1.5, 0xD9FFFFFF);
		}
		int color = selected ? OriginTheme.CLEAN_TEXT
				: OriginTheme.lerpColor(OriginTheme.CLEAN_TEXT_DIM, OriginTheme.CLEAN_TEXT, hv);
		String s = label.getString();
		if (!MenuText.drawFitted(g, s, x + w / 2.0, y + h / 2.0, OriginTheme.CLEAN_EM_LABEL, w - 6, color)) {
			MenuText.vanillaCentered(g, s, x + w / 2.0, y + h / 2.0, color);
		}
	}

	// Dev-shots only (OriginDevShots "clean" set): shows one button, found by its
	// label, in its hovered state so a still frame can prove the hover look.
	// Null in every real session.
	private static String devHoverLabel;

	public static void devForceHover(String label) {
		devHoverLabel = label;
	}

	private static boolean devHover(AbstractButton b) {
		return devHoverLabel != null && devHoverLabel.equals(b.getMessage().getString());
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

	/** Title controls are one opaque material at rest. Color appears only as
	 * direct hover/press feedback; there are no stacked washes or idle outlines. */
	private static void titleBox(GuiGraphics g, AbstractButton button, boolean enabled, double hover) {
		boolean pressed = enabled && button.isHovered()
				&& Minecraft.getInstance().mouseHandler.isLeftPressed();
		int fill = enabled ? OriginTheme.lerpColor(OriginTheme.PANEL, OriginTheme.PANEL_ALT, hover) : FILL_DISABLED;
		if (pressed) fill = OriginTheme.lerpColor(OriginTheme.PANEL_ALT, OriginTheme.ACCENT, 0.15);
		OriginUi.panel(g, button.getX(), button.getY(), button.getWidth(), button.getHeight(),
				RADIUS, fill, 0);
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

	private static void drawTitlePresentation(GuiGraphics g, AbstractButton button, TitlePresentation title,
										 boolean enabled, double hover) {
		int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
		int color = enabled ? OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hover) : OriginTheme.MUTED;
		// Twelve logical pixels gives the SDF enough screen area to retain its
		// hairline character while keeping every dock glyph centred on the same box.
		int iconSize = Math.max(10, Math.min(12, h - 5));
		if (title.iconOnly()) {
			ModIcons.draw(g, title.icon(), x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, color);
			return;
		}
		Font font = Minecraft.getInstance().font;
		String label = button.getMessage().getString();
		float textScale = 0.82f;
		int textW = Math.round(OriginText.widthBold(font, label) * textScale);
		int gap = 4;
		int groupW = iconSize + gap + textW;
		int iconX = x + (w - groupW) / 2;
		int iconY = y + (h - iconSize) / 2;
		ModIcons.draw(g, title.icon(), iconX, iconY, iconSize, color);
		int textX = iconX + iconSize + gap;
		int textY = y + (h - Math.round(8 * textScale)) / 2;
		g.pose().pushPose();
		g.pose().translate(textX, textY, 0);
		g.pose().scale(textScale, textScale, 1f);
		OriginText.drawBold(g, font, label, 0, 0, color, true);
		g.pose().popPose();
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
