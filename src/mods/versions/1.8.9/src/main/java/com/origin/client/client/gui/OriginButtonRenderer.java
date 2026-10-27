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

	// Glass palette -- one material with the rest of the Origin UI. Fill, border, label
	// and handle colours are read live from the OriginTheme tokens at every draw (never
	// copied into a static final here): an equipped theme repaints them at runtime, and a
	// copy taken at class load would keep the default green hover forever. Slider handles
	// read as the accent (ACCENT_BORDER, brightening to ACCENT on hover).
	private static final int FILL_DISABLED = 0x40171C19;
	private static final int BORDER_DISABLED = 0x99080B09;
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

	// The Origin widget skin is for Origin's OWN windows only (2026-10-19 clean
	// look): Minecraft's menus always draw stock widgets (OriginStyle.vanillaButtons).
	// OriginTitleScreen is an Origin class but stands in for vanilla's GuiMainMenu,
	// so its buttons are Minecraft's too (1.21.1 keeps vanilla's TitleScreen widgets).
	private static boolean originOwnsScreen() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc != null && mc.currentScreen != null
				&& !(mc.currentScreen instanceof OriginTitleScreen)
				&& !com.origin.client.client.OriginStyle.vanillaButtonsOn(mc.currentScreen);
	}

	// PRIMARY buttons (the one main action on a screen) draw with the accent.
	private static final Map<Object, Boolean> PRIMARY = new WeakHashMap<>();
	private static final Map<Object, TitlePresentation> TITLE = new WeakHashMap<>();

	// Java 8 has no records: a final value class. small = a loading screen's Cancel (the only control still drawn
	// as the Workbench card; the title's own buttons are Minecraft's dark buttons, see titleIcon).
	private static final class TitlePresentation {
		private final String icon;
		private final boolean iconOnly;
		private final boolean small;

		private TitlePresentation(String icon, boolean iconOnly, boolean small) {
			this.icon = icon;
			this.iconOnly = iconOnly;
			this.small = small;
		}

		public String icon() {
			return icon;
		}

		public boolean iconOnly() {
			return iconOnly;
		}

		public boolean small() {
			return small;
		}
	}

	public static void markPrimary(Object widget) {
		if (widget != null) {
			PRIMARY.put(widget, Boolean.TRUE);
		}
	}

	/** A loading screen's Cancel (Connecting): the Workbench card, its label in Minecraft's font in parchment
	 *  like the loading caption above it (1.21.1's setLoadingButton, Will 2026-10-27). */
	public static void setLoadingButton(Object widget) {
		if (widget != null) {
			TITLE.put(widget, new TitlePresentation(null, false, true));
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
		box(g, x, y, w, h, enabled, hv, PRIMARY.containsKey(button));
		return true;
	}

	private static void renderLabel(Gfx g, GuiButton button) {
		drawLabelCentered(g, button, button.xPosition + button.width / 2.0, button.yPosition + button.height / 2.0,
				labelColor(button));
	}

	/** Vanilla's label colour rule, in the Origin palette: an explicit
	 *  packedFGColour (some screens tint a button's text) wins as vanilla does. */
	private static int labelColor(GuiButton button) {
		if (button.packedFGColour != 0) {
			return 0xFF000000 | button.packedFGColour;
		}
		return button.enabled ? OriginTheme.TEXT : OriginTheme.MUTED;
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
					enabled ? OriginTheme.lerpColor(OriginTheme.ACCENT_BORDER, OriginTheme.ACCENT, hv) : 0x66808080);

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
			int border = focused ? OriginTheme.ACCENT_BORDER : (enabled ? OriginTheme.BOX_BORDER : BORDER_DISABLED);
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

	// ---- skin 2: a loading screen's Cancel (1.21.1's setLoadingButton, Will 2026-10-27) ----
	// The Workbench card (radius 3 GUI px, 1 GUI px edge) with its label in Minecraft's font in parchment, like
	// every loading-screen line. Hover eases over the shared 48 / 64 ms; press darkens the card and drops the
	// label 1 GUI px; geometry never moves. The title's own buttons are NOT drawn here any more: they are
	// Minecraft's dark buttons (the module's dark-widget path, GuiButtonMixin) plus titleIcon for the dock.
	// 1.8.9 (fixed-function, no SDF shader): the card is OriginUi's software AA box drawn in PHYSICAL pixels
	// (radius 3 x guiScale, edge guiScale px). No keyboard focus ring: 1.8.9's GuiButton has no focus state.
	private static volatile boolean titleBroken = false;

	/** Dev-shots only (OriginDevShots' title-press shot): the hovered title control draws pressed. */
	public static volatile boolean devPress = false;

	/**
	 * Draws a registered loading Cancel; false = not ours (or latched): the caller draws vanilla's button.
	 * {@code hovered} is vanilla's own hit test for this frame (GuiButton.hovered).
	 */
	public static boolean titleControl(Gfx g, GuiButton button, boolean hovered) {
		TitlePresentation tp = TITLE.get(button);
		if (tp == null || !tp.small() || titleBroken || com.origin.client.client.OriginStyle.vanillaTitle()) {
			return false;
		}
		try {
			boolean enabled = button.enabled;
			double hv = hoverEase(button, enabled && hovered);
			boolean pressed = enabled && hovered && (devPress || Mouse.isButtonDown(0));
			int x = button.xPosition, y = button.yPosition, w = button.width, h = button.height;
			drawCard(g, x, y, w, h, enabled, hv, pressed);
			// Minecraft's font, parchment at rest (muted read as disabled, judge 2026-10-27), muted when disabled.
			// Measured once per widget and again only when its displayString is replaced or the GUI scale changes.
			Label lab = label(button, false, OriginTheme.TITLE_SMALL_EM);
			int textColor = enabled ? OriginTheme.TITLE_TEXT : OriginTheme.TITLE_TEXT_MUTED;
			g.drawString(Minecraft.getMinecraft().fontRendererObj, lab.text, Math.round(x + (w - lab.width) / 2f),
					y + (h - 8) / 2 + (pressed ? 1 : 0), textColor, true);
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error(
					"Origin loading Cancel failed; falling back to vanilla's button for this session", t);
			return false;
		} finally {
			Gfx.resetColor();
		}
	}

	/** A button's label, measured once: text, advance width and cap height (GUI px), and which font measured it. */
	private static final class Label {
		String source;
		String text;
		float width, cap, em;
		int gs;
		boolean ttf;
	}

	private static final Map<Object, Label> LABELS = new WeakHashMap<>();

	private static Label label(GuiButton button, boolean ttf, float em) {
		String source = button.displayString;
		int gs = Gfx.guiScale();
		Label lab = LABELS.get(button);
		if (lab == null) {
			lab = new Label();
			LABELS.put(button, lab);
		} else if (lab.source == source && lab.ttf == ttf && lab.em == em && lab.gs == gs) {
			return lab;
		}
		lab.source = source;
		lab.text = source == null ? "" : source;
		lab.ttf = ttf;
		lab.em = em;
		lab.gs = gs;
		if (ttf) {
			lab.width = OriginTtfFont.widthEm(lab.text, em * gs, 0f, true) / gs;
			lab.cap = OriginTtfFont.capHeight(em * gs, true) / gs;
		} else {
			lab.width = Minecraft.getMinecraft().fontRendererObj.getStringWidth(lab.text);
			lab.cap = 7f;
		}
		return lab;
	}

	/** The Workbench card behind the loading Cancel: fill + 1 GUI px hairline, radius 3. */
	private static void drawCard(Gfx g, int x, int y, int w, int h, boolean enabled, double hv, boolean pressed) {
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
		// Physical pixels: a 3 GUI px radius at guiScale 2+ has to be shaded per physical pixel to read as
		// 1.21.1's SDF corner, and the hairline is one GUI px = guiScale physical px. The edge is drawn OVER
		// the fill, which is exactly 1.21.1's flatten(fill, edge).
		int gs = Gfx.guiScale();
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		OriginUi.roundRect(g, x * gs, y * gs, w * gs, h * gs, OriginTheme.TITLE_BTN_RADIUS * gs, fill, edge, gs);
		pose.popPose();
	}

	// ---- the title dock's icons (Will, 2026-10-27: the title buttons are "the same ones" as every other menu --
	// Minecraft's dark button, Minecraft's font -- with premade icons, words only on the big buttons) ----
	// Cosmetics and Quit come from free icon packs, rebuilt by tools/title-icons/make_icons.py into one sheet
	// (credits shipped beside it as title_icons_CREDITS.txt), copied verbatim from 1.21.1. Realms does not exist
	// on 1.8.9's title. 1.8.9 has no toast/social_interactions sprite (no social interactions screen, no sprite
	// atlas): Friends and Language keep Origin's own crisp glyphs, white like a label.
	private static final net.minecraft.util.ResourceLocation TITLE_ICONS =
			new net.minecraft.util.ResourceLocation("originclient", "textures/gui/title_icons.png");
	private static final int ICONS_W = 48, ICONS_H = 16;
	// {u, v, w, h} of the art with its 1 px shadow, then the white body's own w, h: the body is what is centred.
	private static final int[] ICON_COSMETICS = {0, 0, 15, 13, 14, 12};
	private static final int[] ICON_QUIT = {32, 0, 13, 13, 12, 12};
	private static final int GLYPH = 12;
	private static volatile boolean iconBroken = false;

	/**
	 * Draws a dock button's icon over its dark button: "cosmetics" / "quit" from title_icons.png, centred on
	 * the body like vanilla's SpriteIconButton (x + w/2 - bodyW/2, the odd pixel left/top); any other id is an
	 * Origin glyph (ModIcons) centred at 12 GUI px. A disabled button greys it like a label (0xA0A0A0).
	 */
	public static void titleIcon(Gfx g, String icon, int x, int y, int w, int h, boolean enabled) {
		if (icon == null || iconBroken) {
			return;
		}
		try {
			int[] r = "cosmetics".equals(icon) ? ICON_COSMETICS : "quit".equals(icon) ? ICON_QUIT : null;
			if (r != null) {
				Gfx.defaultBlend();
				if (enabled) {
					Gfx.resetColor();
				} else {
					Gfx.color(0xFFA0A0A0);
				}
				g.blit(TITLE_ICONS, x + w / 2 - r[4] / 2, y + h / 2 - r[5] / 2, (float) r[0], (float) r[1], r[2], r[3], ICONS_W, ICONS_H);
			} else {
				ModIcons.drawCrisp(g, icon, x + (w - GLYPH) / 2, y + (h - GLYPH) / 2, GLYPH, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
			}
		} catch (Throwable t) {
			iconBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin title dock icons failed; dock buttons draw without icons", t);
		} finally {
			Gfx.resetColor();
		}
	}

	// ---- Inter on the title's own text (player name, footer) ----
	// Placed on Minecraft's 8 GUI px line box (vanilla's caps fill top..top+7) so the swap moves nothing. Every
	// caller falls back to vanilla's font when titleTextActive() says no.

	/** True when the title's own text should be Inter: Origin title on, title skin not latched, Inter loaded. */
	public static boolean titleTextActive() {
		return !titleBroken && !com.origin.client.client.OriginStyle.vanillaTitle() && OriginTtfFont.ready();
	}

	/** Inter at a GUI-px baseline / em: drawn 1:1 in physical pixels under a 1/guiScale pose. */
	private static void drawInter(Gfx g, String text, float x, float baseline, float em, int argb, boolean bold) {
		int gs = Gfx.guiScale();
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		OriginTtfFont.drawEm(g, text, x * gs, baseline * gs, em * gs, 0f, argb, bold);
		pose.popPose();
	}

	/** Inter text whose 8 GUI px line box starts at {@code top} (what vanilla drawString(..., y) would fill). */
	public static void drawTitleText(Gfx g, String text, float x, float top, float em, int argb, boolean bold) {
		drawInter(g, text, x, top + 3.5f + titleCap(em, bold) / 2f, em, argb, bold);
	}

	/** Advance width in GUI px. */
	public static float titleTextWidth(String text, float em, boolean bold) {
		int gs = Gfx.guiScale();
		return OriginTtfFont.widthEm(text, em * gs, 0f, bold) / gs;
	}

	private static float titleCap(float em, boolean bold) {
		int gs = Gfx.guiScale();
		return OriginTtfFont.capHeight(em * gs, bold) / gs;
	}

	/**
	 * The footer's left line, "{@code left} . {@code right}" in Inter (1.21.1's drawTitleFooterLine). The dot is a
	 * square of whole physical pixels on the x-height middle, with the same measured gap both sides. Returns the
	 * line's right edge (GUI px), like drawString.
	 */
	public static int drawTitleFooterLine(Gfx g, String left, String right, int x, int top, int argb) {
		float em = OriginTheme.TITLE_SMALL_EM;
		float cap = titleCap(em, false);
		float baseline = top + 3.5f + cap / 2f;
		float lw = titleTextWidth(left, em, false), space = em * 0.4f;
		drawTitleText(g, left, x, top, em, argb, false);
		int gs = Gfx.guiScale();
		int dot = Math.max(1, Math.round(1.1f * gs));                       // physical px
		float half = dot / (2f * gs);                                       // GUI px
		float dotCx = x + lw + space + half, dotCy = baseline - cap * 0.36f;    // GUI px: x-height middle
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		int dx = Math.round(dotCx * gs - dot / 2f), dy = Math.round(dotCy * gs - dot / 2f);
		g.fill(dx, dy, dx + dot, dy + dot, argb);
		pose.popPose();
		float rx = dotCx + half + space;
		drawTitleText(g, right, rx, top, em, argb, false);
		return Math.round(rx + titleTextWidth(right, em, false));
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
