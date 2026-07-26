package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Nametag Tweaks: rescale + the page's toggles (hide-in-F1, Toggle All /
// Toggle Players keybinds). Cancel happens BEFORE the push so the RETURN pop
// (which a cancelled method never reaches) can't unbalance the pose stack.
//
// PER-VERSION DELTA (1.21.11): EntityRenderer.renderNameTag NO LONGER EXISTS —
// entity rendering became extract-then-submit, and the name tag now goes out
// through submitNameTag(S, PoseStack, SubmitNodeCollector, CameraRenderState).
// This file targeted the old name until 2026-07-26, and because the module sets
// "defaultRequire": 0 that failed in TOTAL SILENCE: compile-clean, boot-clean,
// zero "Mixin apply failed", and every one of these toggles simply did nothing.
// Two consequences of the new shape, both handled below:
//   - There is no Entity parameter any more, only the extracted render state,
//     so "is this a player?" is answered by the state's own type
//     (AvatarRenderState is 1.21.11's player render state — Mojang renamed
//     Player -> Avatar in the render layer).
//   - shouldShowName still takes the live Entity, so that inject is unchanged.
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

	@Inject(method = "submitNameTag", at = @At("HEAD"), cancellable = true)
	private void originclient$scaleTagPush(EntityRenderState state, PoseStack poseStack,
										   SubmitNodeCollector collector, CameraRenderState cameraRenderState,
										   CallbackInfo ci) {
		if (Mods.on("nametags")) {
			boolean player = state instanceof AvatarRenderState;
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

	@Inject(method = "submitNameTag", at = @At("RETURN"))
	private void originclient$scaleTagPop(EntityRenderState state, PoseStack poseStack,
										  SubmitNodeCollector collector, CameraRenderState cameraRenderState,
										  CallbackInfo ci) {
		poseStack.popPose();
	}
}
