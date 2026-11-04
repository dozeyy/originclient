package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Preparing to create world" / world-creation progress stages. HEAD-cancel
// takeover -> the Origin loading scene, captioned with the stage vanilla reports
// and its real percentage (rail sweeps while it reports none). The generation
// logic runs on the integrated-server thread via the ProgressListener interface,
// independent of extractRenderState(), so replacing the draw is safe. Once stop() has
// been called the mixin does NOT cancel: vanilla's extractRenderState() then runs its own
// close-the-screen branch, and cancelling there could leave a stopped screen open. If
// ProgressScreenAccessor (listed in originclient.loading.mixins.json) stops applying,
// the cast throws and the scene falls back to the title with a sweeping rail (G121-3,
// ported from 1.21.1 2026-11-04).
@Mixin(ProgressScreen.class)
public class ProgressScreenMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		Component caption = ((Screen) (Object) this).getTitle();
		float progress = -1f;
		try {
			ProgressScreenAccessor acc = (ProgressScreenAccessor) (Object) this;
			if (acc.originclient$stopped()) {
				return;   // finished: let vanilla's own close-the-screen branch run
			}
			Component stage = acc.originclient$stage();
			Component header = acc.originclient$header();
			caption = stage != null ? stage : header != null ? header : caption;
			// Vanilla shows its "NN%" only for a non-zero value; 0 = nothing reported yet.
			int pct = acc.originclient$progress();
			progress = pct > 0 ? Math.min(100, pct) / 100f : -1f;
		} catch (Throwable t) {
			// Accessor not applied: the screen's title and a sweeping rail.
		}
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, caption, progress)) {
			ci.cancel();
		}
	}
}
