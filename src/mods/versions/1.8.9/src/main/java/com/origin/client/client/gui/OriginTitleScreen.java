package com.origin.client.client.gui;

import com.origin.client.client.StartupTransition;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.render.TitleLayout;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

// The Origin main menu -- 1.21.1's Workbench title (TitleScreenMixin, A-Workbench-3,
// Will 2026-10-25):
//
//   (head) Player                                   no plate, Inter name
//
//                            (logo)                 the real origin_logo.png
//                          [wordmark]               the real origin_wordmark.png
//                 [      Singleplayer          ]    200 x 20 Minecraft dark buttons,
//                 [      Multiplayer           ]    words only, Minecraft's font
//                 [   Options  ][    Mods      ]
//
//              [friends][cosmetics][lang][quit]     20 x 20 dark buttons, premade icons
//   Minecraft 1.8.9 . Origin          Copyright Mojang AB. Do not distribute!
//
// Background: 1.8.9's own panorama, drawn and blurred by vanilla's renderer
// (MenuPanorama), under the title dim and vignette. No splash.
// GEOMETRY LIVES IN ONE PLACE: TitleLayout. Every control is a TitleButton:
// Minecraft's own dark button (the dark-widget path) with a white Minecraft-font
// label or a dock icon (TitleButton). No hover tooltips anywhere on the title
// (1.21.1 narrates its dock tips but never draws them; 1.8.9 has no narrator).
//
// 1.8.9 best fit: later versions REPOSITION vanilla's own TitleScreen widgets
// from a mixin. 1.8.9's GuiMainMenu builds its buttons with bare ids and draws
// its panorama, logo, splash, Forge branding and Realms notice inline in one
// drawScreen, so a mixin would have to suppress half a dozen INVOKE sites. This
// screen REPLACES GuiMainMenu instead (MenuHooks swaps it in on GuiOpenEvent,
// only while Main Menu Style = "Origin"). Because the rows are this screen's own
// button references, other mods' buttons can never take their slots (1.21.1
// finds Singleplayer/Multiplayer by label for that reason); foreign buttons are
// moved clear of the layout by OriginForeignWidgets (MenuHooks).
// Differences from 1.21.1, by necessity:
//  - no Realms square (1.8.9 Realms is retired);
//  - no Accessibility square (1.8.9 has no accessibility screen);
//  - Language is Origin's square here (1.8.9's GuiMainMenu has one too, but this
//    screen replaces it) and Options sits in the pair, as on 1.21.1;
//  - no demo-mode / OpenGL-warning / Forge-branding lines.
// Fail-soft: OriginScreen latches this class broken on any throw (MenuHooks then
// stops substituting it) and a broken backdrop sends the player to vanilla's
// GuiMainMenu on the next tick.
//
// The launch journey (StartupTransition): 1.21.1 holds its finished loading
// picture over the title until the title's first frames settle, then dissolves
// it over REVEAL_MS. 1.8.9's startup load is Forge's own splash, which nothing of
// Origin's can draw on, so the first title of a game start plays that ending
// itself: the clean splash at full strength over the title rendered for real
// underneath (HOLD) until SETTLE_FRAMES frames in a row come in under the settle
// limit -- or HOLD_CAP_MS, or a click -- then the dissolve. The title's entrance
// (the rows rising in) counts from the reveal.
public class OriginTitleScreen extends OriginScreen {
	// Button ids: actionPerformed dispatches on the instance, not the id, but
	// distinct ids keep other mods' InitGuiEvent code from mistaking ours for
	// vanilla's (GuiMainMenu uses 0..6, 14 and 11/12 in demo mode).
	private static final int ID_BASE = 0x4F520;

	// The settle hold, as 1.21.1's LoadingOverlayRevealMixin.
	/** Frames in a row under the settle limit before the dissolve may start. */
	private static final int SETTLE_FRAMES = 5;
	/** The settle limit on a machine that runs at 60 fps or better (ms). */
	private static final double SETTLE_MS = 20.0;
	/** On a slower machine: this much over its own fastest hold frame. */
	private static final double SETTLE_SPREAD = 1.5;
	/** The hold can never last longer than this (ms). */
	private static final long HOLD_CAP_MS = 1500;

