package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.SmartTerrainUpdates;
import com.origin.client.client.render.SodiumIrisBuilds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Target: DebugScreenOverlay.render(GuiGraphics) (1.21.11, javap 2026-10-20). PER-VERSION: 1.21.11's F3
 * is built from DebugScreenEntry objects -- there is no getGameInformation list any more. render() makes
 * one anonymous DebugScreenDisplayer, loops the enabled entries calling
 * DebugScreenEntry.display(displayer, Level, LevelChunk, LevelChunk) (offset 263), then lays the
 * collected lines out; the first List.isEmpty() call (offset 273) is the first thing after the loop.
 * <ul>
 * <li>WrapOperation on display: remembers this frame's displayer (the call itself runs unchanged).</li>
 * <li>Inject before List.isEmpty ordinal 0: adds one group, "originclient:shader_speedups", holding a
 *     line for each of the four shader speed-ups that is on (Limit Far / Off-Map / Half-Rate Shadows,
 *     Smart Terrain Updates). Groups are read later (offset 412+), so the group is laid out with the
 *     vanilla ones. Nothing is added when every feature is off.</li>
 * </ul>
 * Why not a registered DebugScreenEntry: entries are switched on per F3 profile by the player, so a
 * new one would be hidden by default.
 * What breaks if it stops applying: only the F3 lines go away; the features still run.
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOriginRenderMixin {
	@Unique
	private DebugScreenDisplayer originclient$displayer;

	@Unique
	private static final Identifier ORIGINCLIENT$GROUP = Identifier.fromNamespaceAndPath("originclient", "shader_speedups");

	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$resetDisplayer(GuiGraphics graphics, CallbackInfo ci) {
		originclient$displayer = null;
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntry;display(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
	private void originclient$captureDisplayer(DebugScreenEntry entry, DebugScreenDisplayer displayer, Level level,
			LevelChunk client, LevelChunk server, Operation<Void> original) {
		originclient$displayer = displayer;
		original.call(entry, displayer, level, client, server);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", ordinal = 0))
	private void originclient$shaderLines(GuiGraphics graphics, CallbackInfo ci) {
		DebugScreenDisplayer displayer = originclient$displayer;
		if (displayer == null) return;
		// PER-VERSION (1.21.9+): render() also runs with F3 closed whenever any entry is "Always On"; these
		// lines are F3-only (and allocate), so skip unless the overlay itself is open
		// (DebugScreenEntryList.isOverlayVisible(), javap 1.21.11 2026-10-20).
		if (!net.minecraft.client.Minecraft.getInstance().debugEntries.isOverlayVisible()) return;
		// Each line guarded on its own, so one linkage surprise costs that line only, never the debug screen.
		// OffMapShadows / SmartTerrainUpdates hold Sodium types: never touched when Sodium is disabled.
		ArrayList<String> lines = new ArrayList<>(4);
		try { add(lines, ShadowCasterLimit.debugLine()); } catch (Throwable ignored) { }
		if (SodiumIrisBuilds.SODIUM) {
			try { add(lines, OffMapShadows.debugLine()); } catch (Throwable ignored) { }
		}
		try { add(lines, HalfRateShadows.debugLine()); } catch (Throwable ignored) { }
		if (SodiumIrisBuilds.SODIUM) {   // on another Sodium build the line says which builds it needs
			try { add(lines, SmartTerrainUpdates.debugLine()); } catch (Throwable ignored) { }
		}
		if (lines.isEmpty()) return;
		try {
			displayer.addToGroup(ORIGINCLIENT$GROUP, lines);
		} catch (Throwable ignored) {
			// another mod's displayer: skip the lines, keep the debug screen
		}
	}

	@Unique
	private static void add(ArrayList<String> lines, String line) {
		if (line != null) lines.add(line);
	}
}
