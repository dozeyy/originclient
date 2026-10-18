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

// The Origin main menu (2026-09 redesign v6, compact centred client layout):
//
//   ┌──────────────────────────────────────────────────┐
//   │ [head] Player                                    │
//   │                                                  │
//   │                 (Origin atom)                    │
//   │                                                  │
//   │           [ Singleplayer        ]                │
//   │           [ Multiplayer         ]  (nav slides   │
//   │           [ Friends ] [ Mods    ]   in, staggered│
//   │           [ Cosmetics           ]   one pitch)   │
//   │                                                  │
//   │              (options)(lang)(quit)               │
//   └──────────────────────────────────────────────────┘
//
// 1.8.9 best fit: later versions REPOSITION vanilla's own TitleScreen widgets
// from a mixin. 1.8.9's GuiMainMenu builds its buttons with bare ids and draws
// its panorama, logo, splash, Forge branding and Realms notice inline in one
// drawScreen, so a mixin would have to suppress half a dozen INVOKE sites. This
// screen REPLACES GuiMainMenu instead (MenuHooks swaps it in on GuiOpenEvent,
// only while Main Menu Style = "Origin"), carrying the same geometry
// (TitleLayout), the same title presentations (icons + compact labels on the
// opaque title material, OriginButtonRenderer) and the same backdrop / mark /
// account chip (OriginScreenRenderer) as every other version.
//
// The buttons are ordinary GuiButtons so they skin, click and sound exactly like
// vanilla's. Differences from 1.21.1, by necessity:
//  - no Realms (1.8.9 Realms is retired; the brief drops it): its slot in the
//    paired row carries Friends, so the composition is unchanged;
//  - no Accessibility button (1.8.9 has no accessibility screen): the dock is
//    Options, Language, Quit;
//  - no demo-mode / OpenGL-warning / Forge-branding lines (the same lines the
//    other versions suppress).
// Fail-soft: OriginScreen latches this class broken on any throw (MenuHooks then
// stops substituting it) and a broken backdrop sends the player to vanilla's
// GuiMainMenu on the next tick.
//
// The launch journey (StartupTransition; docs/MOTION.md): 1.21.1 dissolves its
// startup loading overlay into the title while the loading mark flies onto the
// logo spot. 1.8.9's startup load is Forge's own splash, which nothing of
// Origin's can draw on, so the first title of a game start plays that ending
// itself: the Origin loading layer (HOLD, the seed blooming into three circles
// on the splash's charcoal, ~1.1 s; a click skips it), then the 560 ms
// DISSOLVE over the title with the mark flying onto its logo spot. The title's
// entrance (the nav rising in) plays once per game start, from that reveal.
public class OriginTitleScreen extends OriginScreen {
	// Button ids: actionPerformed dispatches on the instance, not the id, but
	// distinct ids keep other mods' InitGuiEvent code from mistaking ours for
	// vanilla's (GuiMainMenu uses 0..6, 14 and 11/12 in demo mode).
	private static final int ID_BASE = 0x4F520;

	private GuiButton singleplayer, multiplayer, friends, mods, cosmetics, options, language, quit;
	// Static: the entrance plays once per game start, not on every return to the title.
	private static long openedAt = 0;
	// The startup intro (class comment): NONE, HOLD (the loading layer alone) or
	// DISSOLVE (the layer fading over the title); dissolveAt = its first frame (0 = not yet).
	private static final int NONE = 0, HOLD = 1, DISSOLVE = 2;
	private static int intro = NONE;
	private static long dissolveAt = 0;
	private boolean fallBack = false;
	private final List<GuiButton> nav = new ArrayList<>();
	private final List<int[]> navPos = new ArrayList<>();

	public OriginTitleScreen() {
		super("Origin");
	}

