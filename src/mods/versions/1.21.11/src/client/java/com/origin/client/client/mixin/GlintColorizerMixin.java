package com.origin.client.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.MeshData;
import com.origin.client.client.mods.GlintColorizer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// Glint Colorizer (see GlintColorizer for the whole picture).
//
// 1.21.11 RenderType.draw(MeshData) (source-verified against decompiled
// rendertype/RenderType) is where every render type -- the four glint types
// included -- binds its pipeline (RenderPass.setPipeline(this.state.pipeline))
// and its textures (this.state.getTextures(), a fresh HashMap each call) before
// drawing. Three hooks, each a no-op for any render type that isn't a glint:
//   HEAD     -- Show Glint off: close the mesh unused and skip the draw;
//   getTextures -- swap Sampler0 for the recoloured glint;
//   setPipeline -- Lunar Blending's alpha-blended glint pipeline.
// Everything fails soft to vanilla's glint.
@Mixin(RenderType.class)
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
					target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup;getTextures()Ljava/util/Map;"))
	private Map<String, RenderSetup.TextureAndSampler> originclient$glintTexture(RenderSetup setup) {
		Map<String, RenderSetup.TextureAndSampler> textures = setup.getTextures();
		try {
			GlintColorizer.swapTexture(this, textures);
		} catch (Throwable ignored) {
			// keep vanilla's texture
		}
		return textures;
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
