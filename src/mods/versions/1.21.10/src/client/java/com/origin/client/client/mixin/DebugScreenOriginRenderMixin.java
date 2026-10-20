package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import com.origin.client.client.render.SmartTerrainUpdates;
import com.origin.client.client.render.SodiumIrisBuilds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Target: DebugScreenOverlay.render(GuiGraphics) (1.21.10 Mojmap, javap 2026-10-20). PER-VERSION (1.21.9+):
 * F3 is built from DebugScreenEntry objects -- there is no getGameInformation list any more. render() makes
 * one anonymous DebugScreenDisplayer (offset 192), loops the enabled entries calling
 * DebugScreenEntry.display(displayer, Level, LevelChunk, LevelChunk) (offset 259), then lays the
 * collected lines out; the first List.isEmpty() call (offset 269) is the first thing after the loop
 * (the loop's exit branch at 218 jumps to 267). Same shape as 1.21.11, but the group key is still a
 * ResourceLocation here (1.21.11 renamed it Identifier).
 * <ul>
 * <li>WrapOperation on display: remembers this frame's displayer (the call itself runs unchanged).</li>
 * <li>Inject before List.isEmpty ordinal 0: adds one group, "originclient:shader_speedups", holding a
 *     line for each of the four shader speed-ups that is on (Limit Far / Off-Map / Half-Rate Shadows,
 *     Smart Terrain Updates). Groups are read later (Map.values at offset 414), so the group is laid out
 *     with the vanilla ones. Nothing is added when every feature is off.</li>
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
	private static final ResourceLocation ORIGINCLIENT$GROUP = ResourceLocation.fromNamespaceAndPath("originclient", "shader_speedups");

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
		// PER-VERSION (1.21.9+): render() also runs with F3 closed whenever an entry is set to "Always On"
		// (e.g. the FPS line), so the Origin lines appear only while the F3 screen itself is open
		// (DebugScreenEntryList.isF3Visible, javap 2026-10-20; review 2026-10-20).
		if (!Minecraft.getInstance().debugEntries.isF3Visible()) return;
		// Each line guarded on its own, so one linkage surprise costs that line only, never the debug
		// screen. OffMapShadows and SmartTerrainUpdates link Sodium types, so they are only touched while
		// Sodium is loaded (it is a separate install the player can disable). The list is made only when
		// there is a line to show.
		ArrayList<String> lines = null;
		try { lines = add(lines, ShadowCasterLimit.debugLine()); } catch (Throwable ignored) { }
		if (SodiumIrisBuilds.SODIUM) {
			try { lines = add(lines, OffMapShadows.debugLine()); } catch (Throwable ignored) { }
		}
		try { lines = add(lines, HalfRateShadows.debugLine()); } catch (Throwable ignored) { }
		if (SodiumIrisBuilds.SODIUM) {
			try { lines = add(lines, SmartTerrainUpdates.debugLine()); } catch (Throwable ignored) { }
		}
		if (lines == null) return;
		try {
			displayer.addToGroup(ORIGINCLIENT$GROUP, lines);
		} catch (Throwable ignored) {
			// another mod's displayer: skip the lines, keep the debug screen
		}
	}

	@Unique
	private static ArrayList<String> add(ArrayList<String> lines, String line) {
		if (line == null) return lines;
		if (lines == null) lines = new ArrayList<>(4);
		lines.add(line);
		return lines;
	}
}
