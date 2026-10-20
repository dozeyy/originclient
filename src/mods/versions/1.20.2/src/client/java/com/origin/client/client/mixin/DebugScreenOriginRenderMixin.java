package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.SmartTerrainUpdates;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Target: DebugScreenOverlay.getGameInformation()Ljava/util/List; RETURN (the F3 left column;
// protected on MC 1.20.2, javap of the Loom jar 2026-10-20).
// Why: one F3 line each for the four shader speed-ups (Limit Far / Off-Map / Half-Rate Shadows,
// Smart Terrain Updates) while they run, or why one is off. Nothing when a feature is off.
// (1.20.4 has no Origin Render, so unlike 1.21.1 there is no Origin Render line.)
// What breaks if it stops applying: only the F3 lines go away; the features still run.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenOriginRenderMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$shaderSpeedLines(CallbackInfoReturnable<List<String>> cir) {
		List<String> lines = cir.getReturnValue();
		// Sodium and Iris are separate installs on this version: each line is guarded on its
		// own, so one linkage surprise costs that line only, never the debug screen.
		try { add(lines, ShadowCasterLimit.debugLine()); } catch (Throwable ignored) { }
		try { add(lines, OffMapShadows.debugLine()); } catch (Throwable ignored) { }
		try { add(lines, HalfRateShadows.debugLine()); } catch (Throwable ignored) { }
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
