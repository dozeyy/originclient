package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only (OriginFrameLog, -Dorigin.frameLog=true): times the two parts of the
// loading overlay's completion: onFinish (the startup's "game loaded" step, which
// builds the title screen) and the screen's init right after. Target (26.x, as
// 1.21.10+): LoadingOverlay.tick, the Consumer.accept (ordinal 0, the success
// path) and Screen.init(II)V calls. Off = one static-final check.
@Mixin(LoadingOverlay.class)
public abstract class FrameLogOverlayMixin {
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0))
	private void originclient$finishBegin(CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 0, shift = At.Shift.AFTER))
	private void originclient$finishEnd(CallbackInfo ci) {
		OriginFrameLog.sectionEnd("onFinish");
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(II)V"))
	private void originclient$initBegin(CallbackInfo ci) {
		OriginFrameLog.sectionBegin();
	}

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init(II)V", shift = At.Shift.AFTER))
	private void originclient$initEnd(CallbackInfo ci) {
		OriginFrameLog.sectionEnd("screen.init");
	}
}
