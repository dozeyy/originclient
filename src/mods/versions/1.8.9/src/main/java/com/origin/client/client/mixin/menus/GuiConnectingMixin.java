package com.origin.client.client.mixin.menus;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.multiplayer.GuiConnecting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// "Connecting to the server..." Unlike the pure loading screens this has a live
// Cancel button + status text, so render is NOT taken over: the Origin menu
// backdrop already comes from MenuHooks' DrawScreenEvent.Pre (no world while
// connecting), the Cancel button is Origin-skinned, and this only adds the
// sweeping bar on top at TAIL, after vanilla has drawn its text and button.
@Mixin(GuiConnecting.class)
public abstract class GuiConnectingMixin {

	// Vanilla anchors the two halves of this screen to DIFFERENT fractions of the
	// height: the status line at height/2 - 50, Cancel at height/4 + 132 (javap
	// 1.8.9). Above ~660 GUI px tall (GUI scale 2 on a 1440p screen = 720) Cancel
	// climbs into the status line and the Origin bar under it -- the overlap Will
	// hit joining a world (2026-10-20 playtest). Cancel is kept at least 36 px
	// under the status line's top (below the bar, OriginScreenRenderer.
	// renderConnectingBar) and on screen. Every height where vanilla's spot is
	// already clear keeps vanilla's spot. A miss leaves vanilla's layout.
	@Inject(method = "initGui", at = @At("TAIL"))
	private void originclient$clearCancel(CallbackInfo ci) {
		try {
			Object self = this;
			if (!(self instanceof GuiScreenAccessor)) {
				return;
			}
			int h = ((net.minecraft.client.gui.GuiScreen) self).height;
			for (net.minecraft.client.gui.GuiButton b : ((GuiScreenAccessor) self).originclient$buttonList()) {
				if (b.id == 0) {
					int clear = h / 2 - 50 + 36;
					b.yPosition = Math.min(Math.max(b.yPosition, clear), h - 24);
				}
			}
		} catch (Throwable t) {
			// vanilla's layout
		}
	}

	@Inject(method = "drawScreen", at = @At("TAIL"))
	private void originclient$originBar(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
		try {
			if (OriginStyle.vanillaMenusOn(this)) {
				return;   // vanilla mode: no sweeping bar
			}
			OriginScreenRenderer.renderConnectingBar(new Gfx());
		} catch (Throwable t) {
			// the vanilla connect screen as it is
		}
	}
}
