package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

// Re-skins the main menu: the Origin background (charcoal + rings + grain)
// replaces the panorama, the "ORIGIN" wordmark replaces the vanilla logo, and
// the splash + version text are hidden.
//
// 26.2 retained-mode GUI (all targets javap-confirmed on the 26.2 TitleScreen):
//  - Background drawn at extractBackground() HEAD, then cancelled so vanilla's
//    panorama never paints over ours (extractBackground is background-only; the
//    content pass — logo/buttons/splash — is the separate extractRenderState).
//  - Logo: @Redirect LogoRenderer.extractRenderState (called from
//    TitleScreen.extractRenderState) -> Origin wordmark.
//  - Splash + version: @Redirect their extraction calls to no-ops.
//  - After init(), hide the language/accessibility (SpriteIconButton) + copyright
//    (PlainTextButton) widgets.
// priority 2000: Origin's re-skin wins over other UI mods.
@Mixin(value = TitleScreen.class, priority = 2000)
public class TitleScreenMixin {

	// SETTINGS > General > Main Menu Style. "Vanilla" turns the whole re-skin off.
	private static boolean originclient$origin() {
		return Mods.mode(Mods.GENERAL_ID, "mainMenuStyle").equals("Origin");
	}

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$background(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleBackground(guiGraphics)) {
			return; // fail-soft: vanilla panorama returns if the Origin backdrop fails
		}
		// The website's mouse-follow spotlight: over the rings, under the widgets.
		boolean hoveringClickable = false;
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				hoveringClickable = true;
				break;
			}
		}
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, hoveringClickable);
		// Account chip (Origin mark + username) in the top-left frame corner.
		OriginScreenRenderer.renderTitleAccountChip(guiGraphics);
		ci.cancel(); // replace vanilla's whole backdrop (panorama + menu texture)
	}

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
	private void originclient$logo(LogoRenderer instance, GuiGraphicsExtractor guiGraphics, int screenWidth, float alpha) {
		// Fail-soft: if the wordmark can't draw (or the style is Vanilla), restore
		// vanilla's own logo so the title never loses its centerpiece.
		if (!originclient$origin() || !OriginScreenRenderer.renderTitleWordmark(guiGraphics)) {
			instance.extractRenderState(guiGraphics, screenWidth, alpha);
		}
	}

	// Remove the yellow splash text (Origin style only).
	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
	private void originclient$noSplash(SplashRenderer instance, GuiGraphicsExtractor guiGraphics, int screenWidth, Font font, float alpha) {
		if (!originclient$origin()) {
			instance.extractRenderState(guiGraphics, screenWidth, font, alpha);
		}
	}

	// Remove the bottom version line (the only text() call in extractRenderState).
	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void originclient$noVersion(GuiGraphicsExtractor instance, Font font, String text, int x, int y, int color) {
		if (!originclient$origin()) {
			instance.text(font, text, x, y, color);
		}
	}

	// Hide the language + accessibility icons (SpriteIconButton) and the copyright
	// line (PlainTextButton). visible=false stops both rendering and clicks;
	// re-run on every (re)init so it survives window resizes.
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
