package com.origin.client.client.mixin;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: GuiRenderState's private `firstStratumAfterBlur` (javap-verified on
// 26.1.2, 26.2 and 26.3): Integer.MAX_VALUE until this frame's one blur, reset
// every frame. Why: OriginScreenRenderer.blurOnce skips a second blur in the
// same frame instead of letting blurBeforeThisStratum throw. If it stops
// applying: blurOnce catches that throw instead, so nothing breaks.
@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
	@Accessor("firstStratumAfterBlur")
	int originclient$firstStratumAfterBlur();
}
