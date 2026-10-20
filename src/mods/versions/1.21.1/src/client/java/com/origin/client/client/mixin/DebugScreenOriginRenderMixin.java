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
// Plus one line while Limit Far Shadows is on and a shader pack draws shadows.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$originRenderLine(CallbackInfoReturnable<List<String>> cir) {
		add(cir.getReturnValue(), OriginRender.debugLine());
		// Settings > Performance > Limit Far Shadows: how many far shadow casters it skipped last frame.
		add(cir.getReturnValue(), com.origin.client.client.render.ShadowCasterLimit.debugLine());
		// Settings > Performance > Off-Map Shadows: sections and entities kept out of the shadow map last frame.
		add(cir.getReturnValue(), com.origin.client.client.render.OffMapShadows.debugLine());
		// Settings > Performance > Half-Rate Shadows: shadow maps reused (not redrawn) in the last second.
		add(cir.getReturnValue(), com.origin.client.client.render.HalfRateShadows.debugLine());
		// Settings > Performance > Smart Terrain Updates: main chunk-graph walks reused per second + walk CPU time.
		add(cir.getReturnValue(), com.origin.client.client.render.SmartTerrainUpdates.debugLine());
	}

	private static void add(List<String> lines, String line) {
		if (line != null) {
			try {
				lines.add(line);
			} catch (UnsupportedOperationException ignored) {
				// an immutable list from another mod: skip the line
			}
		}
	}
}
