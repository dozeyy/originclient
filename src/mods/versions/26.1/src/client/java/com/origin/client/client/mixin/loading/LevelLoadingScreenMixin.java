package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Singleplayer "loading terrain" (the chunk-progress map). HEAD-cancel takeover
// -> the Origin loading scene (rings + title + a bar on the load tracker's real
// progress) replaces the chunk map, matching the rest of the UI. Render is display-only; the
// world-load logic runs off the render thread, so cancelling the draw is safe.
// In the isolated required:false config -- if the target ever moves, this is
// skipped instead of crashing the mod.
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock chunk-progress map
		}
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle(), originclient$progress())) {
			ci.cancel();
		}
	}

	// The tracker's server-side fraction done (0..1), or -1 (sweep) while it has
	// none yet or it can't be read. Never a made-up percentage.
	@Unique
	private float originclient$progress() {
		try {
			net.minecraft.client.multiplayer.LevelLoadTracker tracker =
					((LevelLoadingScreenAccessor) (Object) this).originclient$loadTracker();
			return tracker != null && tracker.hasProgress() ? Math.max(0f, Math.min(1f, tracker.serverProgress())) : -1f;
		} catch (Throwable t) {
			return -1f;
		}
	}
}
