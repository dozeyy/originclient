package com.origin.client.client.mixin.loading;

import com.origin.client.client.gui.OriginShaders;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Marks the start of each core-shader reload for OriginShaders, so Origin's shaders
// are compiled once per reload instead of once per vanilla shader (see
// OriginShaders.reloadHookSeen). Own mixin in the isolated required:false loading
// config: if it ever fails to apply, OriginShaders simply compiles as before.
@Mixin(GameRenderer.class)
public abstract class ShaderReloadMixin {
	@Inject(method = "reloadShaders", at = @At("HEAD"))
	private void originclient$beginShaderReload(CallbackInfo ci) {
		OriginShaders.beginShaderReload();
	}
}
