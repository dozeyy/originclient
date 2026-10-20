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
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

// The Origin main menu on Minecraft's own buttons (Will, 2026-10-19: "use
// normal minecraft font ... normal buttons ... keep those custom buttons at the
// bottom but make them into the minecraft buttons ... keep the background and
// player in the top left and add the exit to the bottom"), ported from 1.21.1:
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
// Background: THIS version's own Mojang panorama (vanilla's draw, routed to
// Origin's one shared PanoramaRenderer so it keeps turning into the other
// menus), blurred and dimmed in place of vanilla's overlay blit. No version
// line, no splash. GEOMETRY LIVES IN ONE PLACE: TitleLayout. Vanilla's own
// widgets are only REPOSITIONED (clicks and actions untouched) and draw as
// stock Minecraft buttons; the icon row draws vanilla's button art with
// Origin's icon on top (OriginButtonRenderer.vanillaIconButton). Every icon
// square gets a one-line tooltip saying what it does (vanilla Tooltip); worded
// buttons get none -- their label already says it (Will, 2026-10-19). Mods /
// Friends / Cosmetics are Origin's, added as real widgets through
// ScreenInvoker. Mod Menu's own "Mods" button is hidden every frame (it is
// added by Mod Menu's screen event after our init hook, which also bumps
// buttons -- so positions are re-applied every frame). The copyright line is
// vanilla's, where vanilla puts it. Everything is fail-soft: a throw leaves
// vanilla's layout in place.
//
// 1.19.3 era: no GuiGraphics (Gfx over the PoseStack), no SpriteIconButton
// (Language / Accessibility are ImageButtons, told apart by their translation
// keys), no setHeight (AbstractWidgetAccessor), no LogoRenderer (render() draws
// the MINECRAFT logo with blitOutlineBlack + a 9-arg blit for the edition
// strip), and no renderPanorama / SplashRenderer -- render() calls
// this.panorama.render(FF) and blits the PANORAMA_OVERLAY directly; each
// @Redirect below matches only its own call shape in render() (javap-verified
// on this module's mapped jar). If one stops applying: panorama -> vanilla's
// own (a fresh spin, not shared); overlay -> no blur/dim/chip; logo ->
// vanilla's MINECRAFT logo; splash/version -> shown.
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
	// off -- every inject below no-ops so the stock title screen shows through.
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
			Button entry = Button.builder(Component.literal("Origin Mods"),
					b -> mc.setScreen(new OriginModMenuScreen())).bounds(4, 4, 80, 20).build();
			((ScreenInvoker) (Screen) (Object) this).originclient$addRenderableWidget(entry);
		} catch (Throwable ignored) {
			// fail-soft: the stock title stays as it is
		}
	}

	@Unique
	private static String originclient$translationKey(Component c) {
		return c != null && c.getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
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
	 *  room for its own (hidden) button -- which left a phantom gap in the list
	 *  until this. A handful of setX/setY calls a frame is nothing. */
	@Unique
	private void originclient$applyPositions() {
		// Counted from the startup reveal when that came later: the first init
		// happens behind the loading screen, where the rise would play unseen.
		long el = com.origin.client.client.StartupTransition.clockMs() - com.origin.client.client.StartupTransition.titleIntroStart(originclient$openedAt);
		for (int i = 0; i < originclient$nav.size(); i++) {
			int[] t = originclient$navPos.get(i);
			double e = el > com.origin.client.client.StartupTransition.TITLE_ENTRANCE_MS ? 1.0 : originclient$ease((el - 120 - i * 55) / 380.0);
			AbstractWidget w = originclient$nav.get(i);
			w.setX(t[0]);
			w.setY(t[1] + (int) Math.round((1.0 - e) * 8));
		}
		for (int i = 0; i < originclient$icons.size(); i++) {
			int[] t = originclient$iconPos.get(i);
			originclient$icons.get(i).setX(t[0]);
			originclient$icons.get(i).setY(t[1]);
		}
	}

	// The panorama: vanilla's own draw, through Origin's one shared renderer
	// (OriginScreenRenderer.renderPanorama) so the scene keeps turning from the
	// title into the other menus and back. Origin style off or the renderer
	// broken: the screen's own panorama, untouched.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
	private void originclient$sharedPanorama(PanoramaRenderer instance, float partialTick, float alpha) {
		if (originclient$origin() && OriginScreenRenderer.menuBackdropActive()) {
			try {
				OriginScreenRenderer.renderPanorama(partialTick, alpha);
				return;
			} catch (Throwable ignored) {
				// fall through to vanilla's own panorama
			}
		}
		instance.render(partialTick, alpha);
	}

	// One Gfx reused across frames (it only wraps a PoseStack reference): the title
	// screen hands the same PoseStack every frame, so this allocates once, not per
	// frame. Rebuilt only if the PoseStack instance ever differs.
	private static Gfx originclient$gfxCache;

	private static Gfx originclient$gfx(PoseStack poseStack) {
		Gfx g = originclient$gfxCache;
		if (g == null || g.pose() != poseStack) {
			g = new Gfx(poseStack);
			originclient$gfxCache = g;
		}
		return g;
	}

	// The logo draws in raw GUI space on a fresh identity pose; one is kept and
	// reused with its Gfx. clear() is false if a draw that threw left a push
	// unmatched, then a clean stack replaces it.
	private static PoseStack originclient$logoPose;
	private static Gfx originclient$logoGfxCache;

	private static Gfx originclient$logoGfx() {
		PoseStack p = originclient$logoPose;
		if (p == null || !p.clear()) {
			p = new PoseStack();
			originclient$logoPose = p;
			originclient$logoGfxCache = new Gfx(p);
		}
		return originclient$logoGfxCache;
	}

	// The PANORAMA_OVERLAY blit right after the panorama (the only 10-arg blit in
	// render()): Origin's blur + a light dim + vignette instead (1.21.1 adds the
	// same at renderPanorama TAIL), then the account chip, so the chip sits over
	// the backdrop and under the buttons. If the backdrop fails, vanilla's overlay
	// draws as before.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIFFIIII)V"))
	private void originclient$backdropOver(PoseStack poseStack, int x, int y, int w, int h,
										   float u, float v, int uW, int vH, int texW, int texH) {
		if (originclient$origin() && OriginScreenRenderer.isActive()) {
			Gfx g = originclient$gfx(poseStack);
			if (OriginScreenRenderer.renderTitleBackdropOver((Screen) (Object) this, g, Minecraft.getInstance().getDeltaFrameTime())) {
				OriginScreenRenderer.renderTitleAccountChip(g);
				return;
			}
		}
		GuiComponent.blit(poseStack, x, y, w, h, u, v, uW, vH, texW, texH);
	}

	// Whether the Origin wordmark drew THIS frame -- read by the edition-strip
	// redirect just below, which always runs after this one in render(), so the
	// "Java Edition" strip disappears and reappears in lockstep with the logo.
	@Unique
	private boolean originclient$wordmarkDrawn;

	// The vanilla "Minecraft" wordmark: render() draws it via the inherited
	// blitOutlineBlack, from an if/else (easter-egg / normal), so this matches both
	// call sites but exactly one executes per frame. The logo draws in raw GUI
	// space (no pose translation around it), so a fresh PoseStack is the right
	// pose for the Origin wordmark.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blitOutlineBlack(IILjava/util/function/BiConsumer;)V"))
	private void originclient$logo(TitleScreen instance, int x, int y, BiConsumer<Integer, Integer> draw) {
		originclient$wordmarkDrawn = originclient$origin()
				&& OriginScreenRenderer.renderTitleWordmark(originclient$logoGfx());
		if (!originclient$wordmarkDrawn) {
			instance.blitOutlineBlack(x, y, draw);
		}
	}

	// The "Java Edition" strip blitted right under the wordmark -- the only
	// 9-arg blit in render(). Suppressed exactly when the Origin wordmark drew,
	// so a wordmark failure brings back the complete vanilla logo, not half.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIFFIIII)V"))
	private void originclient$noEditionStrip(PoseStack poseStack, int x, int y,
											 float u, float v, int width, int height, int texWidth, int texHeight) {
		if (!originclient$wordmarkDrawn) {
			GuiComponent.blit(poseStack, x, y, u, v, width, height, texWidth, texHeight);
		}
	}

	// No yellow splash text (Origin style only). Pre-1.20 there is no
	// SplashRenderer: the splash String is drawn by the single drawCenteredString
	// in render() (its pose push/rotate/pop around the call stays balanced).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawCenteredString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noSplash(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawCenteredString(poseStack, font, text, x, y, color);
		}
	}

	// No bottom version line (the only drawString in render()).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noVersion(PoseStack poseStack, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			GuiComponent.drawString(poseStack, font, text, x, y, color);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$layout(CallbackInfo ci) {
		if (!originclient$origin()) {
			originclient$vanillaEntry();
			return;
		}
		Screen self = (Screen) (Object) this;
		if (originclient$openedAt == 0) {           // first init only -- a resize re-init doesn't replay
			originclient$openedAt = com.origin.client.client.StartupTransition.clockMs();
		}
		for (GuiEventListener child : self.children()) {
			// Any Mod Menu "Mods" button already present: hidden. The copyright line
			// (PlainTextButton) stays where vanilla puts it, bottom-right.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget widget) {
				widget.visible = false;
				widget.active = false;
			}
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			originclient$modsNav = Button.builder(Component.literal("Mods"),
					b -> mc.setScreen(new OriginModMenuScreen())).bounds(0, 0, 150, 20).build();
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$modsNav);
			originclient$cosmeticsNav = Button.builder(Component.literal("Cosmetics"),
					b -> mc.setScreen(new CosmeticsScreen(self))).bounds(0, 0, 20, 20).build();
			((ScreenInvoker) self).originclient$addRenderableWidget(originclient$cosmeticsNav);
			originclient$friendsNav = Button.builder(Component.literal("Friends"),
					b -> mc.setScreen(new FriendsScreen(self))).bounds(0, 0, 20, 20).build();
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
		return key.equals(originclient$translationKey(w.getMessage()))
				|| net.minecraft.client.resources.language.I18n.get(key).equals(w.getMessage().getString());
	}

	/** Icon-only square: vanilla's button with the Origin symbol, and what it does as its tooltip. */
	@Unique
	private static void originclient$iconOnly(AbstractWidget w, String icon, String tip) {
		OriginButtonRenderer.setTitlePresentation(w, icon, true);
		if (w != null) {
			w.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tip)));
		}
	}

	/**
	 * The layout: Singleplayer and Multiplayer as full rows, Options | Mods as a
	 * pair, and a centred row of 20px vanilla squares at the bottom (Realms,
	 * Friends, Cosmetics, Language, Accessibility, Quit last). Every vanilla
	 * button is found by its label's translation key; Singleplayer and
	 * Multiplayer always take the first two (full-width) rows, so a mod's button
	 * that sits above them can't take one of their slots (review fix,
	 * 2026-10-19). Anything else (demo mode, another mod's buttons) follows in
	 * y-order instead of vanishing.
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
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)
						|| originclient$isForeignMods(child) || child instanceof PlainTextButton) {
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
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			AbstractWidget single = null, multi = null, realms = null, options = null, quit = null;
			for (AbstractWidget w : main) {
				if (single == null && (originclient$is(w, "menu.singleplayer") || originclient$is(w, "menu.playdemo"))) single = w;
				else if (multi == null && originclient$is(w, "menu.multiplayer")) multi = w;
				else if (realms == null && originclient$is(w, "menu.online")) realms = w;
				else if (options == null && originclient$is(w, "menu.options")) options = w;
				else if (quit == null && originclient$is(w, "menu.quit")) quit = w;
			}
			List<AbstractWidget> full = new ArrayList<>();
			if (single != null) full.add(single);
			if (multi != null) full.add(multi);
			int lead = full.size();   // the buttons actually found: demo mode and mods can leave 0, 1 or 2
			for (AbstractWidget w : main) {
				if (w != single && w != multi && w != realms && w != options && w != quit) {
					full.add(w);
				}
			}

			// Every button in `full` is one row (vanilla's two, plus any another mod
			// added), then the Options | Mods pair: the layout sizes the stack to the
			// real count so it never runs into the icon row. If even that can't fit
			// (many added buttons on a small GUI), the added ones pair up two to a
			// row, as vanilla pairs Options | Quit.
			boolean pair = options != null || originclient$modsNav != null;
			TitleLayout.rows(full.size() + (pair ? 1 : 0));
			TitleLayout L = TitleLayout.of(self.width, self.height);
			int extra = full.size() - lead;
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
				options.setMessage(Component.translatable("options.title"));   // "Options", sentence case
			}
			// Worded buttons say what they do already: no tooltip (Will, 2026-10-19:
			// "dont have the hover tells what it dos for the worded ones only the icons").
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
		w.setX(x);
		w.setY(y);
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
