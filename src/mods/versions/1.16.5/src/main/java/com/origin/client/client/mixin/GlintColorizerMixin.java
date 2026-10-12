package com.origin.client.client.mixin;

import com.origin.client.client.mods.GlintColorizer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Glint Colorizer: every enchantment-glint RenderType's texturing shard calls
// RenderStateShard.setupGlintTexturing(float scale) (private static; 8.0 for
// item models, 0.16 for entity models -- decompiled 1.16.5). It is the LAST
// glint-specific setup to run (CompositeState applies the texture and
// transparency shards before texturing), so its tail is where the glint's
// texture and blend can be swapped without touching any other render type.
//
// 1.16.5 has no shaders to tell an armor glint from an item glint by, so the
// RenderType whose state is being set up is recorded first: a RenderType's own
// setupRenderState() (RenderStateShard's, running its shard list) fires before
// any of its shards, and only RenderType.armorGlint() / armorEntityGlint() are
// the worn-armor glints. See GlintColorizer.
@Mixin(RenderStateShard.class)
public class GlintColorizerMixin {

	@Inject(method = "setupRenderState", at = @At("HEAD"))
	private void originclient$recordGlintType(CallbackInfo ci) {
		Object self = this;
		if (self instanceof RenderType) {
			GlintColorizer.armorTypeActive = self == RenderType.armorGlint() || self == RenderType.armorEntityGlint();
		}
	}

	@Inject(method = "setupGlintTexturing", at = @At("TAIL"))
	private static void originclient$recolorGlint(float scale, CallbackInfo ci) {
		try {
			GlintColorizer.onGlintSetup(scale);
		} catch (Throwable t) {
			// a glint must never take a frame down; vanilla's glint stays
		}
	}
}
