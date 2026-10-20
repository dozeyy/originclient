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

	// 26.x: LevelLoadingScreen overrides extractBackground (panorama + blur, or
	// the portal sprites by load reason), drawn in its own stratum BEFORE
	// extractRenderState. Target: that override (javap-verified on 26.1.2, 26.2,
	// 26.3). Why: the clean scene's backdrop (this version's panorama, blurred and
	// dimmed) belongs here, so the blur runs once and the scene's text sits over
	// it; every load reason gets it, as on 1.21.1 where the scene takes the whole
	// screen. If it stops applying: the render-pass hook below draws the backdrop
	// itself (blurring at most once per frame), over vanilla's background.
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingBackdrop(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock background
		}
		if (OriginScreenRenderer.renderLoadingBackdrop((Screen) (Object) this, guiGraphics, partialTick)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock chunk-progress map
		}
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, originclient$sceneTitle(), originclient$progress())) {
			ci.cancel();
		}
	}

	// 26.x: LevelLoadingScreen's title is empty (vanilla draws its own
	// "Downloading terrain" / progress text instead), so the scene would show
	// no heading at all, and before the tracker has progress no line either.
	// The heading is vanilla's own "Loading world" (menu.loadingLevel), built
	// once per screen (a translatable resolves in the current language when the
	// scene rebuilds its labels). A non-empty title is kept as is.
	@Unique
	private net.minecraft.network.chat.Component originclient$title;

	@Unique
	private net.minecraft.network.chat.Component originclient$sceneTitle() {
		if (originclient$title == null) {
			net.minecraft.network.chat.Component t = ((Screen) (Object) this).getTitle();
			originclient$title = t == null || t.getString().isBlank()
					? net.minecraft.network.chat.Component.translatable("menu.loadingLevel") : t;
		}
		return originclient$title;
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
