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
// Ported from 1.21.1. 1.16.5: no GuiGraphics (Gfx over the PoseStack), no
// SpriteIconButton (the Language / Accessibility icons are ImageButtons, told
// apart by their TranslatableComponent keys), no setHeight (accessor), widget
// x/y are public fields (getX/setX are 1.19.3+), Button has no builder, and
// there is no LogoRenderer / SplashRenderer / renderPanorama — the panorama,
// overlay, logo, edition badge, splash and version line are suppressed with
// this module's own boot-verified @Redirects (PORT-NOTES.md call census).
// 1.16.5 also: Java 8 (no instanceof patterns), Screen.addButton instead of
// addRenderableWidget, and render() OPENS with a full-screen white fill
// (originclient$suppressWhiteFlash, this module's boot-found fix).
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
		return child instanceof Button && child != originclient$modsNav
				&& "mods".equalsIgnoreCase(((Button) child).getMessage().getString());
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Gfx guiGraphics = new Gfx(poseStack);
		OriginScreenRenderer.renderTitleBackground(guiGraphics);
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			// Mod Menu adds its "Mods" button through a screen event that fires
			// AFTER our init hook, so hide it here, every frame.
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget && ((AbstractWidget) child).visible) {
				((AbstractWidget) child).visible = false;
				((AbstractWidget) child).active = false;
				continue;
			}
			if (child instanceof AbstractWidget && ((AbstractWidget) child).visible
					&& ((AbstractWidget) child).isMouseOver(mouseX, mouseY)) {
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
			w.x = t[0];
			w.y = t[1] + (int) Math.round((1.0 - e) * 8);
		}
		for (int i = 0; i < originclient$icons.size(); i++) {
			int[] t = originclient$iconPos.get(i);
			originclient$icons.get(i).x = t[0];
			originclient$icons.get(i).y = t[1];
		}
	}

	// 1.16.5 ONLY: render() OPENS with fill(poseStack, 0, 0, width, height, -1) --
	// a full-screen WHITE fill (-1 == 0xFFFFFFFF) that vanilla immediately covers
	// with the panorama. Origin suppresses the panorama, so that white would
	// survive and bury the backdrop drawn at HEAD -- a white title screen with
	// only the button text on it. ordinal = 0 pins the background fill; the other
	// fill in render() is the copyright hover highlight (bytecode-verified against
	// the mapped 1.16.5 jar: offset 61 iconst_m1 -> fill, offset 741 the
	// copyrightX/copyrightWidth highlight). Later versions dropped this fill,
	// which is why only this module needs the redirect.
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V",
			ordinal = 0))
	private void originclient$suppressWhiteFlash(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.fill(poseStack, x1, y1, x2, y2, color);
		}
	}

	// Every suppression is gated on the renderer's health: if the Origin backdrop
	// ever fails (fail-soft contract), vanilla's panorama/logo come back instead of
	// a black screen. This era's render() calls this.panorama.render(partialTick,
	// alpha) DIRECTLY (no renderPanorama / own renderBackground to cancel), then
	// blits the PANORAMA_OVERLAY vignette; the logo is two blitOutlineBlack calls
	// plus the 8-arg "Java Edition" badge blit, the splash is a String drawn by
	// drawCenteredString and the version line is a drawString (no LogoRenderer /
	// SplashRenderer before 1.19.4). These are this module's own boot-verified
	// redirects, kept through the redesign (call census in PORT-NOTES.md).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
	private void originclient$suppressPanorama(PanoramaRenderer instance, float partialTick, float alpha) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			instance.render(partialTick, alpha);
		}
	}

	// The PANORAMA_OVERLAY vignette blit right after the panorama — the only
	// 10-arg blit in render().
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/TitleScreen;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIFFIIII)V"))
	private void originclient$suppressOverlay(PoseStack poseStack, int x, int y, int w, int h,
											  float u, float v, int uW, int vH, int texW, int texH) {
		if (!originclient$origin() || !OriginScreenRenderer.isActive()) {
			GuiComponent.blit(poseStack, x, y, w, h, u, v, uW, vH, texW, texH);
		}
	}

	// The vanilla logo: both blitOutlineBlack calls (normal + "Minceraft"
	// easter-egg branch) share one shape, so this redirect wraps both; only one
	// runs per frame, so the wordmark draws exactly once. The enclosing render()
	// args are appended to the handler (Mixin arg capture) to reach the frame's
	// PoseStack — blitOutlineBlack itself doesn't carry one. Fail-soft: if the
	// wordmark can't draw (or the style is Vanilla), vanilla's own logo returns.
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
	// whenever the wordmark path is off or unhealthy.
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

	// No bottom version line AND no copyright line: on 1.16.5 both are plain
	// drawString calls in render() (PlainTextButton is 1.18+) -- this redirect binds both.
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
			return;
		}
		Screen self = (Screen) (Object) this;
		if (originclient$openedAt == 0) {           // first init only — a resize re-init doesn't replay
			originclient$openedAt = System.currentTimeMillis();
			OriginScreenRenderer.setTitleOpenedAt(originclient$openedAt);
		}
		for (GuiEventListener child : self.children()) {
			// Any Mod Menu "Mods" button already present: hidden. (No PlainTextButton
			// before 1.18 — the copyright is a plain drawString, suppressed with the
			// version line by originclient$noVersion.)
			if (originclient$isForeignMods(child) && child instanceof AbstractWidget) {
				((AbstractWidget) child).visible = false;
				((AbstractWidget) child).active = false;
			}
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			// No Button.builder before 1.19.3: the plain (x, y, w, h, label, onPress) ctor.
			originclient$modsNav = new Button(0, 0, 150, 20, new TextComponent("Mods"),
					b -> mc.setScreen(new OriginModMenuScreen()));
			((ScreenInvoker) self).originclient$addButton(originclient$modsNav);   // 1.16.5: Screen.addButton
			originclient$cosmeticsNav = new Button(0, 0, 150, 20, new TextComponent("Cosmetics"),
					b -> mc.setScreen(new CosmeticsScreen(self)));
			((ScreenInvoker) self).originclient$addButton(originclient$cosmeticsNav);
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
				if (!(child instanceof AbstractWidget)) {
					continue;
				}
				AbstractWidget w = (AbstractWidget) child;
				if (!w.visible || OriginWidgetOwnership.isForeign(w) || originclient$isForeignMods(child)) {
					continue;
				}
				if (child instanceof ImageButton) {
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
			main.sort(java.util.Comparator.comparingInt((AbstractWidget w) -> w.y).thenComparingInt(w -> w.x));
			if (originclient$modsNav != null) {
				main.add(Math.min(3, main.size()), originclient$modsNav);   // after Realms, before Options
			}
			if (main.size() > 2) {
				main.get(2).setMessage(new TextComponent("Realms"));
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
				w.x = x;
				w.y = y;
				w.setWidth(width);
				((AbstractWidgetAccessor) w).originclient$setHeight(L.buttonH);
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
				originclient$cosmeticsNav.x = L.mainX;
				originclient$cosmeticsNav.y = cy;
				originclient$cosmeticsNav.setWidth(L.mainW);
				((AbstractWidgetAccessor) originclient$cosmeticsNav).originclient$setHeight(L.buttonH);
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
				w.x = dx;
				w.y = L.dockY;
				w.setWidth(L.dockSize);
				((AbstractWidgetAccessor) w).originclient$setHeight(L.dockSize);
				originclient$icons.add(w);
				originclient$iconPos.add(new int[]{dx, L.dockY});
				dx += L.dockSize + L.dockGap;
			}
			for (AbstractWidget iconButton : icons) {
				// narrator.button.language / narrator.button.accessibility
				String key = originclient$translationKey(iconButton.getMessage());
				if (key.endsWith(".language")) {
					OriginButtonRenderer.setTitlePresentation(iconButton, "@title-language", true);
				} else if (key.endsWith(".accessibility")) {
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
