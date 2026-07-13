package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Block Outline mod — hide toggle. When the outline sub-toggle is off, cancel the
// block-selection outline draw (LevelRenderer.submitHitOutline). A single HEAD
// inject only: the earlier @ModifyVariable colour/thickness override broke
// LevelRenderer transformation, so custom colour + thickness are deferred to a
// safer lever (a @Redirect on the submitShapeOutline call) as a follow-up.
@Mixin(LevelRenderer.class)
public class LevelRendererBlockOutlineMixin {

	@Inject(method = "submitHitOutline", at = @At("HEAD"), cancellable = true)
	private void originclient$hideOutline(CallbackInfo ci) {
		if (Mods.on("blockoverlay") && !Mods.bool("blockoverlay", "outline")) {
			ci.cancel();
		}
	}
}
