package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
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
// independent of render(), so replacing the draw is safe.
@Mixin(ProgressScreen.class)
public class ProgressScreenMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$originLoadingScene(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginStyle.vanillaMenusOn(this)) {
			return;   // vanilla mode: stock screen
		}
		Component caption = ((Screen) (Object) this).getTitle();
		float progress = -1f;
		try {
			ProgressScreenAccessor acc = (ProgressScreenAccessor) (Object) this;
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
		if (OriginScreenRenderer.renderLoadingScene(guiGraphics, caption, progress, false)) {
			ci.cancel();
		}
	}
}
