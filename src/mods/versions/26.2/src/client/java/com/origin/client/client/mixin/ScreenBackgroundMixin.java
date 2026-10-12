package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin background (charcoal + rings + grain) behind every out-of-world menu,
// plus the website's mouse-follow spotlight, so the whole menu tree reads as one
// interface with the main menu.
//
// 26.2 retained-mode GUI: the immediate-mode hooks are gone, so this retargets to
// the extraction methods (all javap-confirmed on the 26.2 Screen):
//  - renderBackground(GuiGraphics,...)        -> extractBackground(GuiGraphicsExtractor,...)
//  - renderMenuBackgroundTexture(...)         -> extractMenuBackgroundTexture(GuiGraphicsExtractor,...)
// extractBackground is background-only (widgets extract separately), so cancelling
// it replaces just the backdrop, exactly as the old renderBackground cancel did.
//
// Background replacement is gated on Minecraft.level == null: in-game screens keep
// vanilla's blurred-world backdrop. The cursor glow is not gated (out-of-world in
// the cancel path, in-world at TAIL — mutually exclusive).
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originBackdrop(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null
				&& OriginScreenRenderer.renderTitleBackground(guiGraphics)) {
			OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
			ci.cancel();
		}
	}

	// In-world menus (pause, in-game options): vanilla's blurred-world backdrop
	// stays, the spotlight draws on top of it.
	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void originclient$inWorldGlow(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
	}

	@Inject(method = "extractMenuBackgroundTexture", at = @At("HEAD"), cancellable = true)
	private static void originclient$noListTexture(GuiGraphicsExtractor guiGraphics, Identifier texture, int x, int y, float uOffset, float vOffset, int width, int height, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	// 26.2 blurs everything drawn behind a screen's content (a blur stratum). On
	// out-of-world Origin menus that blurs the Origin backdrop into a grey haze
	// that shows through the translucent Origin widgets — so suppress it and keep
	// the crisp Origin look. In-world (pause/options) keeps vanilla's world blur.
	@Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$noBlur(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	private boolean originclient$hoveringClickable() {
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				return true;
			}
		}
		return false;
	}
}
