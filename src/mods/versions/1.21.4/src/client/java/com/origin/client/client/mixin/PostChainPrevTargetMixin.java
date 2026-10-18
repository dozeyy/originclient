package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.origin.client.client.mods.MotionBlur;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// 1.21.2-1.21.4 Motion Blur: keeps the chains' last-frame target alive across
// frames (why: see MotionBlur). PostChain.addToFrame makes each declared target
// with createInternal(id.toString(), descriptor); for MotionBlur.PREV_TARGET it
// imports the Origin-owned texture instead. Every other target -- vanilla's and
// any other mod's -- goes through untouched. Fail-soft: any error falls back to
// vanilla's pooled target (blur just stops accumulating, the frame still draws).
@Mixin(PostChain.class)
public class PostChainPrevTargetMixin {
	@Unique private static boolean originclient$warned = false;

	@WrapOperation(method = "addToFrame", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"))
	private ResourceHandle<?> originclient$keepMotionPrev(FrameGraphBuilder builder, String name, ResourceDescriptor<?> descriptor, Operation<ResourceHandle<?>> original) {
		if (MotionBlur.PREV_TARGET.equals(name)) {
			try {
				return builder.importExternal(name, MotionBlur.prevTarget());
			} catch (Throwable t) {
				if (!originclient$warned) {
					originclient$warned = true;
					com.origin.client.OriginClient.LOGGER.warn("Motion blur: last-frame target unavailable; blur won't accumulate", t);
				}
			}
		}
		return original.call(builder, name, descriptor);
	}
}
