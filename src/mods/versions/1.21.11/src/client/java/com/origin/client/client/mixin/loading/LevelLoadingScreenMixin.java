package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Singleplayer "loading terrain" (the chunk-progress map). HEAD-cancel takeover
// -> the Origin loading scene (rings + title + bar filled to vanilla's own chunk
// percentage) replaces the chunk map, matching the rest of the UI. Render is display-only; the
// world-load logic runs off the render thread, so cancelling the draw is safe.
// In the isolated required:false config -- if the target ever moves, this is
// skipped instead of crashing the mod.
@Mixin(LevelLoadingScreen.class)
public class LevelLoadingScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock chunk-progress map
		}
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, ((Screen) (Object) this).getTitle(), originclient$progress())) {
			ci.cancel();
		}
	}

	// Target: LevelLoadingScreen.renderBackground(GuiGraphics,int,int,float)
	// (javap-verified 1.21.11; it draws panorama + blur + menu texture, or the
	// nether/end portal). Why: on 1.21.6+ renderBackground runs BEFORE render
	// (Screen.renderWithTooltipAndSubtitles), so the render-HEAD takeover above no
	// longer skips it; the Origin scene draws its own backdrop, so vanilla's would
	// stack a second dim under it (and the portal sprite over the panorama). If
	// this stops applying, the scene still draws, over vanilla's backdrop.
	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$noVanillaBackdrop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!OriginStyle.vanillaMenusOn(this) && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	// The tracker's own 0..1 server progress, or -1 (sweep) while it has none
	// or it can't be read (1.21.9+: LevelLoadTracker replaced the 0-100 count).
	@Unique
	private float originclient$progress() {
		try {
			net.minecraft.client.multiplayer.LevelLoadTracker tracker = ((LevelLoadingScreenAccessor) (Object) this).originclient$loadTracker();
			return tracker != null && tracker.hasProgress() ? Math.max(0f, Math.min(1f, tracker.serverProgress())) : -1f;
		} catch (Throwable t) {
			return -1f;
		}
	}
}
