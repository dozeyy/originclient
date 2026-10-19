package com.origin.client.client.mixin.loading;

import com.origin.client.client.OriginStyle;
import com.origin.client.client.OriginFrameLog;
import com.origin.client.client.render.OriginScreenRenderer;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
// thread, exactly as vanilla would run it a moment later.
//
// It also HOLDS the queue (originclient$holdForMark): once render-thread reload
// work has been queued, no task runs until the loading mark has glided to rest
// on a clean pose, so the first long step can't freeze it mid-motion. The hold
// stops the WHOLE Minecraft render-thread queue (every pending task, not only
// reload steps), at most once per reload and for at most GATE_CAP_NS (600 ms of
// wall-clock time) even if the mark never reports rest; the settle itself takes
// ~280 ms (MarkMotion.SETTLE_MS). So the load can finish up to that much later.
// Any other time (no loading screen, a world loaded, Origin rendering off) the
// queue runs vanilla's way, untouched.
//
// Own mixin in the isolated required:false loading config: if the method ever
// moves, both the per-frame budget and the mark hold are skipped (the queue then
// runs vanilla's way and the mark may be caught mid-motion by a long step).
@Mixin(BlockableEventLoop.class)
public abstract class ReloadTaskBudgetMixin {
	/** Task time per frame while the startup load finishes (ns): leaves room for the frame itself at 60 fps. */
	private static final long BUDGET_NS = 8_000_000L;
	/** Longest the queue is ever held for the mark's settle (ns); the settle itself takes ~280 ms. */
	@Unique
	private static final long GATE_CAP_NS = 600_000_000L;
	// The reload the hold belongs to and when it began (each reload is held at most once).
	@Unique
	private static Object originclient$gateReload;
	@Unique
	private static long originclient$gateStart;

	@Shadow
	public abstract boolean pollTask();

	@Shadow
	@Final
	private Queue<Runnable> pendingRunnables;

	@Inject(method = "runAllTasks", at = @At("HEAD"), cancellable = true)
	private void originclient$budgetReloadTasks(CallbackInfo ci) {
		if (!((Object) this instanceof Minecraft mc) || !originclient$startupLoading(mc)) {
			return;
		}
		long end = System.nanoTime() + BUDGET_NS;
		while (true) {
			// Checked before EVERY task: one that runs now can queue the first
			// render-thread reload step, which must then wait for the mark too.
			if (originclient$holdForMark(mc)) {
				break;
			}
			// Dev-only step timing (OriginFrameLog); one static-final check when off.
			Object next = OriginFrameLog.ENABLED ? this.pendingRunnables.peek() : null;
			OriginFrameLog.pollBegin();
			boolean ran = this.pollTask();
			OriginFrameLog.pollEnd(next);
			if (!ran || System.nanoTime() >= end) {
				break;
			}
		}
		ci.cancel();
	}

	/**
	 * Hold the queue while the loading mark glides to rest (MarkMotion.SETTLE_MS),
	 * so the first long render-thread step (fonts, Shader Loader, a mod's 5 s atlas)
	 * lands on a still mark on a clean pose, never mid-motion. Once per reload and
	 * capped: a mark that never reports rest delays the load by at most GATE_CAP_NS.
	 */
	@Unique
	private static boolean originclient$holdForMark(Minecraft mc) {
		if (!OriginScreenRenderer.holdReloadTasksForMark()) {
			return false;
		}
		long now = System.nanoTime();
		Object reload = ((LoadingOverlayAccessor) mc.gui.overlay()).originclient$reload();
		if (originclient$gateReload != reload) {
			originclient$gateReload = reload;
			originclient$gateStart = now;
			OriginFrameLog.mark("render-thread reload tasks held while the mark settles");
		}
		return now - originclient$gateStart < GATE_CAP_NS;
	}

	/** The game's startup load is on screen (no world, no server) and still running. */
	private static boolean originclient$startupLoading(Minecraft mc) {
		if (mc.level != null || mc.getConnection() != null || !OriginScreenRenderer.isActive() || OriginStyle.vanillaMenus()
				|| !(mc.gui.overlay() instanceof LoadingOverlayAccessor overlay)) {   // 26.2+: the overlay lives on Minecraft.gui
			return false;
		}
		ReloadInstance reload = overlay.originclient$reload();
		return reload != null && !reload.isDone();
	}
}
