package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only frame timing (OriginFrameLog, -Dorigin.frameLog=true). Target:
// Minecraft.runTick, the render thread's one-call-per-frame loop; HEAD marks a
// frame boundary and the runAllTasks pair times the queued tasks (the reload's
// apply steps run there). 26.x moved the frame itself into renderFrame: the
// GameRenderer extract + render pair is "render" and GpuSurface.present
// is the swap (javap-verified on 26.2). Every handler is one static-final check
// when the logger is off; if a target moves, only the logger loses that number
// (required:false loading config).
@Mixin(Minecraft.class)
public abstract class FrameLogMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void originclient$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		if (OriginFrameLog.ENABLED) {
			Minecraft mc = (Minecraft) (Object) this;
			OriginFrameLog.frame(mc.gui.overlay() != null);
		}
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
	private void originclient$tasksBegin(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.tasksBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
	private void originclient$tasksEnd(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.tasksEnd();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private void originclient$renderBegin(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.renderBegin();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
	private void originclient$renderEnd(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.renderEnd();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
	private void originclient$swapBegin(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.swapBegin();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void originclient$swapEnd(boolean advanceGameTime, CallbackInfo ci) {
		OriginFrameLog.swapEnd();
	}
}
