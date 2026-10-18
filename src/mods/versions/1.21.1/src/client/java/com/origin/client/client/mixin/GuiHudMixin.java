package com.origin.client.client.mixin;

import com.origin.client.client.hud.HudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin's HUD (FPS/coords/keystrokes/etc.) as the TOP-MOST layer, so it draws
// over any third-party mod's HUD rather than fighting for z-order.
//
// This replaces the old HudRenderCallback registration in OriginClientMod:
// Fabric dispatches HudRenderCallback from inside Gui.render, and its firing
// order among mods is load-order-dependent — uncontrollable. Injecting at
// Gui.render RETURN with a high injector `order` puts Origin's draw after the
// whole vanilla HUD *and* after Fabric's callback dispatch (where every other
// mod paints), so Origin lands last = on top. (The LayeredDraw HUD API that
// would let a layer register "after everything" doesn't exist in 1.21.1's
// fabric-api — it's 1.21.4+ — so the mixin is the right lever here.)
//
// order = 2000 (default is 1000) beats other mods' default-order RETURN
// injectors. Honest limit: a mod that itself injects at RETURN with a still
// higher order could draw over us — that's the ceiling of what mixins can
// guarantee.
@Mixin(value = Gui.class, priority = 2000)
public class GuiHudMixin {

	@Inject(method = "render", at = @At("RETURN"), order = 2000)
	private void originclient$topHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		HudElements.renderAll(guiGraphics);

		// Tab Editor's custom player-list overlay, drawn here (top of the HUD) on our
		// own visibility schedule so it works even on a solo local server, where vanilla
		// refuses to render it. Vanilla's own draw is suppressed by PlayerTabOverlayMixin,
		// so there's never a double. Fail-soft — the HUD must never go down for this.
		if (com.origin.client.client.OriginClientMod.tabListVisible) {
			try {
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
				var acc = (com.origin.client.client.mixin.PlayerTabOverlayAccessor) mc.gui.getTabList();
				com.origin.client.client.hud.OriginTabList.render(guiGraphics, guiGraphics.guiWidth(),
						acc.originclient$getPlayerInfos(), acc.originclient$getHeader(), acc.originclient$getFooter(),
						com.origin.client.client.mods.Mods.on("tablist"));
			} catch (Throwable ignored) {
				// never take the HUD down over the tab overlay
			}
		}
	}

	// Color Saturation: grade the world at the HEAD of the HUD render — the world is
	// fully drawn by now, but the HUD and any open screen/menu paint AFTER this, so
	// only the game world is graded. Gui.render runs every in-game frame (the HUD
	// draws behind any open screen), so the grade stays live even behind the mod menu.
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$colorGrade(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		com.origin.client.client.render.ColorGrade.process(deltaTracker.getGameTimeDeltaPartialTick(true));
	}

	// Waypoint Locator Bar — drawn at the TAIL of the hotbar layer, i.e. in the SAME
	// HUD layer/batching context vanilla uses to draw the XP bar and the level number
	// over it. Drawing from Gui.render RETURN instead put our gems in a later batch
	// that HUD batching (ImmediatelyFast) reordered under the bar sprite — pixels
	// proved fills vanish wherever a sprite overlaps. Inside the layer, draw order
	// is authoritative, exactly like vanilla's own text-over-bar rendering.
	@Inject(method = "renderHotbarAndDecorations", at = @At("TAIL"))
	private void originclient$locatorBar(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		try {
			com.origin.client.client.waypoints.WaypointHud.renderBars(guiGraphics);
		} catch (Throwable t) {
			// the locator bar must never take the HUD down
		}
	}
}
