package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.HudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin's HUD (FPS/coords/keystrokes/etc.) as the TOP-MOST layer, so it draws
// over any third-party mod's HUD rather than fighting for z-order. Injecting at
// Gui.render RETURN with a high injector `order` puts Origin's draw after the
// whole vanilla HUD *and* after Fabric's HudRenderCallback dispatch (where every
// other mod paints), so Origin lands last = on top.
//
// Pre-1.20 shape: Gui.render(PoseStack, float partialTick); the hotbar and the
// XP bar are separate methods here (1.21.1 draws both in renderHotbarAndDecorations).
@Mixin(value = Gui.class, priority = 2000)
public class GuiHudMixin {

	@Inject(method = "render", at = @At("RETURN"), order = 2000)
	private void originclient$topHud(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		Gfx guiGraphics = new Gfx(poseStack);
		HudElements.renderAll(guiGraphics);

		// Tab Editor's custom player-list overlay, drawn here (top of the HUD) on our
		// own visibility schedule so it works even on a solo local server, where vanilla
		// refuses to render it. Vanilla's own draw is suppressed by PlayerTabOverlayMixin,
		// so there's never a double. Fail-soft — the HUD must never go down for this.
		if (com.origin.client.client.OriginClientMod.tabListVisible) {
			try {
				Minecraft mc = Minecraft.getInstance();
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
	private void originclient$colorGrade(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		com.origin.client.client.render.ColorGrade.process(partialTick);
	}

	// Waypoint Locator Bar. In Survival the gems go on right after vanilla's own XP
	// bar + level number, inside the same method, so plain call order puts them on
	// top of the bar sprite (drawing from render RETURN let HUD batching reorder the
	// gem fills under the sprite on 1.21.1 — pixel-verified there).
	@Inject(method = "renderExperienceBar", at = @At("TAIL"))
	private void originclient$locatorBarOverXp(PoseStack poseStack, int x, CallbackInfo ci) {
		try {
			com.origin.client.client.waypoints.WaypointHud.renderBars(new Gfx(poseStack));
		} catch (Throwable t) {
			// the locator bar must never take the HUD down
		}
	}

	// Creative has no XP bar (renderExperienceBar is never called), so the bar hosts
	// itself: WaypointHud blits its own copy of the empty XP strip right after the
	// hotbar and lays the gems on it.
	@Inject(method = "renderHotbar", at = @At("TAIL"))
	private void originclient$locatorBarNoXp(float partialTick, PoseStack poseStack, CallbackInfo ci) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gameMode != null && !mc.gameMode.hasExperience()) {
				com.origin.client.client.waypoints.WaypointHud.renderBars(new Gfx(poseStack));
			}
		} catch (Throwable t) {
			// the locator bar must never take the HUD down
		}
	}
}
