package com.origin.client.client.gui;

import com.origin.client.client.theme.OriginTheme;
import com.origin.client.client.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiLockIconButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

// Ion Jade widget skin (2026-09 redesign): every vanilla button, slider and text
// field is redrawn as Origin's premium GLASS control -- a rounded, cool-tinted
// translucent surface with a hairline frame and a centred label. It reads as the
// same material as the mod-menu cards and HUD panels by construction (all of
// them draw through OriginUi.panel + the shared OriginTheme.BOX_* glass tokens).
// On hover the surface firms up, takes a faint accent wash + accent-tinted
// border -- restrained, one element at a time.
//
// Restyling happens in place from the widget mixins (mixin/menus: GuiButton /
// Forge GuiButtonExt drawButton HEAD-cancel, GuiTextField drawTextBox): widgets
// keep their positions, actions and clicks; only the drawing changes. Fail-soft:
// if any Origin draw throws, `broken` latches and every widget reverts to
// vanilla for the rest of the session -- the mixins only cancel vanilla when
// these entry points return true.
//
// 1.8.9: every button is a GuiButton (no AbstractButton / AbstractSliderButton /
// Checkbox / ImageButton / tab classes), and a SLIDER is a GuiButton whose
// mouseDragged(mc, mx, my) both applies the drag AND draws the knob from
// widgets.png, called from inside drawButton. The takeover keeps that call (so
// dragging behaves exactly as vanilla) and, for sliders whose value Origin can
// read, runs it with colour writes masked so only the Origin handle shows
// (beginVanillaButton / finishVanillaButton).
// No checkbox / image-button / tab widgets exist in this era's vanilla screens,
// so 1.16.5's renderCheckbox / renderImageButton / renderTab are not ported.
public final class OriginButtonRenderer {

	// Glass palette -- one material with the rest of the Origin UI.
	private static final int FILL_NORMAL = OriginTheme.BOX_FILL;
	private static final int FILL_HOVER = OriginTheme.BOX_FILL_HOVER;
	private static final int FILL_DISABLED = 0x40171C19;
	private static final int BORDER_NORMAL = OriginTheme.BOX_BORDER;
	private static final int BORDER_HOVER = OriginTheme.BOX_BORDER_HOVER;
	private static final int BORDER_DISABLED = 0x99080B09;
	private static final int LABEL_COLOR = OriginTheme.TEXT;
	private static final int LABEL_DISABLED = OriginTheme.MUTED;
	private static final int TITLE_FILL = OriginTheme.PANEL;
	private static final int TITLE_FILL_HOVER = OriginTheme.PANEL_ALT;
	private static final int TITLE_FILL_PRESSED = 0xFF294333;
	// Slider handles read as the accent -- the one place a control's "value"
	// carries the brand hue; brightens toward full accent on hover.
	private static final int HANDLE = OriginTheme.ACCENT_BORDER;
	private static final int HANDLE_HOVER = OriginTheme.ACCENT;
	private static final int RADIUS = OriginTheme.RADIUS_SM;
	private static final double HOVER_IN_MS = OriginTheme.HOVER_IN_MS;
	private static final double HOVER_OUT_MS = OriginTheme.HOVER_OUT_MS;

	// Fail-soft master switch: latches on the first draw failure for the session.
	private static volatile boolean broken = false;

