package com.origin.client.client.mixin;

import com.origin.client.client.render.OriginScreenRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// On 1.20.4 Iris draws the old-style dirt bands above/below its shader-pack
// lists ITSELF: both list classes override renderDecorations and blit vanilla's
// Screen.BACKGROUND_LOCATION dirt across the areas outside the list, plus the
// edge shading gradients (bytecode-confirmed against iris-1.7.2+mc1.20.4 --
// it's Iris's backport of the pre-1.20.3 renderTopAndBottom look, drawn
// unconditionally in-world and out). Vanilla-targeted suppression
// (AbstractSelectionListMixin) can't reach an override in Iris's own classes,
// so this cancels those two overrides directly -- same result as 1.20/1.20.1,
// where the bands came from vanilla's renderTopAndBottom and are suppressed:
// out-of-world the lists sit on the Origin backdrop, in-world the world stays
// fully visible. renderDecorations here draws ONLY the bands + gradients
// (vanilla's scrollbar draws in renderWidget), so cancelling loses nothing.
//
// The method is Minecraft-inherited, so its runtime name differs by namespace:
// mojmap "renderDecorations" in the dev client, intermediary "method_25320" in
// a real launcher install. remap=false + both names listed = whichever exists
// matches; require = 0 + @Pseudo keep this a clean no-op if Iris is absent or
// reshapes these internals. Gated on isActive() only (like
// AbstractSelectionListMixin): a broken Origin renderer gives vanilla back.
@Pseudo
@Mixin(targets = {
		"net.irisshaders.iris.gui.element.ShaderPackSelectionList",
		"net.irisshaders.iris.gui.element.ShaderPackOptionList"
}, remap = false)
public class IrisListDecorationsMixin {

	@Inject(method = {"renderDecorations", "method_25320"}, at = @At("HEAD"),
			cancellable = true, remap = false, require = 0)
	private void originclient$noDirtBands(CallbackInfo ci) {
		if (OriginScreenRenderer.isActive()) {
			ci.cancel();
		}
	}
}
