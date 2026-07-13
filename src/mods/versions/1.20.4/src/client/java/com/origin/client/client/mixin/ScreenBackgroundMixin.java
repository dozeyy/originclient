package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Origin background (charcoal + rings + grain) behind every out-of-world
// menu -- options, world select, server list, create world, etc. -- so the
// whole menu tree reads as one interface with the main menu. The website's
// mouse-follow spotlight is drawn on EVERY menu (Will), right after the
// backdrop so it sits over the background but under the widgets that render
// afterward.
//
// Background replacement is gated on Minecraft.level == null: in-game screens
// (pause, in-game options) keep vanilla's blurred-world backdrop -- replacing
// that would hide the world the player is standing in. The cursor glow is NOT
// gated: out-of-world it draws inside the cancel path, in-world via the TAIL
// hook over vanilla's blur (TAIL never runs when HEAD cancelled, so the two
// paths are exclusive and the glow draws exactly once either way).
//
// Two hooks (javap-confirmed against the mapped 1.20.4 Screen):
//  - renderBackground(GuiGraphics,int,int,float): 1.20.2+ signature (mouse +
//    partialTick came back). Cancelled and replaced wholesale. TitleScreen
//    overrides this method, so its own mixin path (which already draws the
//    glow) is unaffected -- no double glow there.
//  - renderDirtBackground(GuiGraphics): still exists on 1.20.4 (removed only
//    in 1.20.5) and some screens call it directly, so it's suppressed too.
// 1.20.4 has no static renderMenuBackgroundTexture helper (that arrived
// later); the list-strip suppression lives in AbstractSelectionListMixin.
// priority 2000: Origin's shared-screen background wins over other UI mods.
@Mixin(value = Screen.class, priority = 2000)
public class ScreenBackgroundMixin {

	// The Origin backdrop itself is drawn ONCE per menu by the beforeRender screen
	// event (OriginClientMod) -- before any screen content, so it clears the frame
	// even for list screens (SelectWorld, multiplayer) that never call
	// renderBackground and would otherwise show the previous screen bleeding
	// through. These two hooks only CANCEL vanilla's dirt so it can't paint over
	// that backdrop. Fail-soft: if Origin rendering is unhealthy (isActive false),
	// nothing is cancelled and vanilla's backdrop returns.
	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	// Some screens (GenericDirtMessageScreen -- the "Preparing..." transition on
	// world create/load, and any screen forcing a solid dirt backdrop) call
	// renderDirtBackground directly instead of through renderBackground, so the
	// hook above never fires for them. Suppress that dirt too; the beforeRender
	// event has already drawn the Origin backdrop underneath.
	@Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressVanillaDirt(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (Minecraft.getInstance().level == null && OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}

	// In-world menus (pause, in-game options) keep vanilla's blurred-world
	// backdrop -- the beforeRender event skips them (level != null) -- so the
	// Origin cursor spotlight is drawn here to stay consistent with out-of-world
	// menus. Out-of-world this never runs: the HEAD hook cancels renderBackground
	// before TAIL is reached.
	@Inject(method = "renderBackground", at = @At("TAIL"))
	private void originclient$inWorldGlow(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		OriginScreenRenderer.renderTitleCursorGlow(guiGraphics, mouseX, mouseY, originclient$hoveringClickable());
	}

	// Same hover test the title screen uses: bloom the spotlight while any
	// visible widget is hovered, matching the website's hover targets.
	private boolean originclient$hoveringClickable() {
		Screen self = (Screen) (Object) this;
		for (GuiEventListener child : self.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.isHovered()) {
				return true;
			}
		}
		return false;
	}
}