	// Per-widget hover easing state.
	private static final Map<Object, State> STATE = new WeakHashMap<>();

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	// Will, 2026-09-03: the Origin widget skin applies on EVERY screen -- title,
	// pause, options, world/server lists -- so the whole client reads as one
	// design (the Lunar model). Another mod's own widget classes still keep their
	// own art (OriginWidgetOwnership.isForeign), see skins().
	private static boolean originOwnsScreen() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc != null && mc.currentScreen != null;
	}

	// PRIMARY buttons (the one main action on a screen) draw with the accent.
	private static final Map<Object, Boolean> PRIMARY = new WeakHashMap<>();
	private static final Map<Object, TitlePresentation> TITLE = new WeakHashMap<>();

	// Java 8 has no records: a final value class.
	private static final class TitlePresentation {
		private final String icon;
		private final boolean iconOnly;

		private TitlePresentation(String icon, boolean iconOnly) {
			this.icon = icon;
			this.iconOnly = iconOnly;
		}

		public String icon() {
			return icon;
		}

		public boolean iconOnly() {
			return iconOnly;
		}
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

	/** Whether the Origin skin draws this widget. 1.8.9: OptiFine is on every
	 *  player's game and its Video Settings / shader screens are built from its own
	 *  GuiButton subclasses (GuiOptionButtonOF, GuiOptionSliderOF, ...). Those are
	 *  plain buttons with no art of their own, so they are skinned like vanilla's
	 *  even though OriginWidgetOwnership calls any non-Minecraft class foreign. */
	public static boolean skins(Object widget) {
		if (widget == null || broken || !originOwnsScreen()) {
			return false;
		}
		// The difficulty padlock draws a lock ICON from its own drawButton; it never
		// reaches GuiButton.drawButton, but say so explicitly for any caller.
		if (widget instanceof GuiLockIconButton) {
			return false;
		}
		return !OriginWidgetOwnership.isForeign(widget) || isOptiFineWidget(widget.getClass());
	}

	private static final Map<Class<?>, Boolean> OPTIFINE = new ConcurrentHashMap<>();

	private static boolean isOptiFineWidget(Class<?> type) {
		Boolean known = OPTIFINE.get(type);
		if (known == null) {
			String name = type.getName();
			// net.optifine.* (HD_U_M builds), shadersmod.* (older shader GUIs), or
			// OptiFine's default-package Gui* classes (older 1.8.9 builds).
			known = name.startsWith("net.optifine.") || name.startsWith("shadersmod.")
					|| (name.indexOf('.') < 0 && name.startsWith("Gui"));
			OPTIFINE.put(type, known);
		}
		return known;
	}

	// ---- the drawButton takeover (called from the widget mixins) ----
	// Two phases around the button's own mouseDragged(mc, mx, my), which the
	// mixin calls in between (it is protected, and Mixin 0.7 bodies stay free of
	// lambdas / inner classes): vanilla's order is box -> mouseDragged -> label.

	/** beginVanillaButton: not Origin's to draw -- leave vanilla alone. */
	public static final int SKIP = 0;
	/** A slider Origin can read: run mouseDragged with colour writes masked
	 *  (it applies the drag AND draws the vanilla knob), then finish draws the
	 *  Origin control at the fresh value -- dragging stays exactly vanilla. */
	public static final int SLIDER = 1;
	/** Box drawn: run mouseDragged unmasked (a no-op on plain buttons; a slider
	 *  Origin cannot read draws its own knob here), then finish draws the label. */
	public static final int BUTTON = 2;

	/** Phase one. The mixin has already set the button's `hovered` field the way
	 *  vanilla does. Returns SKIP, SLIDER or BUTTON. */
	public static int beginVanillaButton(GuiButton button) {
		if (!button.visible || !skins(button)) {
			return SKIP;
		}
		try {
			if (!Double.isNaN(sliderValue(button))) {
				return SLIDER;
			}
			renderBox(new Gfx(), button);
			return BUTTON;
		} catch (Throwable t) {
			fail(t);
			return SKIP;
		}
	}

	/** Phase two, after mouseDragged. True when Origin drew (the mixin then
	 *  cancels vanilla's drawButton). */
	public static boolean finishVanillaButton(GuiButton button, int mode) {
		try {
			if (mode == SLIDER) {
				return renderSlider(new Gfx(), button, sliderValue(button));
			}
			if (mode == BUTTON && !broken) {
				renderLabel(new Gfx(), button);
				return true;
			}
			return false;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	/** Frost-styled button (box + label). Returns true only if it drew. */
	public static boolean render(Gfx g, GuiButton button) {
		if (!skins(button)) {
			return false;
		}
		try {
			if (!renderBox(g, button)) {
				return false;
			}
			renderLabel(g, button);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static boolean renderBox(Gfx g, GuiButton button) {
		int x = button.xPosition, y = button.yPosition, w = button.width, h = button.height;
		boolean enabled = button.enabled;
		double hv = hoverEase(button, enabled && button.isMouseOver());
		if (TITLE.get(button) != null) {
			titleBox(g, button, enabled, hv);
		} else {
			box(g, x, y, w, h, enabled, hv, PRIMARY.containsKey(button));
		}
		return true;
	}

	private static void renderLabel(Gfx g, GuiButton button) {
		boolean enabled = button.enabled;
		TitlePresentation title = TITLE.get(button);
		if (title != null) {
			drawTitlePresentation(g, button, title, enabled, hoverEase(button, enabled && button.isMouseOver()));
			return;
		}
		drawLabelCentered(g, button, button.xPosition + button.width / 2.0, button.yPosition + button.height / 2.0,
				labelColor(button));
	}

	/** Vanilla's label colour rule, in the Origin palette: an explicit
	 *  packedFGColour (some screens tint a button's text) wins as vanilla does. */
	private static int labelColor(GuiButton button) {
		if (button.packedFGColour != 0) {
			return 0xFF000000 | button.packedFGColour;
		}
		return button.enabled ? LABEL_COLOR : LABEL_DISABLED;
	}

	// ---- sliders (FOV, volumes, sensitivity, OptiFine's sliders, Forge's) ----

	/** Frost-styled slider: the same box as a button plus an accent handle bar at
	 *  the value position (0..1). Vanilla's drag logic is untouched (see
	 *  beginVanillaButton). */
	public static boolean renderSlider(Gfx g, GuiButton slider, double value) {
		if (!skins(slider)) {
			return false;
		}
		try {
			int x = slider.xPosition, y = slider.yPosition, w = slider.width, h = slider.height;
			boolean enabled = slider.enabled;
			double v = Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(1.0, value));
			double hv = hoverEase(slider, enabled && slider.isMouseOver());
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

			drawLabelCentered(g, slider, x + w / 2.0, y + h / 2.0, labelColor(slider));
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// A slider's 0..1 value, or NaN when the button is not a slider Origin can
	// read. One field per class, resolved once:
	//  - vanilla GuiOptionSlider.sliderValue (SRG field_146134_p, verified)
	//  - Forge's GuiSlider.sliderValue (public; Forge names are never obfuscated)
	//  - OptiFine's sliders (GuiOptionSliderOF, shader option sliders) keep the
	//    vanilla field name "sliderValue" in their own unobfuscated classes
	//  - the Sounds screen's per-category slider (GuiScreenOptionsSounds$Button):
	//    MCP stable_22 leaves its value unnamed, so the dev name IS the SRG name
	//    (field_146156_o) -- 1.8.9 NAME UNVERIFIED
	//  - vanilla GuiSlider (Customize World): "sliderPosition" -- 1.8.9 NAME
	//    UNVERIFIED, SRG unknown -> in production it keeps its vanilla knob
	// Anything else returns NaN and keeps its own knob (drawn unmasked).
	private static final Map<Class<?>, Field> SLIDER_FIELD = new ConcurrentHashMap<>();
	private static final Field NO_FIELD;

	static {
		Field none = null;
		try {
			none = OriginButtonRenderer.class.getDeclaredField("broken");
		} catch (Throwable ignored) {
			// cannot happen: the field is declared above
		}
		NO_FIELD = none;
	}

	public static double sliderValue(GuiButton button) {
		try {
			if (button instanceof net.minecraftforge.fml.client.config.GuiSlider) {
				// Forge keeps the 0..1 position in sliderValue (getValue() maps it onto min..max).
				return ((net.minecraftforge.fml.client.config.GuiSlider) button).sliderValue;
			}
			Class<?> type = button.getClass();
			Field f = SLIDER_FIELD.get(type);
			if (f == null) {
				f = findSliderField(type);
				SLIDER_FIELD.put(type, f == null ? NO_FIELD : f);
			}
			if (f == null || f == NO_FIELD) {
				return Double.NaN;
			}
			Object v = f.get(button);
			return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
		} catch (Throwable t) {
			return Double.NaN;
		}
	}

	private static Field findSliderField(Class<?> type) {
		if (GuiOptionSlider.class.isAssignableFrom(type)) {
			Field f = Reflect.field(GuiOptionSlider.class, "sliderValue", "field_146134_p");
			if (f != null) {
				return f;
			}
		}
		if (!GuiButton.class.isAssignableFrom(type)) {
			return null;
		}
		// Own (and inherited non-vanilla) fields of the button's class chain,
		// stopping at GuiButton: a float/double named like a slider value.
		for (Class<?> c = type; c != null && c != GuiButton.class; c = c.getSuperclass()) {
			for (String name : new String[]{"sliderValue", "field_146156_o", "sliderPosition"}) {
				try {
					Field f = c.getDeclaredField(name);
					if (f.getType() == float.class || f.getType() == double.class) {
						f.setAccessible(true);
						return f;
					}
				} catch (NoSuchFieldException ignored) {
					// next candidate
				} catch (Throwable t) {
					return null;
				}
			}
		}
		return null;
	}

	// ---- text fields (world name / seed, server address, chat-less fields) ----

	/** 1.8.9 addition: the Origin field well behind a GuiTextField whose
	 *  background drawing is on (vanilla's grey frame + black well is skipped by
	 *  GuiTextFieldMixin). The rect is vanilla's outer frame: x-1, y-1, w+2, h+2. */
	public static boolean renderTextField(int x, int y, int w, int h, boolean focused, boolean enabled) {
		if (broken || !originOwnsScreen()) {
			return false;
		}
		try {
			Gfx g = new Gfx();
			int fill = enabled ? 0x8C0B0F0D : FILL_DISABLED;
			int border = focused ? OriginTheme.ACCENT_BORDER : (enabled ? BORDER_NORMAL : BORDER_DISABLED);
			OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- shared drawing ----

	private static void box(Gfx g, int x, int y, int w, int h, boolean enabled, double hv) {
		box(g, x, y, w, h, enabled, hv, false);
	}

	private static void box(Gfx g, int x, int y, int w, int h, boolean enabled, double hv, boolean primary) {
		if (primary && enabled) {
			// Accent-washed glass: the one accent moment on a screen.
			int fill = OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv);
			int border = OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hv);
			OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
			int wash = OriginTheme.withAlpha(OriginTheme.ACCENT, (int) Math.round(0x2E + 0x1C * hv));
			OriginUi.panel(g, x, y, w, h, RADIUS, wash, 0);
			return;
		}
		int fill = enabled ? OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv) : BORDER_DISABLED;
		OriginUi.panel(g, x, y, w, h, RADIUS, fill, border);
		// Accent wash: a translucent accent fill that fades in with hover only.
		if (enabled && hv > 0.01) {
			int wash = OriginTheme.withAlpha(OriginTheme.ACCENT, (int) Math.round(0x24 * hv));
			OriginUi.panel(g, x, y, w, h, RADIUS, wash, 0);
		}
	}

	/** Title controls are one opaque material at rest. Colour appears only as
	 * direct hover/press feedback. 1.8.9: "pressed" is LWJGL's live left button. */
	private static void titleBox(Gfx g, GuiButton button, boolean enabled, double hover) {
		boolean pressed = enabled && button.isMouseOver() && Mouse.isButtonDown(0);
		int fill = enabled ? OriginTheme.lerpColor(TITLE_FILL, TITLE_FILL_HOVER, hover) : FILL_DISABLED;
		if (pressed) {
			fill = TITLE_FILL_PRESSED;
		}
		OriginUi.panel(g, button.xPosition, button.yPosition, button.width, button.height, RADIUS, fill, 0);
	}

	/** Centred label through OriginText (the one Origin text voice). Raw label
	 *  text is kept as-is so "Options..." reads right; trimmed with "..." when it
	 *  would overflow the box (Forge's GuiButtonExt trims the same way). */
	private static void drawLabelCentered(Gfx g, GuiButton button, double cx, double cy, int color) {
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		String s = button.displayString == null ? "" : button.displayString;
		int maxW = Math.max(8, button.width - 6);
		if (OriginText.widthBold(font, s) > maxW) {
			s = OriginText.ellipsize(font, s, maxW);
		}
		int tw = OriginText.widthBold(font, s);
		OriginText.drawBold(g, font, s, (int) (cx - tw / 2.0), (int) (cy - 4), color, true);
	}

	private static void drawTitlePresentation(Gfx g, GuiButton button, TitlePresentation title,
											  boolean enabled, double hover) {
		int x = button.xPosition, y = button.yPosition, w = button.width, h = button.height;
		int color = enabled ? OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TEXT, hover) : LABEL_DISABLED;
		int iconSize = Math.max(10, Math.min(12, h - 5));
		if (title.iconOnly()) {
			ModIcons.draw(g, title.icon(), x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, color);
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		String label = button.displayString == null ? "" : button.displayString;
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
