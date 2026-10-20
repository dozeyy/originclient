package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.SmartTerrainUpdates;
import com.origin.client.client.render.terrain.OriginRender;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Target: DebugScreenOverlay.getGameInformation RETURN (the F3 left column).
// Why: one F3 line while Settings > Performance > Origin Render is on (what it's
// doing, e.g. "drawing 812 of 1430 solid sections", or why it isn't), plus one line
// each for the four shader speed-ups (Limit Far / Off-Map / Half-Rate Shadows,
// Smart Terrain Updates) while they run or when they can't. Nothing when off.
// What breaks if it stops applying: only the F3 lines go away; the features still run.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$originRenderLine(CallbackInfoReturnable<List<String>> cir) {
		List<String> lines = cir.getReturnValue();
		// Sodium and Iris are separate installs on this version: each line is
		// guarded on its own, so one linkage surprise costs that line only,
		// never the debug screen or the other lines.
		try { add(lines, OriginRender.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Limit Far Shadows: far shadow casters skipped last frame.
		try { add(lines, ShadowCasterLimit.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Off-Map Shadows: sections and entities kept out of the shadow map last frame.
		try { add(lines, OffMapShadows.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Half-Rate Shadows: shadow maps reused (not redrawn) in the last second.
		try { add(lines, HalfRateShadows.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Smart Terrain Updates: main chunk-graph walks reused per second + walk CPU time.
		try { add(lines, SmartTerrainUpdates.debugLine()); } catch (Throwable ignored) { }
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
