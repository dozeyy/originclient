package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.WeakHashMap;

import com.origin.client.client.theme.OriginTheme;

// Two skins, one entry point per widget kind (the widget mixins call render /
// renderTab / renderIconButton / renderSlider / renderCheckbox and cancel vanilla
// only when they return true):
//
//  1. ORIGIN'S OWN WINDOWS (the Mod Menu family; any com.origin.* screen --
//     originOwnsScreen()). Every vanilla-class widget there is redrawn as the glass
//     control: OriginUi.panel + the OriginTheme.BOX_* tokens, eased hover, accent wash.
//     PROTECTED (the Mod Menu must not change by a pixel) -- do not touch box(), the
//     BOX_* tokens or RADIUS for a title change.
//
//  2. THE TITLE SCREEN (1.21.1's Phase 4 Workbench look, ported 2026-10-25). Minecraft's
//     other menus keep stock widgets (Will, 2026-10-19), but the title's controls are
//     registered by TitleScreenMixin (setTitleButton / setTitleDock) and draw through
//     titleControl(): a Workbench card (radius 3 GUI px, OriginTheme.TITLE_* tokens, Inter
//     600 label in parchment) for the worded buttons, and for the dock the SAME card with a
//     parchment glyph (Will, 2026-10-25: dock icons "not colored"). A widget that was not
//     registered is untouched vanilla. Labels fall back to Minecraft's font when the SDF
//     face is not live. The same skin owns the title's footer line colour (setTitleFooter /
//     footerColor) and its tooltip panel (renderTitleTooltip, from TooltipRenderUtilMixin
//     only while the title screen is up).
//
// Fail-soft, two latches: `broken` (skin 1) and `titleBroken` (skin 2) flip the affected
// widgets back to vanilla for the rest of the session when an Origin draw throws. They are
// separate so a title draw failure can never alter the Mod Menu, and the reverse.
//
// PER-VERSION DELTA (26.x): SpriteIconButton's `sprite` is a WidgetSprites bundle,
// blitSprite takes the RenderPipeline + an ARGB tint, the GUI pose is a Matrix3x2fStack,
// the press state is MouseHandler.isLeftPressed() (no GLFW: 26.3 runs on SDL3), and the
// pointer cursor must be requested here because the cancelled vanilla extract no longer
// reaches AbstractButton's handleCursor.
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
	// Will, 2026-10-19 (final): Minecraft's own menus are stock widgets again, always
	// -- the glass skin applies only inside Origin's own windows (OriginStyle.vanillaButtonsOn).
	// This is the ONE gate AbstractButtonMixin, AbstractSliderButtonMixin,
	// CheckboxMixin, TabButtonMixin and SpriteIconButtonMixin all pass through.
	private static boolean originOwnsScreen() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.gui.screen() != null
				&& !com.origin.client.client.OriginStyle.vanillaButtonsOn(mc.gui.screen());
	}

	// PRIMARY buttons (the one main action on a screen, e.g. Singleplayer on the
	// title) draw with the accent: an accent wash fill + accent border at rest,
	// firming to full accent on hover. Registered by the screen that owns the
	// widget; weak so a rebuilt screen's widgets are collected normally.
	private static final Map<Object, Boolean> PRIMARY = new WeakHashMap<>();
	private static final Map<Object, TitlePresentation> TITLE = new WeakHashMap<>();

	/** icon: a dock icon id (ICON_RECTS key, "friends", or an "@" ModIcons glyph; null = none); iconOnly: a dock
	 *  button vs a worded one; small: a loading screen's Cancel (the Workbench card, Minecraft-font parchment label). */
	private record TitlePresentation(String icon, boolean iconOnly, boolean small) {
	}

	public static void markPrimary(Object widget) {
		if (widget != null) {
			PRIMARY.put(widget, Boolean.TRUE);
		}
	}

	/** A worded title button (Singleplayer, Options, Mods...): a card with the label
	 *  centred and an optional 8 GUI px leading glyph (icon null = label only). */
	public static void setTitleButton(Object widget, String icon) {
		if (widget != null) {
			TITLE.put(widget, new TitlePresentation(icon, false, false));
		}
	}

	/** A loading screen's Cancel (Connecting, Realms connecting): the Workbench card, its label in Minecraft's
	 *  font in parchment like 1.21.1 (Will, 2026-10-27; muted read as disabled, judge 2026-10-27). */
	public static void setLoadingButton(Object widget) {
		if (widget != null) {
			TITLE.put(widget, new TitlePresentation(null, false, true));
		}
	}

	/** A title dock action: a 20 x 20 dark Minecraft button with its icon centred (vanillaTitleButton). */
	public static void setTitleDock(Object widget, String icon) {
		if (widget != null && icon != null) {
			TITLE.put(widget, new TitlePresentation(icon, true, false));
		}
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin widget rendering failed; falling back to vanilla widgets for this session", t);
		return false;
	}

	/** True while the vanilla title screen is the current screen (26.2+: the screen lives on Minecraft.gui). */
	private static boolean titleUp() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen;
	}

	// ---- skin 2: the title screen's controls (1.21.1's Phase 4, Workbench) ----
	// Spec: docs/overhaul/DESIGN-SYSTEM.md 15.2 + docs/overhaul/design/A-Workbench-3.jpg. Worded
	// buttons are a card (radius 3 GUI px, 1 GUI px edge) with an Inter 600 label centred and an
	// optional 8 GUI px leading glyph; the dock is the same card with a 12 GUI px parchment glyph.
	// Hover/press use the shared 48 / 64 ms ease; geometry never moves under the pointer. One SDF
	// quad per panel (the edge is folded into the fill colour, see flatten).
	private static final int GLYPH = 12, LEAD_ICON = 8, LEAD_GAP = 6;

	/** Draws a registered title control; false = not ours (or latched): vanilla draws it. */
	private static boolean titleControl(GuiGraphicsExtractor g, AbstractButton button) {
		TitlePresentation tp = TITLE.get(button);
		if (tp == null || titleBroken || com.origin.client.client.OriginStyle.vanillaTitle()) {
			return false;
		}
		if (!tp.small()) {
			return vanillaTitleButton(g, button, tp);   // the title's buttons and dock: Minecraft's own dark button
		}
		try {
			boolean enabled = button.active;
			double hv = hoverEase(button, enabled && button.isHovered());
			// 26.x: MouseHandler.isLeftPressed() (javap: 26.1.2, 26.2, 26.3) -- no GLFW, which 26.3 lacks.
			boolean pressed = enabled && button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed();
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			if (tp.iconOnly()) {
				drawDock(g, tp, x, y, w, h, enabled, hv, pressed);
			} else {
				drawPanelButton(g, button, tp, x, y, w, h, enabled, hv, pressed);
			}
			if (enabled && button.isFocused()) {
				// Keyboard focus: a 1 GUI px accentLine ring on the control's own box, never colour alone.
				OriginUi.roundRect(g, x, y, w, h, OriginTheme.TITLE_BTN_RADIUS, 0, OriginTheme.TITLE_FOCUS, 1f);
			}
			// The pointer vanilla's AbstractButton extract requests (handleCursor), which the
			// cancelled draw no longer reaches.
			if (button.isHovered()) {
				g.requestCursor(enabled ? com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND
						: com.mojang.blaze3d.platform.cursor.CursorTypes.NOT_ALLOWED);
			}
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error(
					"Origin title controls failed; falling back to vanilla widgets on the title for this session", t);
			return false;
		}
	}

	private static void drawPanelButton(GuiGraphicsExtractor g, AbstractButton button, TitlePresentation tp,
										int x, int y, int w, int h, boolean enabled, double hv, boolean pressed) {
		drawCard(g, x, y, w, h, enabled, hv, pressed);
		// The label: a loading screen's Cancel (small, the only card left after 2026-10-27) is always
		// Minecraft's font, like every loading-screen line, in parchment (1.21.1). Measured once per widget
		// and again only when the widget's message Component is replaced, so an ordinary frame measures nothing.
		float em = tp.small() ? OriginTheme.TITLE_SMALL_EM : OriginTheme.TITLE_LABEL_EM;
		Label lab = label(button, !tp.small() && OriginSdfFont.active(), em);
		int textColor = !enabled ? OriginTheme.TITLE_TEXT_MUTED
				: OriginTheme.TITLE_TEXT;   // parchment at rest, the loading Cancel included: muted read as disabled (judge 2026-10-27)
		boolean lead = tp.icon() != null;
		float groupW = lead ? LEAD_ICON + LEAD_GAP + lab.width : lab.width;
		float gx = x + (w - groupW) / 2f;
		// Press: the label (and glyph) sit 1 GUI px lower; the panel itself never moves under the pointer.
		int dy = pressed ? 1 : 0;
		if (lead) {
			int iconColor = enabled ? OriginTheme.lerpColor(OriginTheme.TEXT_DIM, OriginTheme.TITLE_TEXT, hv) : OriginTheme.TITLE_TEXT_MUTED;
			ModIcons.drawFlat(g, tp.icon(), Math.round(gx), y + (h - LEAD_ICON) / 2 + dy, LEAD_ICON, iconColor);
			gx += LEAD_ICON + LEAD_GAP;
		}
		if (lab.sdf) {
			OriginSdfFont.drawEm(g, lab.text, gx, y + h / 2f + lab.cap / 2f + dy, em, 0f, textColor, true);
		} else {
			g.text(Minecraft.getInstance().font, lab.text, Math.round(gx), y + (h - 8) / 2 + dy, textColor, true);
		}
	}

	/** A title button's label, measured once: its text, advance width and cap height (GUI px), and which font measured it. */
	private static final class Label {
		Component message;
		String text;
		float width, cap, em;
		boolean sdf;
	}

	private static final Map<Object, Label> LABELS = new WeakHashMap<>();

	private static Label label(AbstractButton button, boolean sdf, float em) {
		Component message = button.getMessage();
		Label lab = LABELS.get(button);
		if (lab == null) {
			lab = new Label();
			LABELS.put(button, lab);
		} else if (lab.message == message && lab.sdf == sdf && lab.em == em) {
			return lab;
		}
		lab.message = message;
		lab.text = message.getString();
		lab.sdf = sdf;
		lab.em = em;
		if (sdf) {
			lab.width = OriginSdfFont.widthEm(lab.text, em, 0f, true);
			lab.cap = OriginSdfFont.capHeight(em, true);
		} else {
			lab.width = Minecraft.getInstance().font.width(lab.text);
			lab.cap = 7f;
		}
		return lab;
	}

	/** The Workbench card behind every title control (worded or dock): fill + 1 GUI px hairline, radius 3. */
	private static void drawCard(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, double hv, boolean pressed) {
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
		OriginUi.roundRect(g, x, y, w, h, OriginTheme.TITLE_BTN_RADIUS, fill, flatten(fill, edge), 1f);
	}

	// Dock action (Will, 2026-10-25: dock icons "not colored", the same colour scheme as the normal
	// buttons): the worded buttons' card exactly, with the glyph centred in parchment (muted when
	// disabled). Press drops the glyph 1 GUI px, like a label.
	private static void drawDock(GuiGraphicsExtractor g, TitlePresentation tp, int x, int y, int w, int h,
								 boolean enabled, double hv, boolean pressed) {
		drawCard(g, x, y, w, h, enabled, hv, pressed);
		int color = enabled ? OriginTheme.TITLE_TEXT : OriginTheme.TITLE_TEXT_MUTED;
		ModIcons.drawFlat(g, tp.icon(), x + (w - GLYPH) / 2, y + (h - GLYPH) / 2 + (pressed ? 1 : 0), GLYPH, color);
	}

	// The title dock's icons (Will, 2026-10-27, from 1.21.1: the title buttons are "the same ones" as every other
	// menu -- Minecraft's dark button, Minecraft's font -- with premade icons, words only on the big buttons).
	// Cosmetics, Realms and Quit come from free icon packs, rebuilt by tools/title-icons/make_icons.py into one
	// sheet (credits shipped beside it as title_icons_CREDITS.txt). Origin's Friends is vanilla's own social-
	// interactions sprite drawn from the game's atlas; Mojang's own Friends button (26.2+, FriendsButton) keeps its
	// single-person "@user" glyph; Language / Accessibility stay vanilla's own sprite buttons (not registered).
	private static final Identifier TITLE_ICONS = Identifier.fromNamespaceAndPath("originclient", "textures/gui/title_icons.png");
	private static final int ICONS_W = 48, ICONS_H = 16;
	private static final Map<String, int[]> ICON_RECTS = Map.of(
			// {u, v, w, h} of the art with its 1 px shadow, then the white body's own w, h: the body is what is centred
			"cosmetics", new int[]{0, 0, 15, 13, 14, 12},
			"realms", new int[]{16, 0, 16, 14, 15, 13},
			"quit", new int[]{32, 0, 13, 13, 12, 12});
	// 20 x 20, its bubble centred in the frame: drawn whole, centred on the 20 x 20 dock button. 26.2/26.3 ship it
	// as pause_menu/social_interactions (the same 20 x 20 art as 26.1's toast/social_interactions, which they dropped;
	// unzip of each client jar, 2026-10-27).
	private static final Identifier SOCIAL = Identifier.withDefaultNamespace("pause_menu/social_interactions");
	// Vanilla AbstractButton's three sprites; the jar's dark widget art overrides them, so these ARE the dark buttons.
	private static final Identifier BTN = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BTN_OFF = Identifier.withDefaultNamespace("widget/button_disabled");
	private static final Identifier BTN_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
	/** Vanilla's disabled label colour; the same grey tints a disabled button's icon (1.21.1's setColor 0.63). */
	private static final int GREY = 0xFFA0A0A0;

	/**
	 * A title button drawn exactly like vanilla's AbstractButton (26.x extractDefaultSprite: the same sprite set by
	 * active / isHoveredOrFocused; a white / A0A0A0 label in Minecraft's font with its shadow; instant hover). A dock
	 * button shows its icon centred (where SpriteIconButton puts its sprite) instead of its label; the icon carries its
	 * own drop shadow in the art, and a disabled button greys it like a label (per-blit ARGB: no shader colour in 26.x).
	 */
	private static boolean vanillaTitleButton(GuiGraphicsExtractor g, AbstractButton button, TitlePresentation tp) {
		try {
			boolean enabled = button.active;
			int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
			g.blitSprite(RenderPipelines.GUI_TEXTURED, !enabled ? BTN_OFF : button.isHoveredOrFocused() ? BTN_HOVER : BTN, x, y, w, h, 0xFFFFFFFF);
			int tint = enabled ? 0xFFFFFFFF : GREY;
			if (tp.iconOnly()) {
				String icon = tp.icon();
				int[] r = icon == null ? null : ICON_RECTS.get(icon);
				if ("friends".equals(icon)) {
					g.blitSprite(RenderPipelines.GUI_TEXTURED, SOCIAL, x + w / 2 - 10, y + h / 2 - 10, 20, 20, tint);
				} else if (r != null) {
					// Vanilla SpriteIconButton's placement on the body (odd pixel left/top), so it sits like Language / Accessibility.
					g.blit(RenderPipelines.GUI_TEXTURED, TITLE_ICONS, x + w / 2 - r[4] / 2, y + h / 2 - r[5] / 2,
							r[0], r[1], r[2], r[3], ICONS_W, ICONS_H, tint);
				} else if (icon != null && icon.startsWith("@")) {
					// Mojang's own FriendsButton (26.2+): its single-person glyph, parchment, on the dark button.
					ModIcons.drawFlat(g, icon, x + (w - GLYPH) / 2, y + (h - GLYPH) / 2, GLYPH,
							enabled ? OriginTheme.TITLE_TEXT : OriginTheme.TITLE_TEXT_MUTED);
				}
			} else {
				g.centeredText(Minecraft.getInstance().font, button.getMessage(), x + w / 2, y + (h - 8) / 2,
						enabled ? 0xFFFFFFFF : GREY);
			}
			// The pointer vanilla's AbstractButton extract requests (handleCursor), which the cancelled draw no longer reaches.
			if (button.isHovered()) {
				g.requestCursor(enabled ? com.mojang.blaze3d.platform.cursor.CursorTypes.POINTING_HAND
						: com.mojang.blaze3d.platform.cursor.CursorTypes.NOT_ALLOWED);
			}
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error(
					"Origin title controls failed; falling back to vanilla widgets on the title for this session", t);
			return false;
		}
	}

	// ---- the title's footer line and tooltip (Workbench 15.2 / 11.8) ----

	// The title's copyright button (vanilla's PlainTextButton, still the real clickable widget):
	// registered by TitleScreenMixin so PlainTextButtonMixin recolours ONLY that one.
	private static final Map<Object, Boolean> TITLE_FOOTER = new WeakHashMap<>();

	public static void setTitleFooter(Object widget) {
		if (widget != null) {
			TITLE_FOOTER.put(widget, Boolean.TRUE);
		}
	}

	public static boolean isTitleFooter(Object widget) {
		return widget != null && !titleBroken && TITLE_FOOTER.containsKey(widget)
				&& !com.origin.client.client.OriginStyle.vanillaTitle();
	}

	/** Footer text colour: textMuted at rest, text on hover; the widget's own fade-in alpha rides along. */
	public static int footerColor(boolean hovered, int vanillaArgb) {
		int base = hovered ? OriginTheme.TEXT : OriginTheme.TITLE_TEXT_MUTED;
		int a = (int) Math.round(((base >>> 24) & 0xFF) * (((vanillaArgb >>> 24) & 0xFF) / 255.0));
		return (Math.max(a, 4) << 24) | (base & 0xFFFFFF);   // keep a visible floor, as on 1.21.1
	}

	// ---- Inter on the title's own text (player name, footer, copyright, tooltips) ----
	// Placed on Minecraft's 8 GUI px line box (vanilla's caps fill top..top+7) so the swap moves nothing: the
	// Inter cap height is centred on the same line. Every caller falls back to vanilla's font when this says no.

	/** True when the title's own text should be Inter: Origin title on, title skin not latched, SDF face live. */
	public static boolean titleTextActive() {
		return !titleBroken && !com.origin.client.client.OriginStyle.vanillaTitle() && OriginSdfFont.active();
	}

	/** Inter text whose 8 GUI px line box starts at {@code top} (what vanilla text(..., y) would fill). */
	public static void drawTitleText(GuiGraphicsExtractor g, String text, float x, float top, float em, int argb, boolean bold) {
		OriginSdfFont.drawEm(g, text, x, top + 3.5f + OriginSdfFont.capHeight(em, bold) / 2f, em, 0f, argb, bold);
	}

	public static float titleTextWidth(String text, float em, boolean bold) {
		return OriginSdfFont.widthEm(text, em, 0f, bold);
	}

	/**
	 * The footer's left line, "{@code left} . {@code right}" in Inter. The Inter atlas has no middle dot (it would
	 * draw as a blank gap), so the dot is a square of whole physical pixels on the x-height middle. Returns the
	 * line's right edge (GUI px).
	 */
	public static int drawTitleFooterLine(GuiGraphicsExtractor g, String left, String right, int x, int top, int argb) {
		float em = OriginTheme.TITLE_SMALL_EM;
		float cap = OriginSdfFont.capHeight(em, false);
		float baseline = top + 3.5f + cap / 2f;
		// The same gap both sides of the dot. Measured, not " ": the atlas' space advance is near zero.
		float lw = titleTextWidth(left, em, false), space = em * 0.4f;
		drawTitleText(g, left, x, top, em, argb, false);
		int gs = Math.max(1, OriginSurface.scale());
		int dot = Math.max(1, Math.round(1.1f * gs));                     // physical px
		float half = dot / (2f * gs);                                     // GUI px
		float dotCx = x + lw + space + half, dotCy = baseline - cap * 0.36f;   // GUI px: x-height middle
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
		int dx = Math.round(dotCx * gs - dot / 2f), dy = Math.round(dotCy * gs - dot / 2f);
		g.fill(dx, dy, dx + dot, dy + dot, argb);
		pose.popMatrix();
		float rx = dotCx + half + space;
		drawTitleText(g, right, rx, top, em, argb, false);
		return Math.round(rx + titleTextWidth(right, em, false));
	}

	/** The copyright button's text width in Inter (GUI px, rounded up), so its hit box matches what is drawn. */
	public static int titleFooterWidth(String text) {
		return (int) Math.ceil(titleTextWidth(text, OriginTheme.TITLE_SMALL_EM, false));
	}

	/**
	 * Draws the registered copyright button's text in Inter (PlainTextButtonMixin redirects the button's one
	 * text() call here). {@code argb} is already footerColor's; hover swaps the colour to parchment.
	 * False = not ours or Inter not live: the caller draws vanilla text.
	 */
	public static boolean renderTitleFooterButton(GuiGraphicsExtractor g, net.minecraft.client.gui.components.PlainTextButton b,
												  int x, int y, int argb) {
		if (!isTitleFooter(b) || !titleTextActive()) {
			return false;
		}
		try {
			String text = FOOTER_TEXT.get(b);
			if (text == null) {
				text = b.getMessage().getString();   // fixed for the widget's life: read once
				FOOTER_TEXT.put(b, text);
			}
			if (b.isHoveredOrFocused()) {
				argb = (argb & 0xFF000000) | (OriginTheme.TITLE_TEXT & 0xFFFFFF);
			}
			drawTitleText(g, text, x, y, OriginTheme.TITLE_SMALL_EM, argb, false);
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin title footer failed; vanilla text for this session", t);
			return false;
		}
	}

	private static final Map<Object, String> FOOTER_TEXT = new WeakHashMap<>();

	// Dock tooltips (WidgetTooltipHolderMixin): never shown. A hover card beside a dock glyph read as a second
	// button (Will, 2026-10-25). The Tooltip objects stay on the widgets only so screen readers still narrate
	// what each glyph does. Keyed by the Tooltip object; weak, so a closed title screen's tooltips go with it.
	private static final Map<Object, Boolean> DOCK_TIPS = new WeakHashMap<>();

	public static void setDockTip(Object tooltip) {
		if (tooltip != null) {
			DOCK_TIPS.put(tooltip, Boolean.TRUE);
		}
	}

	/** True = this is a title dock tooltip: narrated, never drawn. */
	public static boolean isDockTip(Object tooltip) {
		return tooltip != null && DOCK_TIPS.containsKey(tooltip);
	}

	// Title tooltips in Inter (ClientTextTooltipMixin): the line's text, read once per line object (vanilla's
	// Tooltip keeps its split lines, so the same FormattedCharSequence comes back every frame), and its width.
	private record TipLine(String text, int width) {
	}

	private static final Map<Object, TipLine> TIP_LINES = new WeakHashMap<>();

	/** The tooltip line as Inter text while the Origin title is up; null = vanilla draws and measures it. */
	private static TipLine titleTip(net.minecraft.util.FormattedCharSequence seq) {
		if (seq == null || !titleUp() || !titleTextActive()) {
			return null;
		}
		TipLine line = TIP_LINES.get(seq);
		if (line == null) {
			StringBuilder sb = new StringBuilder();
			seq.accept((index, style, codePoint) -> {
				sb.appendCodePoint(codePoint);
				return true;
			});
			String text = sb.toString();
			line = new TipLine(text, (int) Math.ceil(titleTextWidth(text, OriginTheme.TITLE_SMALL_EM, false)));
			TIP_LINES.put(seq, line);
		}
		return line;
	}

	/** Tooltip line width in Inter, or -1 = vanilla's. */
	public static int titleTipWidth(net.minecraft.util.FormattedCharSequence seq) {
		try {
			TipLine line = titleTip(seq);
			return line == null ? -1 : line.width();
		} catch (Throwable t) {
			return -1;
		}
	}

	/** Draws a tooltip line in Inter (parchment); false = vanilla draws it. 26.x: the text pass gets the extractor. */
	public static boolean renderTitleTip(net.minecraft.util.FormattedCharSequence seq, int x, int y, GuiGraphicsExtractor g) {
		try {
			TipLine line = titleTip(seq);
			if (line == null) {
				return false;
			}
			drawTitleText(g, line.text(), x, y, OriginTheme.TITLE_SMALL_EM, OriginTheme.TITLE_TEXT, false);
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error("Origin title tooltip text failed; vanilla text for this session", t);
			return false;
		}
	}

	/**
	 * The title screen's tooltip panel: surfaceRaised, a 1 GUI px lineStrong edge, radius 4, text padded
	 * 8 x 6 (DESIGN-SYSTEM 11.8). Called from TooltipRenderUtilMixin in place of vanilla's purple box
	 * (x, y, w, h = the text block). 26.x has no z: the tooltip already sits in its own pose push.
	 * True = drawn; false = let vanilla draw (any other screen, vanilla Main Menu Style, or a latched failure).
	 */
	public static boolean renderTitleTooltip(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		if (titleBroken || !titleUp() || com.origin.client.client.OriginStyle.vanillaTitle()) {
			return false;
		}
		try {
			OriginUi.roundRect(g, x - 8, y - 6, w + 16, h + 12, OriginTheme.TITLE_RADIUS,
					OriginTheme.TITLE_TOOLTIP_FILL, flatten(OriginTheme.TITLE_TOOLTIP_FILL, OriginTheme.TITLE_BTN_EDGE_HOVER), 1f);
			return true;
		} catch (Throwable t) {
			titleBroken = true;
			com.origin.client.OriginClient.LOGGER.error(
					"Origin title tooltip failed; falling back to vanilla widgets on the title for this session", t);
			return false;
		}
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
	public static boolean render(GuiGraphicsExtractor g, AbstractButton button) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen); the title's
		// registered controls take skin 2.
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
			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, w, button.getMessage(),
					enabled ? OriginTheme.TEXT : OriginTheme.MUTED);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- header tabs (Game / World / More on Create World) ----

	/** Frost-styled tab. Selected reads as a lit box with a bright bottom accent;
	 *  unselected is the resting box with a muted label and brightens on hover. */
	public static boolean renderTab(GuiGraphicsExtractor g, Object key, int x, int y, int w, int h,
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
			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, w, label, labelColor);
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
	public static boolean renderIconButton(GuiGraphicsExtractor g, SpriteIconButton button,
										   WidgetSprites sprite, int spriteWidth, int spriteHeight) {
		// Vanilla screens get vanilla widgets (see originOwnsScreen); the title's dock takes skin 2.
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

			// Only TextAndIcon carries a label; CenteredIcon is icon-only, exactly like
			// vanilla. (1.21.11: calling button.renderString() here instead printed
			// "Language"/"Accessibility Settings" across the neighbouring buttons.)
			int ix, iy = y + h / 2 - spriteHeight / 2;
			if (button instanceof SpriteIconButton.TextAndIcon) {
				Font font = Minecraft.getInstance().font;
				OriginText.draw(g, font, button.getMessage().getString(), x + 2, y + (h - 8) / 2,
						enabled ? OriginTheme.TEXT : OriginTheme.MUTED, false);
				ix = x + w - spriteWidth - 2;
			} else {
				ix = x + w / 2 - spriteWidth / 2;
			}
			// 1.21.11: resolve the WidgetSprites variant the way vanilla's renderSprite
			// does; the tint is the per-blit ARGB (no RenderSystem.setShaderColor).
			Identifier spriteId = sprite.get(button.active, button.isHoveredOrFocused());
			g.blitSprite(RenderPipelines.GUI_TEXTURED, spriteId, ix, iy, spriteWidth, spriteHeight, 0xFFFFFFFF);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	// ---- sliders (FOV, volumes, sensitivity, ...) ----

	/** Frost-styled slider: the same box as a button plus a bright handle bar at
	 *  the value position. Vanilla's drag/click logic is untouched -- `value` is
	 *  read live each frame, so dragging stays exactly as responsive as vanilla. */
	public static boolean renderSlider(GuiGraphicsExtractor g, AbstractSliderButton slider, double value) {
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

			drawLabelCentered(g, x + w / 2.0, y + h / 2.0, w, slider.getMessage(),
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
	public static boolean renderCheckbox(GuiGraphicsExtractor g, Checkbox checkbox) {
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
	private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, double hv) {
		box(g, x, y, w, h, enabled, hv, false);
	}

	private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean enabled, double hv, boolean primary) {
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
	 *  Raw label text is kept as-is (no dot-stripping) so "Options..." reads right.
	 *  Labels wider than the box minus a 4 px inset per side are shortened
	 *  (OriginText.fitLabel) instead of spilling past the button edge; an
	 *  option's value always stays readable: "High Contrast Bl…: OFF". */
	private static void drawLabelCentered(GuiGraphicsExtractor g, double cx, double cy, int boxW, Component message, int color) {
		Font font = Minecraft.getInstance().font;
		String s = OriginText.fitLabel(font, message, boxW - 8);
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
