package com.origin.client.client.mixin;

import com.origin.client.client.render.terrain.OriginRender;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// One F3 line (left column) while Settings > Performance > Origin Render is on:
// what it's doing ("drawing 812 of 1430 solid sections") or why it isn't
// (shaderpack on, needs OpenGL 4.5, stopped after an error). Nothing when off.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$originRenderLine(CallbackInfoReturnable<List<String>> cir) {
		String line = OriginRender.debugLine();
		if (line != null) {
			try {
				cir.getReturnValue().add(line);
			} catch (UnsupportedOperationException ignored) {
				// an immutable list from another mod: skip the line
			}
		}
	}
}