	private GuiButton singleplayer, multiplayer, options, mods, friends, cosmetics, language, quit;
	// Static: the entrance plays once per game start, not on every return to the title.
	private static long openedAt = 0;
	// The startup intro (class comment): NONE, HOLD (the splash over the settling
	// title) or DISSOLVE (the splash fading); dissolveAt = its first frame (0 = not yet).
	private static final int NONE = 0, HOLD = 1, DISSOLVE = 2;
	private static int intro = NONE;
	private static long dissolveAt = 0;
	// Settle-hold clock: first hold frame and the previous one (nanoTime, 0 = not
	// yet), the fastest hold frame, and the last SETTLE_FRAMES frame times.
	private static long holdAt = 0, lastHold = 0;
	private static int holdFrames = 0;
	private static double fastest = Double.MAX_VALUE;
	private static final double[] RECENT = new double[SETTLE_FRAMES];

	private boolean fallBack = false;
	private final List<GuiButton> nav = new ArrayList<>();
	private final List<int[]> navPos = new ArrayList<>();
	// The icon squares and their Origin icon (index-aligned).
	private final List<GuiButton> dock = new ArrayList<>();
	// The frame's Gfx, for the TitleButtons super.render draws (one Gfx per frame, none per button).
	private Gfx frameGfx;

	public OriginTitleScreen() {
		super("Origin");
	}

	/** Dev shots: the startup splash is still over the title (hold or dissolve). */
	public static boolean introPlaying() {
		return intro != NONE;
	}

	@Override
	protected void init() {
		if (openedAt == 0) {           // first title of the game only -- a resize or return doesn't replay
			openedAt = StartupTransition.clockMs();
			if (StartupTransition.claimIntro() && OriginScreenRenderer.isActive()) {
				intro = HOLD;
			}
		}
		nav.clear();
		navPos.clear();
		dock.clear();

		singleplayer = add(0, I18n.format("menu.singleplayer"));
		multiplayer = add(1, I18n.format("menu.multiplayer"));
		options = add(2, I18n.format("options.title"));   // "Options", as 1.21.1
		mods = add(3, "Mods");
		friends = add(4, "");
		cosmetics = add(5, "");
		language = add(6, "");
		quit = add(7, "");

		// Two full rows, then the Options | Mods pair.
		TitleLayout.rows(3);
		TitleLayout L = TitleLayout.of(width, height);
		int half = (L.mainW - L.pairGap) / 2;
		place(singleplayer, L.mainX, L.mainTop, L.mainW, L.buttonH, true);
		place(multiplayer, L.mainX, L.mainTop + L.rowStep, L.mainW, L.buttonH, true);
		place(options, L.mainX, L.mainTop + 2 * L.rowStep, half, L.buttonH, true);
		place(mods, L.mainX + L.mainW - half, L.mainTop + 2 * L.rowStep, half, L.buttonH, true);

		// Bottom row of 20 x 20 dark buttons in 1.21.1's order, Quit at its end (no Realms or Accessibility on
		// 1.8.9; class comment). Cosmetics and Quit are title_icons.png (1.21.1's sheet); Friends and Language are
		// Origin's own glyphs: 1.8.9 has no toast/social_interactions sprite, and its Language button is vanilla's
		// LIGHT widgets.png globe sprite (GuiButtonLanguage draws it itself, outside the dark-widget path).
		icon(friends, "@title-multiplayer");
		icon(cosmetics, "cosmetics");
		icon(language, "@title-language");
		icon(quit, "quit");
		int totalW = dock.size() * L.dockSize + Math.max(0, dock.size() - 1) * L.dockGap;
		int dx = (width - totalW) / 2;
		for (GuiButton b : dock) {
			place(b, dx, L.dockY, L.dockSize, L.dockSize, false);
			dx += L.dockSize + L.dockGap;
		}
		applyPositions();
	}

	private GuiButton add(int slot, String label) {
		GuiButton b = new TitleButton(ID_BASE + slot, label);
		buttonList.add(b);
		return b;
	}

	private void icon(GuiButton b, String id) {
		dock.add(b);
		((TitleButton) b).dockIcon = id;
	}

