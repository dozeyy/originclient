package com.origin.client.client.mixin;

import com.origin.client.client.mods.GlintColorizer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Glint Colorizer (see GlintColorizer for the whole picture).
//
// 26.2: RenderType.draw(MeshData) is gone; every render type now resolves its
// pipeline and textures into a PreparedRenderType via prepare() (javap, 26.2),
// which the staged vertex buffer then draws with. One RETURN hook swaps that
// record for the four glint types (recoloured / hidden Sampler0, Lunar
// Blending's pipeline). A no-op for every other render type; fails soft to
// vanilla's glint.
@Mixin(RenderType.class)
public class GlintColorizerMixin {

	@Inject(method = "prepare", at = @At("RETURN"), cancellable = true)
	private void originclient$glint(CallbackInfoReturnable<PreparedRenderType> cir) {
		try {
			PreparedRenderType vanilla = cir.getReturnValue();
			PreparedRenderType swapped = GlintColorizer.prepare(this, vanilla);
			if (swapped != vanilla) {
				cir.setReturnValue(swapped);
			}
		} catch (Throwable ignored) {
			// vanilla draws its glint
		}
	}
}
