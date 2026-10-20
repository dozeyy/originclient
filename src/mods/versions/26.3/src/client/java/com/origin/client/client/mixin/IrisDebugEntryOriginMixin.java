package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.ShadowTerrainStats;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F3 lines for the shader speed-ups (Limit Far Shadows, Off-Map Shadows, Half-Rate Shadows, and why
 * there is no Smart Terrain toggle here).
 * Target: Iris 1.11.7+mc26.3 IrisDebugEntry.display(DebugScreenDisplayer, Level, LevelChunk, LevelChunk)V
 * at RETURN (javap 2026-10-20). PER-VERSION (26.3): the F3 screen is the DebugScreenEntries system,
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
		String far = ShadowCasterLimit.debugLine();
		if (far != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, far);
		}
		String offMap = OffMapShadows.debugLine();
		if (offMap != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, offMap);
		}
		String halfRate = HalfRateShadows.debugLine();
		if (halfRate != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, halfRate);
		}
		String terrain = ShadowTerrainStats.debugLine();
		if (terrain != null) {
			displayer.addToGroup(ORIGINCLIENT$GROUP, terrain);
		}
	}
}