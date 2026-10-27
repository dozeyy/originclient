package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Loading terrain..." when joining a server or changing dimension draws the one Origin loading picture.
//
// PER-VERSION (1.21.6 to 1.21.8, deferred GUI): renderBackground runs BEFORE render, each in its own
// stratum. ReceivingLevelScreen declares its own renderBackground (the portal / end / menu backdrop by
// Reason; javap 1.21.6 / 1.21.8), so the picture HEAD-cancels that, and render's HEAD cancel drops the
// "Downloading terrain" line only when the picture drew. Display-only; the screen's tick (which closes it
// once the level is in) is untouched. Vanilla mode or a failed picture: the stock screen.
// In the isolated required:false config: if a target moves, that half is skipped; nothing crashes.
@Mixin(ReceivingLevelScreen.class)
public class ReceivingLevelScreenMixin {
	// True when this frame's renderBackground drew the Origin picture.
	@Unique
	private boolean originclient$drew;

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		originclient$drew = !OriginStyle.vanillaMenusOn(this)
				&& OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle());
		if (originclient$drew) {
			ci.cancel();
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$dropVanillaText(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$drew) {
			ci.cancel();
		}
	}
}
