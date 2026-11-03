package com.origin.client.client.mixin.core;

import com.origin.client.client.RawMouseInput;
import net.minecraft.util.MouseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: MouseHelper, the one place 1.8.9 turns the mouse into camera motion.
// Why: swaps LWJGL's accelerated deltas for raw counts (RawMouseInput). RETURN, so
// vanilla still consumes Mouse.getDX/getDY. If this stops applying, 1.8.9 goes back
// to vanilla mouse movement, acceleration included; nothing breaks.
@Mixin(MouseHelper.class)
public abstract class MouseHelperRawMixin {
	@Shadow public int deltaX;
	@Shadow public int deltaY;

	@Inject(method = "mouseXYChange", at = @At("RETURN"))
	private void originclient$rawDeltas(CallbackInfo ci) {
		if (RawMouseInput.frame(deltaX, deltaY)) {
			deltaX = RawMouseInput.dx();
			deltaY = RawMouseInput.dy();
		}
	}

	@Inject(method = "grabMouseCursor", at = @At("RETURN"))
	private void originclient$dropMenuMotion(CallbackInfo ci) {
		RawMouseInput.drain();
	}
}
