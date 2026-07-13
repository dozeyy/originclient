package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Singleplayer "loading terrain" (the chunk-progress map). HEAD-cancel takeover
// -> the Origin loading scene (menu background + title + sweeping bar) replaces
// the chunk map, matching the rest of the UI. Render is display-only; the
// world-load logic runs off the render thread, so cancelling the draw is safe.
// In the isolated required:false config -- if the target ever moves, this is
// skipped instead of crashing the mod.
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

	// 26.2: Screen.render is gone -> extractRenderState (retained-mode GUI).
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle())) {
			ci.cancel();
		}
	}
}
