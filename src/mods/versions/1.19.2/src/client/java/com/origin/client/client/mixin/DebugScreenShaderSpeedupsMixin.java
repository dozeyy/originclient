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

// Target: DebugScreenOverlay.getGameInformation()List<String> at RETURN (vanilla 1.19.2 Mojmap; the F3 left column).
// Why: one F3 line each for the shader speed-ups while they run (Limit Far / Off-Map / Half-Rate Shadows),
//   or why one is off; plus the reason line for Smart Terrain Updates, which this version doesn't need
//   (Sodium 0.4 already skips the main chunk walk while nothing moves). Nothing when a feature is off.
// What breaks if it stops applying: only the F3 lines go away; the features still run.
@Mixin(DebugScreenOverlay.class)
public class DebugScreenShaderSpeedupsMixin {
	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void originclient$shaderSpeedupLines(CallbackInfoReturnable<List<String>> cir) {
		List<String> lines = cir.getReturnValue();
		// Sodium and Iris are separate installs on this version: each line is guarded on its own, so one
		// linkage surprise costs that line only, never the debug screen or the other lines.
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
