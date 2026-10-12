package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.TntCountdown;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TNT Countdown: after vanilla draws a lit TNT (TntRenderer.render, decompiled
// 1.19.1 (the 1.19.2 code base); full descriptor so the synthetic Entity bridge isn't matched),
// draw the fuse label above it. The pose is back at the entity origin by then
// (vanilla pops its block transform before calling super.render).
@Mixin(TntRenderer.class)
public class TntCountdownMixin {
	@Inject(method = "render(Lnet/minecraft/world/entity/item/PrimedTnt;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("TAIL"))
	private void originclient$fuseLabel(PrimedTnt tnt, float yaw, float partialTick, PoseStack poseStack,
										MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
		try {
			TntCountdown.render(tnt, partialTick, poseStack, buffers);
		} catch (Throwable ignored) {
			// a label must never take the world render down
		}
	}
}