	@Override
	protected void init() {
		if (openedAt == 0) {           // first title of the game only -- a resize or return doesn't replay
			openedAt = com.origin.client.client.StartupTransition.clockMs();
			if (StartupTransition.claimIntro() && OriginScreenRenderer.isActive()) {
				intro = HOLD;
			}
		}
		nav.clear();
		navPos.clear();
		TitleLayout L = TitleLayout.of(width, height);

		singleplayer = add(0, I18n.format("menu.singleplayer"));
		multiplayer = add(1, I18n.format("menu.multiplayer"));
		friends = add(2, "Friends");
		mods = add(3, "Mods");
		cosmetics = add(4, "Cosmetics");
		options = add(5, I18n.format("menu.options"));
		language = add(6, I18n.format("options.language"));
		quit = add(7, I18n.format("menu.quit"));

		// Two full rows, one paired row, then Cosmetics full width under the pair.
		int half = (L.mainW - L.pairGap) / 2;
		place(singleplayer, L.mainX, L.mainTop, L.mainW, L.buttonH, true);
		place(multiplayer, L.mainX, L.mainTop + L.rowStep, L.mainW, L.buttonH, true);
		place(friends, L.mainX, L.mainTop + 2 * L.rowStep, half, L.buttonH, true);
		place(mods, L.mainX + half + L.pairGap, L.mainTop + 2 * L.rowStep, half, L.buttonH, true);
		place(cosmetics, L.mainX, L.mainTop + 3 * L.rowStep, L.mainW, L.buttonH, true);
		OriginButtonRenderer.setTitlePresentation(singleplayer, "@title-singleplayer", false);
		OriginButtonRenderer.setTitlePresentation(multiplayer, "@title-multiplayer", false);
		OriginButtonRenderer.setTitlePresentation(friends, "@user", false);
		OriginButtonRenderer.setTitlePresentation(mods, "@title-mods", false);
		OriginButtonRenderer.setTitlePresentation(cosmetics, "@shirt", false);

		// The small bottom-centre utility dock.
		GuiButton[] dock = {options, language, quit};
		int totalW = dock.length * L.dockSize + (dock.length - 1) * L.dockGap;
		int dx = (width - totalW) / 2;
		for (GuiButton b : dock) {
			place(b, dx, L.dockY, L.dockSize, L.dockSize, false);
			dx += L.dockSize + L.dockGap;
		}
		OriginButtonRenderer.setTitlePresentation(options, "@title-options", true);
		OriginButtonRenderer.setTitlePresentation(language, "@title-language", true);
		OriginButtonRenderer.setTitlePresentation(quit, "@title-quit", true);
		applyPositions();
	}

	private GuiButton add(int slot, String label) {
		GuiButton b = new GuiButton(ID_BASE + slot, 0, 0, 150, 20, label);
		buttonList.add(b);
		return b;
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

	/** The nav's staggered entrance rise on Y (the dock sits still), re-applied
	 *  every frame like the other versions' title mixins. */
	private void applyPositions() {
		long el = com.origin.client.client.StartupTransition.clockMs() - StartupTransition.titleIntroStart(openedAt);
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
		if (intro == HOLD) {
			if (OriginScreenRenderer.renderStartupLoading(g)) {
				if (OriginScreenRenderer.startupBloomed()) {
					intro = DISSOLVE;
				}
				return;   // the title waits under the layer
			}
			endIntro();
		}
		long fadeT = -1;
		if (intro == DISSOLVE) {
			long t = dissolveAt == 0 ? 0 : StartupTransition.clockMs() - dissolveAt;
			if (t >= StartupTransition.REVEAL_MS) {
				// The dissolve's last frame: end the layer BEFORE the title draws, or
				// the title's mark (hidden while the layer is up) blinks out for a
				// frame (1.21.1 b083908a).
				endIntro();
			} else {
				fadeT = t;
			}
		}
		if (!OriginScreenRenderer.renderTitleBackground(g)) {
			// Origin rendering failed this session: a plain frame now, vanilla's
			// GuiMainMenu on the next tick (tick() below).
			g.fill(0, 0, width, height, 0xFF000000);
			fallBack = true;
			return;
		}
		boolean hoveringClickable = false;
		for (GuiButton b : buttonList) {
			if (b.visible && mouseX >= b.xPosition && mouseY >= b.yPosition
					&& mouseX < b.xPosition + b.width && mouseY < b.yPosition + b.height) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(g, mouseX, mouseY, hoveringClickable);
		OriginScreenRenderer.renderTitleWordmark(g);
		OriginScreenRenderer.renderTitleAccountChip(g);
		applyPositions();
		super.render(g, mouseX, mouseY, partialTick);   // the buttons (skinned) + labels
		if (fadeT >= 0) {
			float alpha = (float) (1.0 - StartupTransition.ease(fadeT / (double) StartupTransition.REVEAL_MS));
			if (!OriginScreenRenderer.renderLoadingFade(g, alpha, true)) {
				endIntro();
			} else if (dissolveAt == 0) {
				// First dissolve frame: the layer still fully covers the title (whose
				// slow first frame is now out of the way). Both the dissolve and the
				// title's entrance count from here.
				dissolveAt = StartupTransition.clockMs();
				StartupTransition.titleRevealed();
			}
		}
	}

	private static void endIntro() {
		intro = NONE;
		OriginScreenRenderer.endStartupLayer();
	}

	// A click while the seed blooms skips straight to the dissolve (the buttons
	// under the layer are not on screen yet, so it never presses one).
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (intro == HOLD) {
			intro = DISSOLVE;
			return true;
		}
		return false;
	}

	// Leaving the title mid-intro (a click during the dissolve) ends the layer:
	// a later return shows the plain title.
	@Override
	public void removed() {
		if (intro != NONE) {
			endIntro();
		}
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
		} else if (button == friends) {
			mc.displayGuiScreen(new FriendsScreen(this));
		} else if (button == mods) {
			mc.displayGuiScreen(new OriginModMenuScreen());
		} else if (button == cosmetics) {
			mc.displayGuiScreen(new CosmeticsScreen(this));
		} else if (button == options) {
			mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
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
