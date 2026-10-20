package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.ShadowTerrainStats;
import com.origin.client.client.render.SmartTerrainUpdates;
import com.origin.client.client.render.SodiumIrisBuilds;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3 lines for the shader speed-ups (Limit Far Shadows, Off-Map Shadows, Half-Rate Shadows, and Smart
 * Terrain Updates on Sodium 0.8 / why there is no such toggle on Sodium 0.9).
 * Target: IrisDebugEntry.display(DebugScreenDisplayer, Level, LevelChunk, LevelChunk)V at RETURN, the same
 * in both Iris builds this jar meets (1.10.9+mc26.1.1 and 1.11.4+mc26.1.2, javap 2026-10-20). PER-VERSION (26.x): the F3 screen is the DebugScreenEntries system,
 * whose register method is private; Iris registers this entry ("iris:iris") and turns it on in the
 * overlay by default, and it writes its lines with displayer.addToGroup(iris:name, line). These lines go
 * into that same group, under Iris's own. The displayer comes in through @Local(argsOnly = true), no
 * declared argument list. @Pseudo + string target: without Iris the mixin is skipped (and there would
 * be nothing to report: every line is about the shader pass).
 * What breaks if it stops applying: only these F3 lines are missing; every feature still works.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gui.debug.IrisDebugEntry", remap = false)
public abstract class IrisDebugEntryOriginMixin {
	@Unique
	private static final Identifier ORIGINCLIENT$GROUP = Identifier.fromNamespaceAndPath("iris", "name");

	@Inject(method = "display", at = @At("RETURN"), remap = false)
	private void originclient$shaderSpeedLines(CallbackInfo ci, @Local(argsOnly = true) DebugScreenDisplayer displayer) {
		// The overlay also runs entries with F3 closed when one is set "Always On" (Iris's could be): these
		// lines are F3-only and allocate, so skip unless the overlay itself is open
		// (DebugScreenEntryList.isOverlayVisible(), javap 26.1.2 2026-10-20).
		if (!net.minecraft.client.Minecraft.getInstance().debugEntries.isOverlayVisible()) {
			return;
		}
		String far = ShadowCasterLimit.debugLine();
		if (far != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, far);
		}
		// OffMapShadows / SmartTerrainUpdates hold Sodium types: never touched when Sodium is disabled.
		String offMap = SodiumIrisBuilds.SODIUM ? OffMapShadows.debugLine() : null;
		if (offMap != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, offMap);
		}
		String halfRate = HalfRateShadows.debugLine();
		if (halfRate != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, halfRate);
		}
		// Sodium 0.8.9 (26.1 / 26.1.1) runs Smart Terrain Updates; Sodium 0.9.2 (26.1.2) has it built in.
		// (On another 0.8.x build SmartTerrainUpdates.debugLine says which builds it needs.)
		String terrain = SodiumIrisBuilds.SODIUM_09 ? ShadowTerrainStats.debugLine()
				: SodiumIrisBuilds.SODIUM_08 ? SmartTerrainUpdates.debugLine() : null;
		if (terrain != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, terrain);
		}
	}
}