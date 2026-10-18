package com.origin.client.client.mixin.loading;

import com.origin.client.client.StartupTransition;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Loading -> title: once the resource load is done, the Origin loading screen
// dissolves into the screen behind it over StartupTransition.REVEAL_MS, then
// the overlay is removed. Without this, LoadingOverlayMixin's opaque TAIL draw
// holds the loading screen through vanilla's whole 2 s fade-out and then hard-
// cuts to the title (input is blocked that whole time too).
//
// Why a HEAD cancel is safe HERE when it isn't in LoadingOverlayMixin: it only
// cancels once fadeOutStart is set, i.e. after vanilla has already run the
// completion step (checkExceptions -> onFinish -> screen init). What's left of
// vanilla's render after that point is the fade drawing and setOverlay(null),
// and this does both. Every other frame — and any frame where this throws or
// Origin rendering is off — runs vanilla untouched, so vanilla's own 2 s end
// stays the safety net and a hang is impossible.
//
// Its own small mixin (in the isolated required:false loading config) so a
// shadow that doesn't resolve skips only the dissolve, never the loading screen.
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayRevealMixin {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	@Final
	private boolean fadeIn;
	@Shadow
	private long fadeOutStart;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$reveal(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.fadeOutStart <= -1L || !OriginScreenRenderer.isActive()) {
			return;
		}
		long t = Util.getMillis() - this.fadeOutStart;
		if (t < 0 || t >= 2000L) {
			return;
		}
		if (!this.fadeIn) {
			StartupTransition.titleRevealed();  // the startup load, not a later resource reload
		}
		Screen screen = this.minecraft.screen;
		if (screen != null) {
			screen.render(guiGraphics, mouseX, mouseY, partialTick);
		}
		if (t >= StartupTransition.REVEAL_MS) {
			// Dissolve finished: this frame is the screen alone, and from the
			// next one the game renders and routes input to it normally.
			this.minecraft.setOverlay(null);
			ci.cancel();
			return;
		}
		guiGraphics.flush();
		float alpha = (float) (1.0 - StartupTransition.ease(t / (double) StartupTransition.REVEAL_MS));
		if (OriginScreenRenderer.renderLoadingFade(guiGraphics, alpha)) {
			ci.cancel();
		}
	}
}
