package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.CosmeticsScreen;
import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.render.TitleLayout;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// The Origin main menu, clean look (Will's approved mockup, 2026-10-19):
//
//   [head Player]                                         [quit]
//
//                            (logo)
//                            ORIGIN
//                     [    Singleplayer    ]
//                     [    Multiplayer     ]
//                     [ Options ][  Mods   ]
//
//              (realms)(friends)(cosmetics)(lang)(access)
//                                  Copyright Mojang AB. Do not distribute!
//
// Background: THIS version's own Mojang panorama (vanilla draws it), blurred
// by vanilla's blur and dimmed (renderPanorama TAIL). No version line, no
// splash. GEOMETRY LIVES IN ONE PLACE: TitleLayout. Vanilla's own widgets are
// only REPOSITIONED (clicks and actions untouched) and drawn by
// OriginButtonRenderer's clean skin; Mods / Friends / Cosmetics are Origin's,
// added as real widgets through ScreenInvoker. Mod Menu's own "Mods" button is
// hidden every frame (it is added by Mod Menu's screen event after our init
// hook, which also bumps buttons -- so positions are re-applied every frame).
// Everything is fail-soft: a throw leaves vanilla's layout in place.
//
// All injection targets confirmed via javap against the mapped 1.21.1 jar.
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
	// The answer comes from OriginStyle (the one predicate every restyle asks).
	private static boolean originclient$origin() {
		return !OriginStyle.vanillaTitle();
	}

	/** The stock title screen has no way into the mod menu (Right Shift does nothing
	 *  while a screen is open), so a plain vanilla-sized button in the top-left corner
	 *  opens it. Whatever Button Style says, it is an ordinary Button. */
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
	private void originclient$background(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
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

	// Vanilla draws THIS version's own panorama (TitleScreen.renderPanorama,
	// javap: PANORAMA.render(g, width, height, panoramaFade, pt)); Origin adds
	// vanilla's blur + a light dim + vignette right after it, then the account
	// chip (drawn here, not at render HEAD, so the panorama doesn't cover it).
	// The blur follows Accessibility > Menu Background Blurriness. If the
	// backdrop fails, the plain vanilla panorama simply stays.
	@Inject(method = "renderPanorama", at = @At("TAIL"))
	private void originclient$blurPanorama(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			return;
		}
		OriginScreenRenderer.renderTitleBackdropOver((Screen) (Object) this, guiGraphics, partialTick);
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
	}

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$origin() && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V"))
	private void originclient$logo(LogoRenderer instance, GuiGraphics guiGraphics, int screenWidth, float alpha) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(guiGraphics)) {
			instance.renderLogo(guiGraphics, screenWidth, alpha);
		}
	}

	// No yellow splash text (Origin style only).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;I)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphics guiGraphics, int screenWidth, Font font, int color) {
		if (!originclient$origin()) {
			instance.render(guiGraphics, screenWidth, font, color);
		}
	}

	// No bottom version line.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
	private int originclient$noVersion(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		return originclient$origin() ? 0 : instance.drawString(font, text, x, y, color);
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
			// (PlainTextButton) stays bottom-right, as in the mockup.
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
					b -> mc.setScreen(new CosmeticsScreen(self))).bounds(0, 0, 150, 20).build();
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
		return net.minecraft.client.resources.language.I18n.get(key).equals(w.getMessage().getString());
	}

	/** Icon-only square: the Origin symbol, the label as its hover tooltip. */
	@Unique
	private static void originclient$iconOnly(AbstractWidget w, String icon) {
		OriginButtonRenderer.setTitlePresentation(w, icon, true);
		if (w.getTooltip() == null) {
			w.setTooltip(net.minecraft.client.gui.components.Tooltip.create(w.getMessage()));
		}
	}

	/**
	 * The mockup layout: Singleplayer and Multiplayer as full rows, Options | Mods
	 * as a pair, a centred row of 16px icon squares at the bottom (Realms,
	 * Friends, Cosmetics, Language, Accessibility) and Quit as a square in the
	 * top-right corner. Buttons are found by their vanilla label (translation
	 * key) with the old y-order as the fallback, so demo mode or an extra vanilla
	 * button still lands in the stack instead of vanishing.
	 */
	@Unique
	private void originclient$layoutCentered(Screen self) {
		try {
			originclient$nav.clear();
			originclient$navPos.clear();
			originclient$icons.clear();
			originclient$iconPos.clear();
			List<AbstractWidget> main = new ArrayList<>();
			List<AbstractWidget> sprites = new ArrayList<>();
			for (GuiEventListener child : self.children()) {
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)
						|| originclient$isForeignMods(child) || child instanceof PlainTextButton) {
					continue;
				}
				if (w == originclient$modsNav || w == originclient$cosmeticsNav || w == originclient$friendsNav) {
					continue;
				}
				if (child instanceof net.minecraft.client.gui.components.SpriteIconButton) {
					sprites.add(w);
				} else if (child instanceof Button) {
					main.add(w);
				}
			}
			if (main.isEmpty()) {
				return;
			}
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			AbstractWidget realms = null, options = null, quit = null;
			for (AbstractWidget w : main) {
				if (realms == null && originclient$is(w, "menu.online")) realms = w;
				else if (options == null && originclient$is(w, "menu.options")) options = w;
				else if (quit == null && originclient$is(w, "menu.quit")) quit = w;
			}
			List<AbstractWidget> full = new ArrayList<>(main);
			full.remove(realms);
			full.remove(options);
			full.remove(quit);

			TitleLayout L = TitleLayout.of(self.width, self.height);
			int y = L.mainTop;
			for (AbstractWidget w : full) {
				originclient$place(w, L.mainX, y, L.mainW, L.buttonH, true);
				y += L.rowStep;
			}
			int half = (L.mainW - L.pairGap) / 2;
			if (options != null) {
				options.setMessage(Component.translatable("options.title"));   // "Options", sentence case
			}
			if (options != null && originclient$modsNav != null) {
				originclient$place(options, L.mainX, y, half, L.buttonH, true);
				originclient$place(originclient$modsNav, L.mainX + L.mainW - half, y, half, L.buttonH, true);
			} else if (options != null || originclient$modsNav != null) {
				originclient$place(options != null ? options : originclient$modsNav, L.mainX, y, L.mainW, L.buttonH, true);
			}

			// Bottom icon row.
			List<AbstractWidget> dock = new ArrayList<>();
			if (realms != null) {
				originclient$iconOnly(realms, "@title-realms");
				dock.add(realms);
			}
			if (originclient$friendsNav != null) {
				originclient$iconOnly(originclient$friendsNav, "@title-multiplayer");
				dock.add(originclient$friendsNav);
			}
			if (originclient$cosmeticsNav != null) {
				originclient$iconOnly(originclient$cosmeticsNav, "@shirt");
				dock.add(originclient$cosmeticsNav);
			}
			for (AbstractWidget icon : sprites) {
				ResourceLocation sprite = ((SpriteIconButtonAccessor) icon).originclient$sprite();
				String path = sprite.getPath();
				if (path.endsWith("language")) {
					OriginButtonRenderer.setTitlePresentation(icon, "@title-language", true);
				} else if (path.endsWith("accessibility")) {
					OriginButtonRenderer.setTitlePresentation(icon, "@title-accessibility", true);
				}
				dock.add(icon);
			}
			// Button Style = Minecraft: stock text buttons need a width their label fits in.
			boolean plain = OriginStyle.vanillaButtons();
			int[] dockW = new int[dock.size()];
			int totalW = Math.max(0, dock.size() - 1) * L.dockGap;
			for (int i = 0; i < dock.size(); i++) {
				dockW[i] = plain && !(dock.get(i) instanceof net.minecraft.client.gui.components.SpriteIconButton)
						? 74 : L.dockSize;
				totalW += dockW[i];
			}
			int dx = (self.width - totalW) / 2;
			for (int i = 0; i < dock.size(); i++) {
				originclient$place(dock.get(i), dx, L.dockY, dockW[i], L.dockSize, false);
				dx += dockW[i] + L.dockGap;
			}

			// Copyright line (legally required, stays): quiet grey, on the corner inset
			// instead of hard against the window edge. Hover still shows vanilla's
			// underlined white (PlainTextButton keeps its own underlined copy).
			for (GuiEventListener child : self.children()) {
				if (child instanceof PlainTextButton c && c.visible) {
					String text = c.getMessage().getString();
					c.setMessage(Component.literal(text).withStyle(st -> st.withColor(OriginTheme.CLEAN_TEXT_MUTED & 0xFFFFFF)));
					int cw = Minecraft.getInstance().font.width(text);
					c.setWidth(cw);
					originclient$place(c, self.width - TitleLayout.CORNER - cw, self.height - TitleLayout.CORNER - 8,
							cw, 10, false);
				}
			}

			// Quit: a square in the top-right corner.
			if (quit != null) {
				originclient$iconOnly(quit, "@title-quit");
				int qw = plain ? 74 : TitleLayout.CORNER_SIZE;
				originclient$place(quit, self.width - TitleLayout.CORNER - qw, TitleLayout.CORNER, qw,
						TitleLayout.CORNER_SIZE, false);
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
		w.setHeight(height);
		if (nav) {
			originclient$nav.add(w);
			originclient$navPos.add(new int[]{x, y});
		} else {
			originclient$icons.add(w);
			originclient$iconPos.add(new int[]{x, y});
		}
	}
}
