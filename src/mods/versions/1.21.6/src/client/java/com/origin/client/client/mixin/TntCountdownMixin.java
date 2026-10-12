package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.TntCountdown;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TNT Countdown: after vanilla draws a lit TNT, draw the fuse label above it.
// PER-VERSION (1.21.2+ render-state era, verified against the decompiled 1.21.4
// and 1.21.8 TntRenderer): render(TntRenderState, PoseStack, MultiBufferSource,
// int) -- the state already carries the fuse (fuseRemainingInTicks), the
// bounding-box height and the camera distance, everything the label needs. Full
// descriptor so the synthetic EntityRenderState bridge isn't matched. The pose is
// back at the entity origin by TAIL (vanilla pops its block transform before
// calling super.render).
@Mixin(TntRenderer.class)
public class TntCountdownMixin {
	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/TntRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("TAIL"))
	private void originclient$fuseLabel(TntRenderState state, PoseStack poseStack, MultiBufferSource buffers,
										int packedLight, CallbackInfo ci) {
		try {
			TntCountdown.render(state, poseStack, buffers);
		} catch (Throwable ignored) {
			// a label must never take the world render down
		}
	}
}
