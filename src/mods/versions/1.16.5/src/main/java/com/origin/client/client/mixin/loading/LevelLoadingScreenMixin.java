package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Singleplayer "loading terrain" (the chunk-progress map). HEAD-cancel takeover
// -> the Origin loading scene (menu background + title + the real chunk
// progress, read through LevelLoadingScreenAccessor) replaces the chunk map, matching the rest of the UI. Render is display-only; the
// world-load logic runs off the render thread, so cancelling the draw is safe.
// In the isolated required:false config -- if the target ever moves, this is
// skipped instead of crashing the mod.
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		if (OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), ((Screen) (Object) this).getTitle(), originclient$progress())) {
			ci.cancel();
		}
	}

	// Vanilla's own 0-100 count as 0..1, or -1 (sweep) if it can't be read.
	@Unique
	private float originclient$progress() {
		try {
			return Math.max(0, Math.min(100, ((LevelLoadingScreenAccessor) (Object) this).originclient$progressListener().getProgress())) / 100f;
		} catch (Throwable t) {
			return -1f;
		}
	}
}
