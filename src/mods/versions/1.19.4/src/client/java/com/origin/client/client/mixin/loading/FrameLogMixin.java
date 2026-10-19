package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginFrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dev-only frame timing (OriginFrameLog, -Dorigin.frameLog=true). Target:
// Minecraft.runTick(Z), the render thread's one-call-per-frame loop. HEAD marks a
// frame boundary; the before/after pairs time the three parts a hitch can hide
// in: the queued tasks (the reload's apply steps run here), the GUI/world render,
// and the buffer swap (where a software or busy GPU makes the CPU wait).
// Pre-1.21 GameRenderer.render takes (float partialTick, long nanos, boolean)
// (javap-verified on this module's mapped jar; 1.21.1's DeltaTracker form is later).
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
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"))
	private void originclient$renderBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.renderBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V", shift = At.Shift.AFTER))
	private void originclient$renderEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.renderEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
	private void originclient$swapBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.swapBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V", shift = At.Shift.AFTER))
	private void originclient$swapEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.swapEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
	private void originclient$tickBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.tickBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER))
	private void originclient$tickEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.tickEnd();
	}

	// The frame limiter (javap: runTick -> RenderSystem.limitDisplayFPS(I), a
	// glfwWaitEventsTimeout loop; menus cap at 60 fps).
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V"))
	private void originclient$waitBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.waitBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V", shift = At.Shift.AFTER))
	private void originclient$waitEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.waitEnd();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
	private void originclient$blitBegin(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.blitBegin();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V", shift = At.Shift.AFTER))
	private void originclient$blitEnd(boolean renderLevel, CallbackInfo ci) {
		OriginFrameLog.blitEnd();
	}
}
