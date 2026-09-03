package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginForeignWidgets;
import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Re-skins the main menu: the Origin background (charcoal + rotating rings +
// grain) replaces the panorama, the "ORIGIN" wordmark replaces the vanilla
// "Minecraft" logo, and the splash/version text + the language/accessibility/
// copyright buttons are hidden -- leaving just the real menu buttons + header.
//
// Strategy (all targets confirmed via javap against the mapped 1.21.1 jar):
//  - Draw the Origin background at render() HEAD -- render() is guaranteed to
//    run every frame, so the background is always painted, under the logo/
//    buttons that draw afterward.
//  - Cancel both renderPanorama and renderBackground so vanilla's own backdrop
//    (whichever path render() uses) never paints over ours. Both are
//    background-only on TitleScreen; widgets draw in the separate widget pass.
//  - Redirect renderLogo -> Origin wordmark; no-op the splash + version draws.
//  - After init(), hide the SpriteIconButton (language, accessibility) and
//    PlainTextButton (copyright) widgets via visible/active -- the only widgets
//    of those types; the real options are plain Button, left intact.
// priority 2000 (default 1000): if another mod also modifies TitleScreen (e.g.
// redirects the logo/background), Origin's re-skin wins the conflict. Scoped to
// the UI mixins only — perf/render mixins stay at default so Sodium/Iris
// application ordering is left undisturbed.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the entire re-skin
	// off — every inject below no-ops so the stock title screen shows through.
	private static boolean originclient$origin() {
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$background(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		OriginScreenRenderer.renderTitleBackground(guiGraphics);
		// The website's mouse-follow spotlight: over the rings, under the
		// widgets (this HEAD inject runs before the widget pass). Blooms while
		// any visible button is hovered, like the site's hover targets.
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		// Account chip (player head + username) in the top-left frame corner.
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
	}

	// Both suppressions are gated on the renderer's health: if the Origin
	// backdrop ever fails (fail-soft contract), vanilla's panorama comes back
	// instead of leaving a black screen.
	@Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressPanorama(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
		if (originclient$origin() && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
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
		// Fail-soft: if the wordmark can't draw (or the style is Vanilla),
		// restore vanilla's own logo so the title never loses its centerpiece.
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(guiGraphics)) {
			instance.renderLogo(guiGraphics, screenWidth, alpha);
		}
	}

	// Remove the yellow splash text (Origin style only).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;I)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphics guiGraphics, int screenWidth, Font font, int color) {
		if (!originclient$origin()) {
			instance.render(guiGraphics, screenWidth, font, color);
		}
	}

	// Remove the bottom version line (the only drawString in render()).
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
	private int originclient$noVersion(GuiGraphics instance, Font font, String text, int x, int y, int color) {
		return originclient$origin() ? 0 : instance.drawString(font, text, x, y, color);
	}

	// Hide only the copyright line (PlainTextButton). The language + accessibility
	// icons (SpriteIconButton) are KEPT on the Origin menu (Will, 2026-07-21) and
	// re-skinned to the Frost box by SpriteIconButtonMixin. visible=false stops
	// rendering and clicks; re-run on every (re)init so it survives resizes.
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$stripExtraButtons(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof PlainTextButton && child instanceof AbstractWidget widget) {
				widget.visible = false;
				widget.active = false;
			}
		}
		// Hero-left layout (2026-09 redesign) — see originclient$layoutHeroLeft.
		originclient$layoutHeroLeft(self);
		// Other mods add their buttons to this screen too. They keep their own
		// look (OriginWidgetOwnership) and their own placement -- but if one
		// landed on top of an Origin button, move it to free space. Runs after
		// the hide pass above so a hidden widget is not treated as an obstacle.
		OriginForeignWidgets.avoidOverlap(self);
	}

	// HERO-LEFT main menu (2026-09 redesign): the wordmark sits large at the left
	// (OriginScreenRenderer.renderTitleWordmark uses the SAME left/stack formula
	// below) and the real menu buttons stack beneath it as a left-aligned nav
	// list — Singleplayer / Multiplayer / Realms / Options / Quit — instead of
	// vanilla's centred stack. The language + accessibility icon buttons tuck in
	// a row under the list. This only REPOSITIONS vanilla's own widgets (setX/
	// setY/setWidth): labels, clicks and actions are untouched, so the menu can't
	// lose a button. Foreign mods' widgets are skipped (they keep their placement;
	// avoidOverlap then nudges any that collide). Fail-soft: a throw leaves
	// vanilla's positions in place.
	private static void originclient$layoutHeroLeft(Screen self) {
		try {
			java.util.List<AbstractWidget> main = new java.util.ArrayList<>();
			java.util.List<AbstractWidget> icons = new java.util.ArrayList<>();
			for (GuiEventListener child : self.children()) {
				if (!(child instanceof AbstractWidget w) || !w.visible) {
					continue;
				}
				if (OriginWidgetOwnership.isForeign(w)) {
					continue;
				}
				if (child instanceof net.minecraft.client.gui.components.SpriteIconButton) {
					icons.add(w);
				} else if (child instanceof net.minecraft.client.gui.components.Button) {
					main.add(w);
				}
			}
			if (main.isEmpty()) {
				return;
			}
			// Keep vanilla's order (top→bottom, then left→right for Options/Quit).
			main.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
			int sw = self.width, sh = self.height;
			int x = Math.max(24, (int) Math.round(sw * 0.08));          // same left edge as the wordmark
			int bw = Math.max(140, Math.min(180, sw / 4));
			int bh = 20, step = 24;
			int total = main.size() * step - (step - bh);
			int y = Math.max(sh / 2 - 8, (int) Math.round(sh * 0.40));  // same stack top as the wordmark
			int iconRowY = sh - 20 - 14;
			if (y + total > iconRowY - 10) {                              // short window: keep it on-screen
				y = Math.max(40, iconRowY - 10 - total);
			}
			for (AbstractWidget w : main) {
				w.setX(x);
				w.setY(y);
				w.setWidth(bw);
				y += step;
			}
			int ix = x;
			for (AbstractWidget w : icons) {
				w.setX(ix);
				w.setY(iconRowY);
				ix += w.getWidth() + 6;
			}
		} catch (Throwable ignored) {
		}
	}
}