	/**
	 * A title control (Will, 2026-10-27: "the same ones" as every other menu): Minecraft's own button -- GuiButton's
	 * drawButton, which the module's dark-widget path (GuiButtonMixin -> OriginDarkWidgets) points at the dark page --
	 * with its label in Minecraft's font, white (0xA0A0A0 disabled; never the yellow hover tint, as 1.21.1), and a
	 * dock button's icon on top (OriginButtonRenderer.titleIcon). Forge's GuiButton picks packedFGColour first when
	 * it is non-zero (javap), so white is set there; 0 lets vanilla's own disabled grey through.
	 * A subclass, not a mixin: these are this screen's own buttons (1.21.1 draws vanilla's via AbstractButtonMixin).
	 */
	private final class TitleButton extends GuiButton {
		/** The dock icon id (null for worded buttons), drawn over the dark button's blank face. */
		String dockIcon;

		TitleButton(int id, String label) {
			super(id, 0, 0, 150, 20, label);
		}

		@Override
		public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
			if (!visible) {
				return;
			}
			packedFGColour = enabled ? 0xFFFFFF : 0;
			super.drawButton(mc, mouseX, mouseY);
			Gfx g = frameGfx;
			if (dockIcon != null && g != null) {
				OriginButtonRenderer.titleIcon(g, dockIcon, xPosition, yPosition, width, height, enabled);
			}
		}
	}

	private void place(GuiButton b, int x, int y, int w, int h, boolean animated) {
		b.xPosition = x;
		b.yPosition = y;
		b.width = w;
		b.height = h;
		if (animated) {
			nav.add(b);
			navPos.add(new int[]{x, y});
		}
	}

	/** The rows' staggered entrance rise on Y (the dock sits still), re-applied
	 *  every frame like the other versions' title mixins. */
	private void applyPositions() {
		long el = StartupTransition.clockMs() - StartupTransition.titleIntroStart(openedAt);
		for (int i = 0; i < nav.size(); i++) {
			int[] t = navPos.get(i);
			double e = el > 1200 ? 1.0 : ease((el - 120 - i * 55) / 380.0);
			GuiButton w = nav.get(i);
			w.xPosition = t[0];
			w.yPosition = t[1] + (int) Math.round((1.0 - e) * 8);
		}
	}

	private static double ease(double raw) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, raw)));
	}

	@Override
	public void render(Gfx g, int mouseX, int mouseY, float partialTick) {
		long fadeT = -1;
		if (intro == DISSOLVE) {
			long t = dissolveAt == 0 ? 0 : StartupTransition.clockMs() - dissolveAt;
			if (t >= StartupTransition.REVEAL_MS) {
				intro = NONE;
			} else {
				fadeT = t;
			}
		}
		if (!OriginScreenRenderer.renderTitleBackdrop(g)) {
			// Origin rendering failed this session: a plain frame now, vanilla's
			// GuiMainMenu on the next tick (tick() below).
			g.fill(0, 0, width, height, 0xFF000000);
			fallBack = true;
			intro = NONE;
			return;
		}
		OriginScreenRenderer.renderTitleWordmark(g);
		OriginScreenRenderer.renderTitleAccountChip(g);
		applyPositions();
		frameGfx = g;
		try {
			super.render(g, mouseX, mouseY, partialTick);   // the TitleButtons (and any other mod's buttons)
		} finally {
			frameGfx = null;
		}
		drawFooter(g);

		if (intro == HOLD) {
			// The title is drawn for real underneath; the splash covers it until
			// its frames settle (class comment).
			if (!OriginScreenRenderer.renderStartupLoading(g) || holdDone()) {
				intro = DISSOLVE;
			}
			return;
		}
		if (fadeT >= 0) {
			float alpha = (float) (1.0 - StartupTransition.ease(fadeT / (double) StartupTransition.REVEAL_MS));
			if (!OriginScreenRenderer.renderLoadingFade(g, alpha, true)) {
				intro = NONE;
			} else if (dissolveAt == 0) {
				// First dissolve frame: the splash still fully covers the title. Both
				// the dissolve and the title's entrance count from here.
				dissolveAt = StartupTransition.clockMs();
				StartupTransition.titleRevealed();
			}
		}
	}

	private static final String COPYRIGHT = "Copyright Mojang AB. Do not distribute!";

	// The footer strings (fixed for the game's life). 1.21.1 builds "Minecraft <version>" from SharedConstants;
	// 1.8.9's version is the module's own.
	private static final String FOOTER_VERSION = "Minecraft 1.8.9";
	private static final String FOOTER_LINE = FOOTER_VERSION + " \u00B7 Origin";   // middle dot, escaped: sources stay ASCII
	private static int copyrightW = -1, copyrightGs = -1;

	/**
	 * 1.21.1's footer line: "Minecraft 1.8.9 . Origin" CORNER in from the left, the copyright with its right edge
	 * CORNER in from the right, both on the line box FOOTER_TOP above the bottom, textMuted, Inter (the dot is
	 * drawn). Minecraft's font if Inter is not live. The copyright is plain text on 1.8.9 (vanilla's GuiMainMenu
	 * has no credits link there).
	 */
	private void drawFooter(Gfx g) {
		int top = height - TitleLayout.FOOTER_TOP;
		int argb = OriginTheme.TITLE_TEXT_MUTED;
		if (OriginButtonRenderer.titleTextActive()) {
			OriginButtonRenderer.drawTitleFooterLine(g, FOOTER_VERSION, "Origin", TitleLayout.CORNER, top, argb);
			int gs = Gfx.guiScale();
			if (copyrightW < 0 || copyrightGs != gs) {
				copyrightW = (int) Math.ceil(OriginButtonRenderer.titleTextWidth(COPYRIGHT, OriginTheme.TITLE_SMALL_EM, false));
				copyrightGs = gs;
			}
			OriginButtonRenderer.drawTitleText(g, COPYRIGHT, width - copyrightW - TitleLayout.CORNER, top,
					OriginTheme.TITLE_SMALL_EM, argb, false);
		} else {
			g.drawString(fontRendererObj, FOOTER_LINE, TitleLayout.CORNER, top, argb, true);
			g.drawString(fontRendererObj, COPYRIGHT, width - fontRendererObj.getStringWidth(COPYRIGHT) - TitleLayout.CORNER, top, argb, true);
		}
	}

	/** One settle-hold frame's bookkeeping; true once the hold should end. */
	private static boolean holdDone() {
		long ns = System.nanoTime();
		if (holdAt == 0L) {
			holdAt = ns;
		} else {
			double frame = (ns - lastHold) / 1e6;
			RECENT[holdFrames % SETTLE_FRAMES] = frame;
			holdFrames++;
			fastest = Math.min(fastest, frame);
		}
		lastHold = ns;
		if ((ns - holdAt) / 1_000_000L >= HOLD_CAP_MS) {
			return true;
		}
		if (holdFrames < SETTLE_FRAMES) {
			return false;
		}
		double limit = Math.max(SETTLE_MS, (fastest == Double.MAX_VALUE ? 0.0 : fastest) * SETTLE_SPREAD);
		for (double f : RECENT) {
			if (f > limit) {
				return false;
			}
		}
		return true;
	}

	// A click during the hold skips straight to the dissolve (the buttons under
	// the splash are not visible yet, so it never presses one).
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (intro == HOLD) {
			intro = DISSOLVE;
			return true;
		}
		return false;
	}

	// Leaving the title mid-intro ends it: a later return shows the plain title.
	@Override
	public void removed() {
		intro = NONE;
	}

	@Override
	public void tick() {
		if (fallBack) {
			fallBack = false;
			// MenuHooks lets vanilla's own menu through once Origin rendering is off.
			mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
		}
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (button == singleplayer) {
			mc.displayGuiScreen(new GuiSelectWorld(this));
		} else if (button == multiplayer) {
			mc.displayGuiScreen(new GuiMultiplayer(this));
		} else if (button == options) {
			mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
		} else if (button == mods) {
			mc.displayGuiScreen(new OriginModMenuScreen());
		} else if (button == friends) {
			mc.displayGuiScreen(new FriendsScreen(this));
		} else if (button == cosmetics) {
			mc.displayGuiScreen(new CosmeticsScreen(this));
		} else if (button == language) {
			mc.displayGuiScreen(new GuiLanguage(this, mc.gameSettings, mc.getLanguageManager()));
		} else if (button == quit) {
			mc.shutdown();
		}
	}

	// The title is the root: Esc does nothing (vanilla's GuiMainMenu ignores it too).
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
