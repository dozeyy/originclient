package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.ItemScaleState;
import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Size Customizer: scales a dropped item's render by the player's per-item
// size. Gameplay, hitbox and pickup are untouched — this is render-only.
//
// PER-VERSION DELTA (1.21.11): 1.21.1 did the whole thing in one hook, because
// its render(ItemEntity, ...) still had the live entity to look the item id up
// from. Here rendering is split: extractRenderState(ItemEntity, state, float)
// has the entity, submit(state, PoseStack, ...) has only the baked state and no
// route back to the ItemStack. So the scale is resolved during extraction and
// stashed on the state through the ItemScaleState duck (see that interface for
// why it lives outside the mixin package).
//
// The push/pop pair is UNCONDITIONAL — unlike 1.21.1, which returned early from
// both halves when the mod was off. That was a latent imbalance: toggling the
// mod between the HEAD and the RETURN of the same call would push without
// popping (or vice versa) and corrupt the pose stack for the rest of the frame.
// Pushing always and only scaling when there is something to scale costs
// nothing and cannot desync.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityScaleMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void originclient$captureSize(ItemEntity entity, ItemEntityRenderState state, float partialTick,
										  CallbackInfo ci) {
		float s = 1.0f;
		if (Mods.on("itemsize")) {
			s = ItemSizes.get(BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()));
		}
		((ItemScaleState) state).originclient$setItemScale(s);
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void originclient$pushSize(ItemEntityRenderState state, PoseStack poseStack,
									   SubmitNodeCollector collector, CameraRenderState cameraRenderState,
									   CallbackInfo ci) {
		poseStack.pushPose();
		float s = ((ItemScaleState) state).originclient$getItemScale();
		if (s != ItemSizes.DEFAULT) {
			poseStack.scale(s, s, s);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void originclient$popSize(ItemEntityRenderState state, PoseStack poseStack,
									  SubmitNodeCollector collector, CameraRenderState cameraRenderState,
									  CallbackInfo ci) {
		poseStack.popPose();
	}
}
