package com.origin.client.client.mixin;

import com.origin.client.client.mods.GlintColorizer;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Once-a-frame texture upkeep for Hit Color and Glint Colorizer, on the render
// thread before anything of the frame is drawn. Both only re-upload when their
// colour actually changed, so a steady frame costs a couple of compares.
// `render` (1.17.1: render(float, long, boolean), the only method of that name)
// matched by name with a CallbackInfo-only handler (same as GameRendererMixin's zoom ease).
@Mixin(GameRenderer.class)
public class GameRendererFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$syncTextures(CallbackInfo ci) {
		HitColor.sync();
		GlintColorizer.sync();
	}
}
