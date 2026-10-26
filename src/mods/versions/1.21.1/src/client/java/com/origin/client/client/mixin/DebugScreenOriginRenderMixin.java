package com.origin.client.client.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// F3 lines (left column) for the shader speed-ups that are on (Settings > Performance):
// Limit Far / Off-Map / Half-Rate Shadows, Smart Terrain Updates. Nothing when they are off.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$originRenderLine(CallbackInfoReturnable<List<String>> cir) {
		// Settings > Performance > Limit Far Shadows: how many far shadow casters it skipped last frame.
		add(cir.getReturnValue(), com.origin.client.client.render.ShadowCasterLimit.debugLine());
		// Settings > Performance > Off-Map Shadows: sections and entities kept out of the shadow map last frame.
		if (com.origin.client.client.render.SodiumIrisBuilds.SODIUM) {   // Sodium-typed class: proven Sodium only
			add(cir.getReturnValue(), com.origin.client.client.render.OffMapShadows.debugLine());
		}
		// Settings > Performance > Half-Rate Shadows: shadow maps reused (not redrawn) in the last second.
		add(cir.getReturnValue(), com.origin.client.client.render.HalfRateShadows.debugLine());
		// Settings > Performance > Smart Terrain Updates: main chunk-graph walks reused per second + walk CPU time.
		if (com.origin.client.client.render.SodiumIrisBuilds.SODIUM) {
			add(cir.getReturnValue(), com.origin.client.client.render.SmartTerrainUpdates.debugLine());
		}
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
