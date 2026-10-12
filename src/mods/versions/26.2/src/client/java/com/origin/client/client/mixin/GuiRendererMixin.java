package com.origin.client.client.mixin;

import com.origin.client.client.render.ColorGrade;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Color Saturation's world snapshot point. GuiRenderer.render() is where the built
// GuiRenderState is flushed to the main target — i.e. the exact moment the world is
// finished in the main target and the GUI has not drawn over it yet. Copying the
// world into ColorGrade's swap HERE (a real GPU point) fixes the "ghost of the last
// frame + black elsewhere" bug that came from doing the copy during GUI extraction
// (pure CPU state building, where the copy captured a stale/empty frame).
//
// The grade quad itself is still submitted during extraction (ColorGrade.process, a
// Fabric HUD element registered addFirst); it draws later in this same render() call,
// after this HEAD snapshot, so it samples the fresh world copy. Fail-soft: the copy
// is wrapped in ColorGrade and no-ops if it wasn't armed this frame.
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$snapshotWorldForGrade(CallbackInfo ci) {
		ColorGrade.captureWorld();
	}
}
