package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Nametag Tweaks: rescale + the page's toggles (hide-in-F1, Toggle All /
// Toggle Players keybinds). Cancel happens BEFORE the push so the RETURN pop
// (which a cancelled method never reaches) can't unbalance the pose stack.
@Mixin(EntityRenderer.class)
public class EntityNametagMixin {

	@Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
	private void originclient$scaleTagPush(Entity entity, Component displayName, PoseStack poseStack,
										   MultiBufferSource bufferSource, int packedLight, float partialTick,
										   CallbackInfo ci) {
		if (Mods.on("nametags")) {
			boolean player = entity instanceof Player;
			boolean f1 = Minecraft.getInstance().options.hideGui && Mods.bool("nametags", "hideInF1");
			if (OriginClientMod.nametagsHidden || (OriginClientMod.playerNametagsHidden && player) || f1) {
				ci.cancel();
				return;
			}
		}
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
