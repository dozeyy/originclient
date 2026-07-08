package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginKeyBindings;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void originclient$applyZoom(Camera camera, float partialTick, boolean usePerspective, CallbackInfoReturnable<Double> cir) {
		// Zoom key comes from the mod-menu keybind (raw GLFW code), with the
		// vanilla-controls binding still honored as a fallback.
		if (com.origin.client.client.mods.Mods.on("zoom")
				&& (OriginClientMod.isRawKeyDown(com.origin.client.client.mods.Mods.keyCode("zoom", "key"))
					|| OriginKeyBindings.zoom.isDown())) {
			cir.setReturnValue(com.origin.client.client.mods.Mods.num("zoom", "fov"));
		}
	}
}
