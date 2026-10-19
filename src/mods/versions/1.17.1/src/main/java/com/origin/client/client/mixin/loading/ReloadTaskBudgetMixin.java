package com.origin.client.client.mixin.loading;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The last stretch of the startup load froze the loading screen for up to a
// second right before the main menu (Will 2026-10-18: "it freezes ... when your
// about to load into the main menu"). Measured on dev-shots: once every reload
// listener has prepared, all their "apply" steps (fonts, sounds, models, shader
// packs...) are queued on the render thread together, and vanilla runs the whole
// queue inside ONE frame (Minecraft.runTick -> runAllTasks), so the animation
// stops until the last one is done.
//
// While the startup loading screen is up, this runs that queue on a per-frame
// time budget instead: at least one task, then more until BUDGET_NS is spent, and
// the rest next frame. Each task still runs whole and in order on the render
// thread, exactly as vanilla would run it a moment later, so nothing about the
// load changes except that the loading screen keeps drawing between steps.
// Any other time (no loading screen, a world loaded, Origin rendering off) the
// queue runs vanilla's way, untouched.
//
// Own mixin in the isolated required:false loading config: if the method ever
// moves, only this smoothing is skipped.
@Mixin(BlockableEventLoop.class)
public abstract class ReloadTaskBudgetMixin {
	/** Task time per frame while the startup load finishes (ns): leaves room for the frame itself at 60 fps. */
	private static final long BUDGET_NS = 8_000_000L;

	@Shadow
	public abstract boolean pollTask();

	@Shadow
	@org.spongepowered.asm.mixin.Final
	private java.util.Queue<Runnable> pendingRunnables;

	@Inject(method = "runAllTasks", at = @At("HEAD"), cancellable = true)
	private void originclient$budgetReloadTasks(CallbackInfo ci) {
		if (!((Object) this instanceof Minecraft mc) || !originclient$startupLoading(mc)) {
			return;
		}
		long end = System.nanoTime() + BUDGET_NS;
		while (true) {
			// Dev-only step timing (OriginFrameLog); one static-final check when off.
			Object next = com.origin.client.client.OriginFrameLog.ENABLED ? this.pendingRunnables.peek() : null;
			com.origin.client.client.OriginFrameLog.pollBegin();
			boolean ran = this.pollTask();
			com.origin.client.client.OriginFrameLog.pollEnd(next);
			if (!ran || System.nanoTime() >= end) {
				break;
			}
		}
		ci.cancel();
	}

	/** The game's startup load is on screen (no world, no server) and still running. */
	private static boolean originclient$startupLoading(Minecraft mc) {
		if (mc.level != null || mc.getConnection() != null || !OriginScreenRenderer.isActive()
				|| !(mc.getOverlay() instanceof LoadingOverlayAccessor overlay)) {
			return false;
		}
		ReloadInstance reload = overlay.originclient$reload();
		return reload != null && !reload.isDone();
	}
}
