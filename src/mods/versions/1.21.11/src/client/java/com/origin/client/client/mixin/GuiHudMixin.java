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

	// Color Saturation: grade the finished WORLD frame before any HUD is drawn,
	// so only world pixels are affected. HEAD of Gui.render is exactly that
	// moment. Fail-soft inside ColorGrade itself, but guarded here too — a grade
	// failure must never take the HUD down.
	@Inject(method = "render", at = @At("HEAD"))
	private void originclient$colorGrade(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		try {
			com.origin.client.client.render.ColorGrade.process(guiGraphics);
		} catch (Throwable t) {
			// never take the frame down over a colour grade
		}
	}

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

	// Locator Bar. Drawn from the TAIL of the hotbar layer — the same layer vanilla
	// draws the XP bar and its level number in — so the gems land over the bar
	// sprite by plain call order. (Drawing from a later hook lets HUD batching
	// reorder the sprite back over the gems; that was pixel-verified on 1.21.1.)
	@Inject(method = "renderHotbarAndDecorations", at = @At("TAIL"))
	private void originclient$locatorBar(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		try {
			com.origin.client.client.waypoints.WaypointHud.renderBars(guiGraphics);
		} catch (Throwable t) {
			// the locator bar must never take the HUD down
		}
	}
}
