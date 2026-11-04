package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Preparing to create world" / world-creation progress stages, and (before 1.20.3) a
// server resource pack's download. HEAD-cancel takeover -> the Origin loading scene,
// captioned with the stage vanilla reports and its real percentage (rail sweeps while it
// reports none). The generation logic runs on the integrated-server thread via the
// ProgressListener interface, independent of render(), so replacing the draw is safe.
// Once stop() has been called the mixin does NOT cancel: vanilla's render() then closes
// the screen (setScreen(null)), and cancelling there left a stopped screen up with no way
// out (G121-3, ported from 1.21.1 2026-11-04). If ProgressScreenAccessor (listed in
// originclient.loading.mixins.json) stops applying, the cast throws and the scene falls
// back to the title with a sweeping rail.
@Mixin(ProgressScreen.class)
public class ProgressScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// Only cancel vanilla when the Origin scene actually drew (fail-soft).
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		Component caption = ((Screen) (Object) this).getTitle();
		float progress = -1f;
		try {
			ProgressScreenAccessor acc = (ProgressScreenAccessor) (Object) this;
			if (acc.originclient$stopped()) {
				return;   // finished: let vanilla's render() run its close-the-screen branch
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
		if (OriginScreenRenderer.renderLoadingScene(new Gfx(poseStack), caption, progress)) {
			ci.cancel();
		}
	}
}
