package com.origin.client.client.mixin;

import com.origin.client.client.mods.GlintColorizer;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Glint Colorizer: every enchantment-glint RenderType's texturing shard calls
// RenderStateShard.setupGlintTexturing(float scale) (private static; 8.0 for
// item models, 0.16 for armor/entity models -- decompiled 1.19.4). It is the
// LAST glint-specific setup to run (CompositeState applies texture, shader and
// blend shards before texturing), so its tail is where the glint's texture and
// blend can be swapped without touching any other render type. See GlintColorizer.
@Mixin(RenderStateShard.class)
public class GlintColorizerMixin {
	@Inject(method = "setupGlintTexturing", at = @At("TAIL"))
	private static void originclient$recolorGlint(float scale, CallbackInfo ci) {
		try {
			GlintColorizer.onGlintSetup(scale);
		} catch (Throwable t) {
			// a glint must never take a frame down; vanilla's glint stays
		}
	}
}
