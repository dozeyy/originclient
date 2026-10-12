package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.TntCountdown;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TNT Countdown: after vanilla submits a lit TNT (1.21.11 TntRenderer.submit,
// source-verified; full descriptor so the synthetic EntityRenderState bridge
// isn't matched), submit the fuse label above it. The pose is back at the
// entity origin by then (vanilla pops its block transform before super.submit).
@Mixin(TntRenderer.class)
public class TntCountdownMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/TntRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At("TAIL"))
	private void originclient$fuseLabel(TntRenderState tnt, PoseStack poseStack, SubmitNodeCollector collector,
										CameraRenderState camera, CallbackInfo ci) {
		try {
			TntCountdown.submit(tnt, poseStack, collector, camera);
		} catch (Throwable ignored) {
			// a label must never take the world render down
		}
	}
}
