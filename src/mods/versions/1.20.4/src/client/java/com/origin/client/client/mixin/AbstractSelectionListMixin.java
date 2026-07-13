package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.20 option/world/server lists tile their OWN dirt background — behind the
// rows (gated by the `renderBackground` flag) and as top/bottom strips over the
// title/button areas (gated by `renderTopAndBottom`). Together those cover the
// whole screen, painting over the Origin backdrop — which is why Video Settings
// / world-select / server-list showed vanilla dirt while flat button screens
// (Create World) looked right. So we flip both flags off while an Origin menu
// backdrop is active, letting the list sit transparently on it.
//
// But some list screens (SelectWorldScreen, JoinMultiplayerScreen, ...) NEVER
// call Screen.renderBackground themselves — they relied on the list's OPAQUE
// dirt to clear the frame each render. With that dirt suppressed, nothing clears
// the frame, so the PREVIOUS screen (the title's wordmark + buttons) bleeds
// through. To make every list screen its own clean scene, we draw the opaque
// Origin backdrop ourselves at render HEAD (full-screen fill -> frame cleared)
// before the transparent list draws over it. On screens that DO call
// renderBackground this simply redraws the same opaque backdrop (harmless).
//
// This runs IN-WORLD too (not gated on level == null): in-world list screens —
// notably Iris's ShaderPackScreen over a loaded world — draw the list's dirt
// top/bottom strips (renderTopAndBottom) over the game, since that block in
// AbstractSelectionList.render has no level check. That's the dirt band behind
// the shader screen's title/buttons. In-world we don't draw the opaque Origin
// backdrop (the world stays visible); the screen's own renderBackground supplies
// vanilla's darkening gradient, and the transparent list sits cleanly on it —
// matching how every other Origin surface reads on that version.
//
// Fail-soft: gated only on isActive() (a broken Origin renderer leaves vanilla
// dirt untouched).
@Mixin(AbstractSelectionList.class)
public class AbstractSelectionListMixin {

	@Shadow private boolean renderBackground;

	// 1.20.3+ has no renderTopAndBottom (the dirt header/footer strips are
	// gone) and list drawing moved from render() to renderWidget(); the list
	// dirt tile AND its edge gradients both gate on this one field.
	@Inject(method = "renderWidget", at = @At("HEAD"))
	private void originclient$transparentOnOrigin(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (OriginScreenRenderer.isActive()) {
			this.renderBackground = false;
		}
	}
}
