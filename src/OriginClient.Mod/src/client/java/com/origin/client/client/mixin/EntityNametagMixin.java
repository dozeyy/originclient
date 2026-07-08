package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Nametag Tweaks: rescales every rendered nametag around its own anchor.
// push at HEAD + pop at every RETURN (not TAIL — renderNameTag has early
// returns, and an unbalanced pose stack is a hard crash).
@Mixin(EntityRenderer.class)
public class EntityNametagMixin {

	@Inject(method = "renderNameTag", at = @At("HEAD"))
	private void originclient$scaleTagPush(Entity entity, Component displayName, PoseStack poseStack,
										   MultiBufferSource bufferSource, int packedLight, float partialTick,
										   CallbackInfo ci) {
		poseStack.pushPose();
		if (Mods.on("nametags")) {
			float s = (float) Mods.num("nametags", "scale");
			poseStack.scale(s, s, s);
		}
	}

	@Inject(method = "renderNameTag", at = @At("RETURN"))
	private void originclient$scaleTagPop(Entity entity, Component displayName, PoseStack poseStack,
										  MultiBufferSource bufferSource, int packedLight, float partialTick,
										  CallbackInfo ci) {
		poseStack.popPose();
	}
}
