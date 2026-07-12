package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.SpriteIconButton;
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

	// Hide the language + accessibility icons (SpriteIconButton) and the
	// copyright line (PlainTextButton). visible=false stops both rendering and
	// clicks; re-run on every (re)init so it survives window resizes.
	@Inject(method = "init", at = @At("TAIL"))
	private void originclient$stripExtraButtons(CallbackInfo ci) {
		if (!originclient$origin()) {
			return;
		}
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if ((child instanceof SpriteIconButton || child instanceof PlainTextButton)
					&& child instanceof AbstractWidget widget) {
				widget.visible = false;
				widget.active = false;
			}
		}
	}
}
