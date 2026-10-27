package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Preparing to create world" / world-creation progress stages draw the one Origin loading picture,
// captioned with the stage vanilla reports and its real percentage (the bar sweeps while it reports
// none). The generation logic runs on the integrated-server thread via the ProgressListener
// interface, independent of render(), so replacing the draw is safe.
//
// PER-VERSION (1.21.6 to 1.21.8, deferred GUI): renderBackground runs BEFORE render, each in its own
// stratum, and ProgressScreen declares no renderBackground (javap 1.21.6 / 1.21.8), so the backdrop
// is cancelled SEPARATELY: this mixin ADDS a renderBackground override that draws the picture instead
// of the inherited panorama + blur, and render's HEAD cancel drops vanilla's text only when it drew.
// Once stop() has been called nothing is drawn or cancelled: vanilla's render() then closes the screen
// (setScreen(null) when clearScreenAfterStop), and cancelling there would leave a stopped screen open
// forever. If the accessor stops applying, the screen's title and a sweeping bar are shown and the
// stopped flag reads false (as before this port); nothing crashes.
@Mixin(ProgressScreen.class)
public abstract class ProgressScreenMixin extends Screen {
	// True when this frame's renderBackground drew the Origin picture.
	@Unique
	private boolean originclient$drew;

	private ProgressScreenMixin(Component title) {
		super(title);   // never runs: mixin constructors are discarded
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		originclient$drew = false;
		if (!OriginStyle.vanillaMenusOn(this)) {
			Component caption = this.getTitle();
			float progress = -1f;
			boolean stopped = false;
			try {
				ProgressScreenAccessor acc = (ProgressScreenAccessor) (Object) this;
				stopped = acc.originclient$stopped();
				Component stage = acc.originclient$stage();
				Component header = acc.originclient$header();
				caption = stage != null ? stage : header != null ? header : caption;
				// Vanilla shows its "NN%" only for a non-zero value; 0 = nothing reported yet.
				int pct = acc.originclient$progress();
				progress = pct > 0 ? Math.min(100, pct) / 100f : -1f;
			} catch (Throwable t) {
				// Accessor not applied: the screen's title and a sweeping bar.
			}
			// Finished: let vanilla's render() run its close-the-screen branch.
			originclient$drew = !stopped && OriginScreenRenderer.renderLoadingScene(guiGraphics, caption, progress, false);
		}
		if (!originclient$drew) {
			super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$dropVanillaText(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (originclient$drew) {
			ci.cancel();
		}
	}
}
