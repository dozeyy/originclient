package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only frame timing (OriginFrameLog, -Dorigin.frameLog=true). Target:
// Minecraft.runTick, the render thread's one-call-per-frame loop. HEAD marks a
// frame boundary; the before/after pairs time the three parts a hitch can hide
// in: the queued tasks (the reload's apply steps run here), the GUI/world render,
// and the buffer swap (where a software or busy GPU makes the CPU wait).
// Every handler is one static-final check when the logger is off. If any target
// moves, only the logger loses that number (required:false loading config).
@Mixin(Minecraft.class)
public abstract class FrameLogMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void originclient$frameStart(boolean renderLevel, CallbackInfo ci) {
		if (OriginFrameLog.ENABLED) {
			OriginFrameLog.frame(((Minecraft) (Object) this).getOverlay() != null);
		}
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
	private void originclient$tasksBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.tasksBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
	private void originclient$tasksEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.tasksEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private void originclient$renderBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.renderBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
	private void originclient$renderEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.renderEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
	private void originclient$swapBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.swapBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V", shift = At.Shift.AFTER))
	private void originclient$swapEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.swapEnd();
	}
}
