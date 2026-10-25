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

// The Origin main menu on Minecraft's own buttons (Will, 2026-10-19: "use
// normal minecraft font ... normal buttons ... keep those custom buttons at the
// bottom but make them into the minecraft buttons ... keep the background and
// player in the top left and add the exit to the bottom ... when you hover over
// any of the buttons it should tell you what they do just like the launcher"):
//
//   [head Player]
//
//                            (logo)
//                            ORIGIN
//                 [        Singleplayer        ]   200 x 20, Workbench panel (radius 4)
//                 [        Multiplayer         ]
//                 [   Options    ][    Mods    ]
//
//     realms friends cosmetics lang access quit          bare 12 px glyphs in 20 x 20 hit boxes
//     Minecraft 1.21.1, Origin                   Copyright Mojang AB. Do not distribute!
//
// Background: THIS version's own Mojang panorama (vanilla draws it), blurred
// by vanilla's blur and dimmed (renderPanorama TAIL). No splash. Footer: the
// version line left and the real copyright button right, both textMuted in
// Minecraft's font, 6 GUI px from the bottom. GEOMETRY LIVES IN ONE PLACE: TitleLayout. Vanilla's own widgets are
// only REPOSITIONED (clicks and actions untouched) and draw as stock
// Workbench panels (OriginButtonRenderer skin 2: titleControl, registered below
// with setTitleButton); the dock is bare hued glyphs (setTitleDock, no boxes).
// Both fall back to the stock vanilla button on a draw failure or when the
// Main Menu Style is vanilla. Every dock glyph gets a one-line tooltip saying what it does (vanilla Tooltip); worded buttons get
// none -- their label already says it (Will, 2026-10-19). Mods / Friends /
// Cosmetics are Origin's, added as real widgets through ScreenInvoker. Mod
// Menu's own "Mods" button is hidden every frame (it is added by Mod Menu's
// screen event after our init hook, which also bumps buttons -- so positions
// are re-applied every frame). The copyright line is vanilla's own clickable
// widget, re-seated bottom-right and recoloured (PlainTextButtonMixin). Title
// tooltips draw as the Workbench panel (TooltipRenderUtilMixin). Everything is fail-soft: a throw leaves vanilla's layout in place.
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
	// Minecraft Realms only lets a client join a realm running the SAME release, and a realm always runs the latest one: on every older version the button is a dead end (Will, 2026-10-20: "realms only work on certain versions"). Flip to true if a version's Realms is verified to join.
	private static final boolean originclient$REALMS_WORKS = false;

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

	// Footer, left half: vanilla's bottom-left version line (drawn pure white, 2 px in) becomes
	// "Minecraft 1.21.1, Origin" in textMuted, in Minecraft's own font (Will, 2026-10-19), 6 GUI px
	// from the bottom (DESIGN-SYSTEM 15.2). `color` carries vanilla's fade-in alpha, which rides along.
	// The right half is the real copyright button: originclient$footerButton below.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
	private int originclient$footerLeft(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			return instance.drawString(font, text, x, y, color);
		}
		String line = originclient$footerLine;
		if (line == null) {
			// Built once: the game version never changes while the screen lives.
			line = "Minecraft " + net.minecraft.SharedConstants.getCurrentVersion().getName() + ", Origin";
			originclient$footerLine = line;
		}
		return instance.drawString(font, line, TitleLayout.CORNER, ORIGIN_FOOTER_TOP_OFFSET + ((Screen) (Object) this).height,
				OriginButtonRenderer.footerColor(false, color), true);
	}

	// Footer baseline: the 8 px text line's top, relative to the screen bottom (bottom edge 6 GUI px up).
	@Unique
	private static final int ORIGIN_FOOTER_TOP_OFFSET = -14;

	// The footer string, cached on first draw (it was rebuilt every frame).
	@Unique
	private String originclient$footerLine;

	@Unique
	private PlainTextButton originclient$copyright;

	// The copyright line stays vanilla's real clickable PlainTextButton (credits screen); Origin only
	// registers it (PlainTextButtonMixin recolours it to textMuted) and re-seats it bottom-right, 6 px in.
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$footerButton(CallbackInfo ci) {
		originclient$copyright = null;
		if (!originclient$origin()) {
			return;
		}
		for (GuiEventListener child : ((Screen) (Object) this).children()) {
			if (child instanceof PlainTextButton b) {
				originclient$copyright = b;
				OriginButtonRenderer.setTitleFooter(b);
			}
		}
	}

	// Every frame, before the widgets draw: Mod Menu's screen event shifts buttons after our init, so
	// the footer button's seat is re-applied like the nav and dock positions are.
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$footerSeat(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		PlainTextButton b = originclient$copyright;
		if (b != null && originclient$origin()) {
			Screen self = (Screen) (Object) this;
			b.setPosition(self.width - b.getWidth() - TitleLayout.CORNER, self.height + ORIGIN_FOOTER_TOP_OFFSET);
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
			// (PlainTextButton) is left out of the layout; originclient$footerSeat seats it.
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

	/** Dock action: a bare glyph in its hue, and what it does as its tooltip. */
	@Unique
	private static void originclient$iconOnly(AbstractWidget w, String icon, int hue, String tip) {
		OriginButtonRenderer.setTitleDock(w, icon, hue);
		originclient$tip(w, tip);
	}

	/** Worded button on the Workbench panel, with its optional 8 px leading glyph (null = none). */
	@Unique
	private static void originclient$worded(AbstractWidget w, String icon) {
		if (w != null) {
			OriginButtonRenderer.setTitleButton(w, icon);
		}
	}

	/** The launcher's hover line: one short plain sentence saying what the button does. */
	@Unique
	private static void originclient$tip(AbstractWidget w, String tip) {
		if (w != null) {
			w.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tip)));
		}
	}

	/**
	 * The layout: Singleplayer and Multiplayer as full rows, Options | Mods as a
	 * pair, and a centred row of 20px dock glyphs at the bottom (Realms,
	 * Friends, Cosmetics, Language, Accessibility, Quit last). Buttons are found by their vanilla label (translation
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
				options.setMessage(Component.translatable("options.title"));   // "Options", sentence case
			}
			for (int i = 0; i < full.size(); i++) {
				AbstractWidget w = full.get(i);
				originclient$worded(w, w == play ? "@title-singleplayer" : w == online ? "@title-multiplayer" : null);
			}
			originclient$worded(options, "@title-options");
			originclient$worded(originclient$modsNav, "@title-mods");
			// Worded buttons say what they do already: no tooltip (Will, 2026-10-19:
			// "dont have the hover tells what it dos for the worded ones only the icons").
			if (options != null && originclient$modsNav != null) {
				originclient$place(options, L.mainX, y, half, L.buttonH, true);
				originclient$place(originclient$modsNav, L.mainX + L.mainW - half, y, half, L.buttonH, true);
			} else if (options != null || originclient$modsNav != null) {
				originclient$place(options != null ? options : originclient$modsNav, L.mainX, y, L.mainW, L.buttonH, true);
			}

			// Bottom row of bare glyphs (20 x 20 hit boxes), Quit at its end.
			List<AbstractWidget> dock = new ArrayList<>();
			if (realms != null) {
				if (originclient$REALMS_WORKS) {
					originclient$iconOnly(realms, "@title-realms", com.origin.client.client.theme.OriginTheme.HUE_SKY, "Play on Minecraft Realms");
					dock.add(realms);
				} else {
					// Realms only joins on the latest release (see REALMS_WORKS): hide the dead button, keep the slot out of the dock
					realms.visible = false;
					realms.active = false;
				}
			}
			if (originclient$friendsNav != null) {
				originclient$iconOnly(originclient$friendsNav, "@title-multiplayer", com.origin.client.client.theme.OriginTheme.HUE_SKY, "Friends: see who is online and join them");
				dock.add(originclient$friendsNav);
			}
			if (originclient$cosmeticsNav != null) {
				originclient$iconOnly(originclient$cosmeticsNav, "@shirt", com.origin.client.client.theme.OriginTheme.HUE_GOLD, "Cosmetics: capes and more for your player");
				dock.add(originclient$cosmeticsNav);
			}
			for (AbstractWidget icon : sprites) {
				ResourceLocation sprite = ((SpriteIconButtonAccessor) icon).originclient$sprite();
				String path = sprite.getPath();
				if (path.endsWith("language")) {
					originclient$iconOnly(icon, "@title-language", com.origin.client.client.theme.OriginTheme.HUE_GOLD, "Change the game's language");
				} else if (path.endsWith("accessibility")) {
					originclient$iconOnly(icon, "@title-accessibility", com.origin.client.client.theme.OriginTheme.HUE_EMERALD, "Accessibility settings");
				}
				dock.add(icon);
			}
			if (quit != null) {
				originclient$iconOnly(quit, "@title-quit", com.origin.client.client.theme.OriginTheme.HUE_CORAL, "Quit the game");
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
