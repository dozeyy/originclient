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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nametag Tweaks: rescale + the page's toggles (hide-in-F1, Toggle All /
// Toggle Players keybinds). Cancel happens BEFORE the push so the RETURN pop
// (which a cancelled method never reaches) can't unbalance the pose stack.
@Mixin(EntityRenderer.class)
public class EntityNametagMixin {

	// Third Person Nametag: your own tag is normally hidden (you're the camera
	// entity). Force it visible for the local player while in a third-person
	// view so you can see it in F5. Fail-soft: only ever flips a false to true.
	// 1.21.9–1.21.11 all use shouldShowName(Entity, double) — the squared
	// camera distance. (A dual-hook attempt failed: Mixin throws "Invalid
	// descriptor" for the non-matching signature even with require=0, so the
	// build must carry exactly the one shape its API era uses.)
	@Inject(method = "shouldShowName", at = @At("RETURN"), cancellable = true)
	private void originclient$ownNametag(Entity entity, double distSq, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !Mods.on("nametags") || !Mods.bool("nametags", "thirdPerson")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player && !mc.options.getCameraType().isFirstPerson()) {
			cir.setReturnValue(true);
		}
	}

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
			// There is no "scale" option, so num() returns 0 — guard it to 1 so
			// the tag never collapses to nothing (the "can't see my nametag" bug).
			float s = (float) Mods.num("nametags", "scale");
			if (s <= 0f) {
				s = 1f;
			}
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
