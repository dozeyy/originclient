package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.SmartTerrainUpdates;
import com.origin.client.client.render.SodiumIrisBuilds;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Target: DebugScreenOverlay.getGameInformation RETURN (the F3 left column; javap of the 1.21.6 and 1.21.8
// client jar 2026-10-20: protected List<String> getGameInformation(), still the pre-1.21.9 F3 list).
// Why: one line each for the four shader speed-ups (Limit Far / Off-Map / Half-Rate Shadows, Smart
// Terrain Updates) while they run, or why one does nothing. Nothing when a feature is off.
// What breaks if it stops applying: only the F3 lines go away; the features still run.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$shaderSpeedLines(CallbackInfoReturnable<List<String>> cir) {
		List<String> lines = cir.getReturnValue();
		// Sodium and Iris are separate installs on this version: each line is guarded on its own,
		// so one linkage surprise costs that line only, never the debug screen or the other lines.
		// Settings > Performance > Limit Far Shadows: far shadow casters skipped last frame.
		try { add(lines, ShadowCasterLimit.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Off-Map Shadows: sections and entities kept out of the shadow map last frame.
		if (SodiumIrisBuilds.SODIUM) {
			try { add(lines, OffMapShadows.debugLine()); } catch (Throwable ignored) { }
		}
		// Settings > Performance > Half-Rate Shadows: shadow maps reused (not redrawn) in the last second.
		try { add(lines, HalfRateShadows.debugLine()); } catch (Throwable ignored) { }
		// Settings > Performance > Smart Terrain Updates: main chunk-graph walks replayed per second + walk CPU time.
		if (SodiumIrisBuilds.SODIUM) {
			try { add(lines, SmartTerrainUpdates.debugLine()); } catch (Throwable ignored) { }
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
