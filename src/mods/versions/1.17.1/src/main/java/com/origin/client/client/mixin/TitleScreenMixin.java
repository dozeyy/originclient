package com.origin.client.client.mixin;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.CosmeticsScreen;
import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.render.TitleLayout;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// The Origin main menu on Minecraft's own buttons (Will, 2026-10-19), ported
// from 1.21.1:
//
//   [head Player]
//
//                            (logo)
//                            ORIGIN
//                 [        Singleplayer        ]   200 x 20, vanilla
//                 [        Multiplayer         ]
//                 [   Options    ][    Mods    ]
//
//     [realms][friends][cosmetics][lang][access][quit]   20 x 20 vanilla squares
//                                  Copyright Mojang AB. Do not distribute!
//
// Background: THIS version's own Mojang panorama, blurred (MenuBlur: this era has
// no vanilla menu blur), dimmed and vignetted. No version line, no splash.
// GEOMETRY LIVES IN ONE PLACE: TitleLayout. Vanilla's own widgets are only
// REPOSITIONED (clicks and actions untouched) and draw as stock Minecraft
// buttons; the icon row draws widgets.png with Origin's icon on top
// (OriginButtonRenderer.vanillaIconButton). Every icon square says what it does
// on hover; worded buttons get no hover text (their label says it). Mods /
// Friends / Cosmetics are Origin's, added as real widgets through ScreenInvoker.
// Mod Menu's own "Mods" button is hidden every frame (it is added by Mod Menu's
// screen event after our init hook, which also bumps buttons -- so positions
// are re-applied every frame). The copyright line is vanilla's own drawString
// (no PlainTextButton before 1.18), where vanilla puts it. Everything is fail-soft: a throw leaves vanilla's
// layout in place.
//
// 1.17.1 era (javap against the mapped 1.17.1 jar): no GuiGraphics (Gfx over the
// PoseStack), widget x/y are public fields, no setHeight (AbstractWidgetAccessor),
// Button has no builder, no Tooltip API (the icon tooltips are drawn at render
// TAIL with Screen.renderTooltip), the Language / Accessibility icons are
// ImageButtons told apart by their TranslatableComponent keys, and there is no
// renderPanorama / LogoRenderer / SplashRenderer -- render() calls
// this.panorama.render(FF) directly, blits the PANORAMA_OVERLAY, draws the logo
// with blitOutlineBlack and the "Java Edition" badge with an 8-arg blit, the
// splash with drawCenteredString and the version with drawString. Each of those
// is a @Redirect below.
// priority 2000: Origin's re-skin wins over other title-screen mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	@Unique
	private Button originclient$modsNav;
	@Unique
	private Button originclient$cosmeticsNav;
	@Unique
	private Button originclient$friendsNav;
	// Static: the menu's entrance plays once per game start (Will 2026-10-18,
	// "snappy"), not again every time the player comes back from a world.
	@Unique
	private static long originclient$openedAt = 0;
	@Unique
	private final List<AbstractWidget> originclient$nav = new ArrayList<>();
	@Unique
	private final List<int[]> originclient$navPos = new ArrayList<>();
	@Unique
	private final List<AbstractWidget> originclient$icons = new ArrayList<>();
	@Unique
	private final List<int[]> originclient$iconPos = new ArrayList<>();

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the entire re-skin
	// off — every inject below no-ops so the stock title screen shows through.
	private static boolean originclient$origin() {
		return !OriginStyle.vanillaTitle();
	}

	/** The stock title screen has no way into the mod menu (Right Shift does nothing
	 *  while a screen is open), so a plain vanilla-sized button in the top-left corner
	 *  opens it. It is an ordinary Button. */
	@Unique
	private void originclient$vanillaEntry() {
		try {
			Minecraft mc = Minecraft.getInstance();
			Button entry = new Button(4, 4, 80, 20, new TextComponent("Origin Mods"),
					b -> mc.setScreen(new OriginModMenuScreen()));
			((ScreenInvoker) (Screen) (Object) this).originclient$addRenderableWidget(entry);
		} catch (Throwable ignored) {
			// fail-soft: the stock title stays as it is
		}
	}

	/** The translation key of a button label (pre-1.19 the component itself is the
	 *  TranslatableComponent; TranslatableContents is 1.19+). */
	@Unique
	private static String originclient$translationKey(Component c) {
		return c instanceof TranslatableComponent ? ((TranslatableComponent) c).getKey() : "";
	}

	@Unique
	private static double originclient$ease(double raw) {
		return OriginTheme.easeOut(Math.max(0.0, Math.min(1.0, raw)));
	}

	/** Mod Menu's title button (any "Mods" button that isn't Origin's own). */
	@Unique
	private boolean originclient$isForeignMods(GuiEventListener child) {
		return child instanceof Button b && b != originclient$modsNav
				&& "mods".equalsIgnoreCase(b.getMessage().getString());
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			// Mod Menu adds its "Mods" button through a screen event that fires
			// AFTER our init hook, so hide it here, every frame.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget w && w.visible) {
				w.visible = false;
				w.active = false;
			}
		}
		originclient$applyPositions();
	}

	/** Re-applies Origin's positions to the nav + icon widgets EVERY FRAME, with
	 *  the entrance rise on Y. Every frame because Mod Menu's screen event fires
	 *  after our init and "bumps" every button below Realms down by 24 to make
	 *  room for its own (hidden) button — which left a phantom gap in the list
	 *  until this. A handful of field writes a frame is nothing. */
	@Unique
	private void originclient$applyPositions() {
		// Counted from the startup reveal when that came later: the first init
		// happens behind the loading screen, where the rise would play unseen.
		long el = com.origin.client.client.StartupTransition.clockMs() - com.origin.client.client.StartupTransition.titleIntroStart(originclient$openedAt);
		for (int i = 0; i < originclient$nav.size(); i++) {
			int[] t = originclient$navPos.get(i);
			double e = el > com.origin.client.client.StartupTransition.TITLE_ENTRANCE_MS ? 1.0 : originclient$ease((el - 120 - i * 55) / 380.0);
			AbstractWidget w = originclient$nav.get(i);
			w.x = t[0];
			w.y = t[1] + (int) Math.round((1.0 - e) * 8);
		}
		for (int i = 0; i < originclient$icons.size(); i++) {
			int[] t = originclient$iconPos.get(i);
			originclient$icons.get(i).x = t[0];
			originclient$icons.get(i).y = t[1];
		}
	}

	// The panorama: render() calls this.panorama.render(partialTick, alpha)
	// directly. Origin draws the ONE shared panorama instead (so the scene keeps
	// turning, unbroken, from the title into Options and back), then the blur +
	// a light dim + vignette over it, then the account chip (drawn here, not at
	// render HEAD, so the panorama doesn't cover it). If the Origin backdrop
	// fails, vanilla's own panorama draws instead.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
	private void originclient$panorama(PanoramaRenderer instance, float partialTick, float alpha,
									   PoseStack poseStack, int mouseX, int mouseY, float renderPartialTick) {
		if (!originclient$origin() || !OriginScreenRenderer.menuBackdropActive()) {
			instance.render(partialTick, alpha);
			return;
		}
		try {
			OriginScreenRenderer.renderPanorama(partialTick, alpha);
		} catch (Throwable t) {
			OriginScreenRenderer.panoramaFailed(t);
			instance.render(partialTick, alpha);
			return;
		}
		Gfx g = new Gfx(poseStack);
		OriginScreenRenderer.renderTitleBackdropOver((Screen) (Object) this, g, renderPartialTick);
		OriginScreenRenderer.renderTitleAccountChip(g);
	}

	// The PANORAMA_OVERLAY vignette blit right after the panorama — the only
	// 10-arg blit in render(). Origin draws its own vignette in the backdrop.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIFFIIII)V"))
	private void originclient$suppressOverlay(PoseStack poseStack, int x, int y, int w, int h,
											  float u, float v, int uW, int vH, int texW, int texH) {
		if (!originclient$origin() || !OriginScreenRenderer.menuBackdropActive()) {
			GuiComponent.blit(poseStack, x, y, w, h, u, v, uW, vH, texW, texH);
		}
	}

	// The vanilla logo: both blitOutlineBlack calls (normal + "Minceraft"
	// easter-egg branch) share one shape, so this redirect wraps both; only one
	// runs per frame, so the Origin logo + wordmark draw exactly once. The
	// enclosing render() args are appended to the handler (Mixin arg capture) to
	// reach the frame's PoseStack. Fail-soft: if the logo can't draw (or the
	// style is Vanilla), vanilla's own logo returns.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blitOutlineBlack(IILjava/util/function/BiConsumer;)V"))
	private void originclient$logo(TitleScreen instance, int x, int y, java.util.function.BiConsumer<Integer, Integer> draw,
								   PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(new Gfx(poseStack))) {
			instance.blitOutlineBlack(x, y, draw);
		}
	}

	// The "Java Edition" badge blit straight after the logo — the only 8-arg
	// blit in render(). Gated like the logo so it returns with vanilla's logo
	// whenever the Origin path is off or unhealthy.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIFFIIII)V"))
	private void originclient$suppressEdition(PoseStack poseStack, int x, int y, float u, float v,
											  int w, int h, int texW, int texH) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.blit(poseStack, x, y, u, v, w, h, texW, texH);
		}
	}

	// No yellow splash text (Origin style only): the single drawCenteredString in
	// render() (its pose push/rotate/pop around the call stays balanced).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noSplash(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawCenteredString(poseStack, font, text, x, y, color);
		}
	}

	// No bottom version line. 1.17.1's render() has two drawString calls (javap):
	// ordinal 0 is the version line at x = 2, ordinal 1 the copyright line at
	// copyrightX -- only the first is bound, so the copyright stays bottom-right.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
			ordinal = 0))
	private void originclient$noVersion(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawString(poseStack, font, text, x, y, color);
		}
	}

	// The icon squares' hover line (1.21.1 uses vanilla's Tooltip, 1.19.3+). Drawn
	// after the widgets with this version's own Screen.renderTooltip, so it looks
	// exactly like vanilla's. Hover state is the widget's own (set in its render
	// this frame, or keyboard focus), as vanilla's Tooltip uses it.
	@Inject(method = "render", at = @At("TAIL"))
	private void originclient$iconTooltip(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		try {
			for (int i = 0; i < originclient$icons.size(); i++) {
				AbstractWidget w = originclient$icons.get(i);
				if (w.visible && w.isHovered()) {
					Component tip = OriginButtonRenderer.titleTooltip(w);
					if (tip != null) {
						((Screen) (Object) this).renderTooltip(poseStack, tip, mouseX, mouseY);
					}
					return;
				}
			}
		} catch (Throwable ignored) {
			// fail-soft: no hover line
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$layout(CallbackInfo ci) {
		if (!originclient$origin()) {
			originclient$vanillaEntry();
			return;
		}
		Screen self = (Screen) (Object) this;
		if (originclient$openedAt == 0) {           // first init only — a resize re-init doesn't replay
			originclient$openedAt = com.origin.client.client.StartupTransition.clockMs();
		}
		for (GuiEventListener child : self.children()) {
			// Any Mod Menu "Mods" button already present: hidden. The copyright line
			// (a drawString in render() this era) stays where vanilla puts it.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget widget) {
				widget.visible = false;
				widget.active = false;
			}
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			// No Button.builder before 1.19.3: the plain (x, y, w, h, label, onPress) ctor.
			originclient$modsNav = new Button(0, 0, 150, 20, new TextComponent("Mods"),
					b -> mc.setScreen(new OriginModMenuScreen()));
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$modsNav);
			originclient$cosmeticsNav = new Button(0, 0, 150, 20, new TextComponent("Cosmetics"),
					b -> mc.setScreen(new CosmeticsScreen(self)));
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$cosmeticsNav);
			originclient$friendsNav = new Button(0, 0, 20, 20, new TextComponent("Friends"),
					b -> mc.setScreen(new FriendsScreen(self)));
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$friendsNav);
		} catch (Throwable ignored) {
			originclient$modsNav = null;
			originclient$cosmeticsNav = null;
			originclient$friendsNav = null;
		}
		originclient$layoutCentered(self);
		OriginForeignWidgets.avoidOverlap(self);
	}

	/** Is this vanilla button the one whose label is the given translation key's text? */
	@Unique
	private static boolean originclient$is(AbstractWidget w, String key) {
		return net.minecraft.client.resources.language.I18n.get(key).equals(w.getMessage().getString());
	}

	/** Icon-only square: vanilla's button with the Origin symbol, and what it does on hover. */
	@Unique
	private static void originclient$iconOnly(AbstractWidget w, String icon, String tip) {
		OriginButtonRenderer.setTitlePresentation(w, icon, true, new TextComponent(tip));
	}

	/**
	 * The layout: Singleplayer and Multiplayer as full rows, Options | Mods as a
	 * pair, and a centred row of 20px vanilla squares at the bottom (Realms,
	 * Friends, Cosmetics, Language, Accessibility, Quit last). Buttons are found by
	 * their vanilla label (translation key); anything else (another mod's button,
	 * demo mode's reset) gets a full row after them in screen order.
	 */
	@Unique
	private void originclient$layoutCentered(Screen self) {
		try {
			originclient$nav.clear();
			originclient$navPos.clear();
			originclient$icons.clear();
			originclient$iconPos.clear();
			List<AbstractWidget> main = new ArrayList<>();
			List<AbstractWidget> images = new ArrayList<>();
			for (GuiEventListener child : self.children()) {
				// ImageButton extends Button this era (the copyright is not a widget before 1.18).
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)
						|| originclient$isForeignMods(child)) {
					continue;
				}
				if (w == originclient$modsNav || w == originclient$cosmeticsNav || w == originclient$friendsNav) {
					continue;
				}
				if (child instanceof ImageButton) {
					images.add(w);
				} else if (child instanceof Button) {
					main.add(w);
				}
			}
			if (main.isEmpty()) {
				return;
			}
			main.sort(java.util.Comparator.comparingInt((AbstractWidget w) -> w.y).thenComparingInt(w -> w.x));
			AbstractWidget realms = null, options = null, quit = null;
			for (AbstractWidget w : main) {
				if (realms == null && originclient$is(w, "menu.online")) realms = w;
				else if (options == null && originclient$is(w, "menu.options")) options = w;
				else if (quit == null && originclient$is(w, "menu.quit")) quit = w;
			}
			// Singleplayer (or demo mode's Play Demo World) and Multiplayer lead the
			// stack, found by their label like Options and Quit, so a button another
			// mod placed above them can never take their full-width slots.
			AbstractWidget play = null, online = null;
			for (AbstractWidget w : main) {
				if (play == null && (originclient$is(w, "menu.singleplayer") || originclient$is(w, "menu.playdemo"))) play = w;
				else if (online == null && originclient$is(w, "menu.multiplayer")) online = w;
			}
			List<AbstractWidget> full = new ArrayList<>();
			if (play != null) full.add(play);
			if (online != null) full.add(online);
			int lead = full.size();
			for (AbstractWidget w : main) {
				if (w != realms && w != options && w != quit && w != play && w != online) {
					full.add(w);   // the rest (other mods' buttons, demo's reset) in screen order
				}
			}

			// Every button in `full` is one row (vanilla's two, plus any another mod
			// added), then the Options | Mods pair: the layout sizes the stack to the
			// real count so it never runs into the icon row. If even that can't fit
			// (many added buttons on a small GUI), the added ones pair up two to a
			// row, as vanilla pairs Options | Quit.
			boolean pair = options != null || originclient$modsNav != null;
			int extra = full.size() - lead;
			TitleLayout.rows(full.size() + (pair ? 1 : 0));
			TitleLayout L = TitleLayout.of(self.width, self.height);
			boolean halves = !L.fits && extra > 1;
			if (halves) {
				TitleLayout.rows(lead + (extra + 1) / 2 + (pair ? 1 : 0));
				L = TitleLayout.of(self.width, self.height);
			}
			int half = (L.mainW - L.pairGap) / 2;
			int y = L.mainTop;
			for (int i = 0; i < full.size(); i++) {
				AbstractWidget w = full.get(i);
				boolean right = halves && i >= lead && (i - lead) % 2 == 1;
				boolean alone = !halves || i < lead || (!right && i == full.size() - 1);
				if (alone) {
					originclient$place(w, L.mainX, y, L.mainW, L.buttonH, true);
				} else {
					originclient$place(w, right ? L.mainX + L.mainW - half : L.mainX, y, half, L.buttonH, true);
				}
				if (alone || right) {
					y += L.rowStep;
				}
			}
			if (options != null) {
				options.setMessage(new TranslatableComponent("options.title"));   // "Options", no ellipsis
			}
			// Worded buttons say what they do already: no hover text (Will, 2026-10-19).
			if (options != null && originclient$modsNav != null) {
				originclient$place(options, L.mainX, y, half, L.buttonH, true);
				originclient$place(originclient$modsNav, L.mainX + L.mainW - half, y, half, L.buttonH, true);
			} else if (options != null || originclient$modsNav != null) {
				originclient$place(options != null ? options : originclient$modsNav, L.mainX, y, L.mainW, L.buttonH, true);
			}

			// Bottom row of vanilla squares, Quit at its end.
			List<AbstractWidget> dock = new ArrayList<>();
			if (realms != null) {
				originclient$iconOnly(realms, "@title-realms", "Play on Minecraft Realms");
				dock.add(realms);
			}
			if (originclient$friendsNav != null) {
				originclient$iconOnly(originclient$friendsNav, "@title-multiplayer", "Friends: see who is online and join them");
				dock.add(originclient$friendsNav);
			}
			if (originclient$cosmeticsNav != null) {
				originclient$iconOnly(originclient$cosmeticsNav, "@shirt", "Cosmetics: capes and more for your player");
				dock.add(originclient$cosmeticsNav);
			}
			for (AbstractWidget icon : images) {
				// narrator.button.language / narrator.button.accessibility
				String key = originclient$translationKey(icon.getMessage());
				if (key.endsWith(".language")) {
					originclient$iconOnly(icon, "@title-language", "Change the game's language");
				} else if (key.endsWith(".accessibility")) {
					originclient$iconOnly(icon, "@title-accessibility", "Accessibility settings");
				}
				dock.add(icon);
			}
			if (quit != null) {
				originclient$iconOnly(quit, "@title-quit", "Quit the game");
				dock.add(quit);
			}
			int totalW = dock.size() * L.dockSize + Math.max(0, dock.size() - 1) * L.dockGap;
			int dx = (self.width - totalW) / 2;
			for (AbstractWidget w : dock) {
				originclient$place(w, dx, L.dockY, L.dockSize, L.dockSize, false);
				dx += L.dockSize + L.dockGap;
			}
		} catch (Throwable ignored) {
		}
	}

	/** Sets a widget's box and records it for the per-frame re-apply (nav rows rise in once). */
	@Unique
	private void originclient$place(AbstractWidget w, int x, int y, int width, int height, boolean nav) {
		w.x = x;
		w.y = y;
		w.setWidth(width);
		((AbstractWidgetAccessor) w).originclient$setHeight(height);
		if (nav) {
			originclient$nav.add(w);
			originclient$navPos.add(new int[]{x, y});
		} else {
			originclient$icons.add(w);
			originclient$iconPos.add(new int[]{x, y});
		}
	}
}
