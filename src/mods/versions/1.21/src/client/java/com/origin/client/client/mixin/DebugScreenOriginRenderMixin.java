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
// (shaderpack on, card too old, stopped after an error). Nothing when off.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$originRenderLine(CallbackInfoReturnable<List<String>> cir) {
		// Sodium is a separate install on this version: any linkage surprise
		// costs the one F3 line, never the debug screen
		try {
			String line = OriginRender.debugLine();
			if (line != null) {
				cir.getReturnValue().add(line);
			}
		} catch (Throwable ignored) {
			// an immutable list from another mod, or no Sodium: skip the line
		}
	}
}
