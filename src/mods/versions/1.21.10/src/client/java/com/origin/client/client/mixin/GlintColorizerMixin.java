package com.origin.client.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.origin.client.client.mods.GlintColorizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Glint Colorizer (see GlintColorizer for the whole picture).
//
// 1.21.10 RenderType.CompositeRenderType.draw(MeshData) (source-verified against
// decompiled 1.21.10 RenderType) is where every render type -- the four glint
// types included -- runs its state shards (the texture shard puts the glint
// texture in RenderSystem shader-texture slot 0), binds its pipeline
// (RenderPass.setPipeline(this.renderPipeline)) and binds each slot's texture
// (RenderSystem.getShaderTexture(i) -> bindSampler("Sampler" + i)). Three hooks,
// each a no-op for any render type that isn't a glint:
//   HEAD             -- Show Glint off: close the mesh unused and skip the draw;
//   getShaderTexture -- Sampler0 becomes the recoloured glint;
//   setPipeline      -- Lunar Blending's alpha-blended glint pipeline.
// CompositeRenderType is package-private, hence the string target (its draw is
// the only implementation of the abstract RenderType.draw). Everything fails
// soft to vanilla's glint.
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public class GlintColorizerMixin {

	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private void originclient$hideGlint(MeshData mesh, CallbackInfo ci) {
		try {
			if (GlintColorizer.hide(this)) {
				mesh.close();
				ci.cancel();
			}
		} catch (Throwable ignored) {
			// vanilla draws its glint
		}
	}

	@Redirect(method = "draw",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;getShaderTexture(I)Lcom/mojang/blaze3d/textures/GpuTextureView;"))
	private GpuTextureView originclient$glintTexture(int index) {
		GpuTextureView vanilla = com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(index);
		try {
			return GlintColorizer.texture(this, index, vanilla);
		} catch (Throwable ignored) {
			return vanilla;
		}
	}

	@ModifyArg(method = "draw",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
			index = 0)
	private RenderPipeline originclient$glintPipeline(RenderPipeline vanilla) {
		try {
			return GlintColorizer.pipeline(this, vanilla);
		} catch (Throwable t) {
			return vanilla;
		}
	}
}
