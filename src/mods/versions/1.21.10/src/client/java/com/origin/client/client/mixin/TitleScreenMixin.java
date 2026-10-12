package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginButtonRenderer;
import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.CosmeticsScreen;
import com.origin.client.client.gui.OriginModMenuScreen;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.mods.Mods;
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
import net.minecraft.client.gui.components.WidgetSprites;
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

// The Origin main menu (2026-09 redesign v6, compact centred client layout):
//
//   ┌──────────────────────────────────────────────────┐
//   │ [head] Player                                    │
//   │                                                  │
//   │  ORIGIN  (letters reveal in)                     │
//   │                                                  │
//   │  [ Singleplayer ]  ← primary (accent)            │
//   │  [ Multiplayer  ]     (nav slides in,            │
//   │  [ Realms       ]      staggered, one even pitch)│
//   │  [ Mods         ]  ← Origin's own                │
//   │  [ Options…     ]                                │
//   │  [ Quit Game    ]                                │
//   │                    (options)(lang)(access)(quit) │
//   └──────────────────────────────────────────────────┘
//
// GEOMETRY LIVES IN ONE PLACE: TitleLayout. The wordmark (drawn by
// OriginScreenRenderer) and the nav placed here read the same computed block,
// so they can never collide on a short window. Vanilla's own widgets are only
// REPOSITIONED (labels, clicks, actions untouched) and re-skinned by
// OriginButtonRenderer; the Mods button is Origin's, added as a real widget
// through ScreenInvoker. Mod Menu's own "Mods" button is excluded from the
// layout AND hidden every frame (it is added by Mod Menu's screen event after
// our init hook, which also bumps buttons — so positions are re-applied every
// frame). Everything is fail-soft: a throw leaves vanilla's layout in place.
//
// Ported from 1.21.1 onto this module's boot-verified descriptors: the splash
// redirect takes a float colour and the version drawString returns void here;
// the panorama/background suppressions live in ScreenBackgroundMixin (see the
// note below); SpriteIconButton's sprite is a WidgetSprites bundle.
// priority 2000: Origin's re-skin wins over other title-screen mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	@Unique
	private Button originclient$modsNav;
	@Unique
	private Button originclient$cosmeticsNav;
	@Unique
	private long originclient$openedAt = 0;
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
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
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
		OriginScreenRenderer.renderTitleBackground(guiGraphics);
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			// Mod Menu adds its "Mods" button through a screen event that fires
			// AFTER our init hook, so hide it here, every frame.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget w && w.visible) {
				w.visible = false;
				w.active = false;
				continue;
			}
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
		originclient$applyPositions();
	}

	/** Re-applies Origin's positions to the nav + icon widgets EVERY FRAME, with
	 *  the entrance rise on Y. Every frame because Mod Menu's screen event fires
	 *  after our init and "bumps" every button below Realms down by 24 to make
	 *  room for its own (hidden) button — which left a phantom gap in the list
	 *  until this. A handful of setX/setY calls a frame is nothing. */
	@Unique
	private void originclient$applyPositions() {
		long el = System.currentTimeMillis() - originclient$openedAt;
		for (int i = 0; i < originclient$nav.size(); i++) {
			int[] t = originclient$navPos.get(i);
			double e = el > 1200 ? 1.0 : originclient$ease((el - 120 - i * 55) / 380.0);
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

	// NOTE (1.21.10, as on 1.21.11): the backdrop suppressions do NOT live here any more, and
	// must not come back. renderPanorama moved TitleScreen -> Screen in 1.21.6,
	// so an @Inject on it from a TitleScreen mixin binds nothing and is skipped
	// in silence ("defaultRequire": 0) — it only LOOKED like it was working.
	// TitleScreen.renderBackground is an empty override here, so cancelling that
	// achieved nothing either. ScreenBackgroundMixin (@Mixin(Screen.class)) does
	// both for real, gated on the same Origin-style + renderer-health checks.

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V"))
	private void originclient$logo(LogoRenderer instance, GuiGraphics guiGraphics, int screenWidth, float alpha) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(guiGraphics)) {
			instance.renderLogo(guiGraphics, screenWidth, alpha);
		}
	}

	// No yellow splash text (Origin style only).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;F)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphics guiGraphics, int screenWidth, Font font, float color) {
		if (!originclient$origin()) {
			instance.render(guiGraphics, screenWidth, font, color);
		}
	}

	// No bottom version line.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noVersion(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			instance.drawString(font, text, x, y, color);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$layout(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		if (originclient$openedAt == 0) {           // first init only — a resize re-init doesn't replay
			originclient$openedAt = System.currentTimeMillis();
			OriginScreenRenderer.setTitleOpenedAt(originclient$openedAt);
		}
		for (GuiEventListener child : self.children()) {
			// Copyright line + any Mod Menu "Mods" button already present: hidden.
			if ((child instanceof PlainTextButton || originclient$isForeignMods(child))
					&& child instanceof AbstractWidget widget) {
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
		} catch (Throwable ignored) {
			originclient$modsNav = null;
			originclient$cosmeticsNav = null;
		}
		originclient$layoutCentered(self);
		OriginForeignWidgets.avoidOverlap(self);
	}

	/** Mark + two full actions + one paired row. Options, the two vanilla utility
	 *  actions and Quit share one small bottom-centre dock. */
	@Unique
	private void originclient$layoutCentered(Screen self) {
		try {
			originclient$nav.clear();
			originclient$navPos.clear();
			originclient$icons.clear();
			originclient$iconPos.clear();
			List<AbstractWidget> main = new ArrayList<>();
			List<AbstractWidget> icons = new ArrayList<>();
			for (GuiEventListener child : self.children()) {
				if (!(child instanceof AbstractWidget w) || !w.visible || OriginWidgetOwnership.isForeign(w)
						|| originclient$isForeignMods(child)) {
					continue;
				}
				if (child instanceof net.minecraft.client.gui.components.SpriteIconButton) {
					icons.add(w);
				} else if (child instanceof Button) {
					main.add(w);
				}
			}
			if (main.isEmpty()) {
				return;
			}
			main.remove(originclient$modsNav);
			main.remove(originclient$cosmeticsNav);
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			if (originclient$modsNav != null) {
				main.add(Math.min(3, main.size()), originclient$modsNav);   // after Realms, before Options
			}
			if (main.size() > 2) {
				main.get(2).setMessage(Component.literal("Realms"));
			}
			TitleLayout L = TitleLayout.of(self.width, self.height);
			String[] titleIcons = {
					"@title-singleplayer", "@title-multiplayer", "@title-realms", "@title-mods"
			};
			int visibleMain = Math.min(4, main.size());
			int half = (L.mainW - L.pairGap) / 2;
			for (int i = 0; i < visibleMain; i++) {
				AbstractWidget w = main.get(i);
				int x = L.mainX;
				int y = L.mainTop + i * L.rowStep;
				int width = L.mainW;
				if (i >= 2) {
					y = L.mainTop + 2 * L.rowStep;
					width = half;
					if (i == 3) x += half + L.pairGap;
				}
				w.setX(x);
				w.setY(y);
				w.setWidth(width);
				w.setHeight(L.buttonH);
				originclient$nav.add(w);
				originclient$navPos.add(new int[]{x, y});
				OriginButtonRenderer.setTitlePresentation(w, titleIcons[i], false);
			}

			// Cosmetics gets its own full-width row under the Realms/Mods pair, and
			// the SAME title presentation as the rows above it (icon + compact
			// label on the opaque title material). Without it the button fell back
			// to the generic glass skin: a different fill, full-size text, no icon.
			// "@shirt" is the cosmetics symbol the Right Shift screen already uses.
			if (originclient$cosmeticsNav != null) {
				int cy = L.mainTop + 3 * L.rowStep;
				originclient$cosmeticsNav.setX(L.mainX);
				originclient$cosmeticsNav.setY(cy);
				originclient$cosmeticsNav.setWidth(L.mainW);
				originclient$cosmeticsNav.setHeight(L.buttonH);
				originclient$nav.add(originclient$cosmeticsNav);
				originclient$navPos.add(new int[]{L.mainX, cy});
				OriginButtonRenderer.setTitlePresentation(originclient$cosmeticsNav, "@shirt", false);
			}

			List<AbstractWidget> dock = new ArrayList<>();
			if (main.size() > 4) dock.add(main.get(4));       // Options
			dock.addAll(icons);                               // Language + Accessibility
			if (main.size() > 5) dock.add(main.get(5));       // Quit
			for (int i = 6; i < main.size(); i++) dock.add(main.get(i));
			int totalW = dock.size() * L.dockSize + Math.max(0, dock.size() - 1) * L.dockGap;
			int dx = (self.width - totalW) / 2;
			for (AbstractWidget w : dock) {
				w.setX(dx);
				w.setY(L.dockY);
				w.setWidth(L.dockSize);
				w.setHeight(L.dockSize);
				originclient$icons.add(w);
				originclient$iconPos.add(new int[]{dx, L.dockY});
				dx += L.dockSize + L.dockGap;
			}
			for (AbstractWidget iconButton : icons) {
				if (!(iconButton instanceof net.minecraft.client.gui.components.SpriteIconButton)) {
					continue;
				}
				// 1.21.10: `sprite` is a WidgetSprites bundle; the enabled variant names the icon.
				WidgetSprites sprites = ((SpriteIconButtonAccessor) iconButton).originclient$sprite();
				ResourceLocation sprite = sprites.get(true, false);
				String path = sprite.getPath();
				if (path.endsWith("icon/language") || path.endsWith("/language")) {
					OriginButtonRenderer.setTitlePresentation(iconButton, "@title-language", true);
				} else if (path.endsWith("icon/accessibility") || path.endsWith("/accessibility")) {
					OriginButtonRenderer.setTitlePresentation(iconButton, "@title-accessibility", true);
				}
			}
			if (main.size() > 4) {
				AbstractWidget options = main.get(4);
				OriginButtonRenderer.setTitlePresentation(options, "@title-options", true);
			}
			if (main.size() > 5) {
				AbstractWidget quit = main.get(5);
				OriginButtonRenderer.setTitlePresentation(quit, "@title-quit", true);
			}
		} catch (Throwable ignored) {
		}
	}
}
