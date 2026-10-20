package com.origin.client.client.mixin;

import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: GuiRenderState.firstStratumAfterBlur (private int; Integer.MAX_VALUE until a
// blur is requested this frame, then the stratum index).
// Why: GuiGraphics.blurBeforeThisStratum() throws IllegalStateException when a blur was
// already placed this frame. OriginScreenRenderer.backdrop() reads this to skip the call
// instead of paying for a thrown exception every frame another mod blurred first. A plain
// getter, so nothing is allocated per frame.
// If this stops applying: the instanceof check in backdrop() fails, the guard is skipped,
// and the old try/catch path runs (the blur is still correct, just exception-driven).
@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
	@Accessor("firstStratumAfterBlur")
	int originclient$firstStratumAfterBlur();
}
