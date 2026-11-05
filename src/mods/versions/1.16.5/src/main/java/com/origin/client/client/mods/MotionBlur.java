package com.origin.client.client.mods;

import com.origin.client.client.mixin.GameRendererAccessor;
import com.origin.client.client.mixin.PostChainAccessor;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

// Motion Blur: a previous-frame accumulation post chain (the classic
// "phosphor" technique) loaded through vanilla's own post-effect pipeline. A
// single chain now covers the whole range — the blend strength is a live
// `Amount` uniform driven by the 0..10 slider (0 = off, 10 = maximum), so the
// slider is smooth end to end instead of three fixed variants. Fully guarded:
// if the shader fails on some driver, the feature silently no-ops.
public final class MotionBlur {
	private static final ResourceLocation EFFECT =
			new ResourceLocation("originclient", "shaders/post/motion_blur.json");
	private static final String EFFECT_NAME = EFFECT.toString();

	private static boolean loaded = false;
	private static boolean broken = false;
	/** Last frame's timestamp, so the blend can be derived from real elapsed time. */
	private static long lastNanos = 0L;
	/** The slider value tick() last read, used by frame() (no config lookup per frame). */
	private static double frameAmount = 0;
	// Re-apply backoff (same as 1.21.1): ticks counted here, the tick of the last load, and the
	// earliest tick a re-apply may run (see the slot-lost branch in tick()).
	private static int ticks = 0;
	private static int loadedAt = 0;
	private static int retryAt = 0;
	private static int backoff = 0;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		ticks++;
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		frameAmount = amount;
		boolean want = amount >= 0.5;

		// Vanilla empties the post-effect slot behind our back: F5 and spectating a mob
		// (checkEntityPostEffect) and every resource reload (F3+T, a pack change, the startup
		// reload). So the slot itself is the truth -- a field read and a String compare per
		// tick -- and the blur is put back whenever it went missing. A chain that keeps
		// vanishing right after a load (another mod emptying the slot every tick) backs off,
		// up to a minute, instead of recompiling the whole chain every tick.
		if (loaded && !isOurs(mc)) {
			loaded = false;
			backoff = (ticks - loadedAt < 100) ? Math.min(Math.max(backoff * 2, 20), 1200) : 0;
			retryAt = ticks + backoff;
		}
		if (want != loaded) {
			if (mc.getOverlay() != null) {
				return;   // a resource reload is running; try again once it has finished
			}
			try {
				if (want) {
					if (ticks < retryAt || mc.gameRenderer.currentEffect() != null) {
						return;   // backing off, or another post effect (spectating a creeper/spider/enderman) owns the slot
					}
					((GameRendererAccessor) mc.gameRenderer).originclient$loadEffect(EFFECT);
					if (!isOurs(mc)) {
						// loadEffect swallows a missing/bad shader (logs, leaves the slot empty):
						// latch off instead of retrying (and logging) every tick.
						broken = true;
						com.origin.client.OriginClient.LOGGER.warn("Motion blur post chain did not load; feature disabled this session");
						return;
					}
					loadedAt = ticks;
				} else {
					mc.gameRenderer.shutdownEffect();
				}
				loaded = want;
			} catch (Throwable t) {
				broken = true;
				loaded = false;
				com.origin.client.OriginClient.LOGGER.warn("Motion blur shader failed to load; feature disabled this session", t);
				return;
			}
		}

		if (!loaded) {
			lastNanos = 0;
		}
	}

	/** Once per rendered frame, before the frame is drawn. The shader blends the previous
	 *  frame in by `Amount` on EVERY frame, so the blend must come from THIS frame's
	 *  duration. Set from tick() it used the 50 ms gap between client ticks instead, which
	 *  made the trail ~4x shorter than designed at 60 FPS and shorter still at higher FPS. */
	public static void frame(Minecraft mc) {
		if (!loaded) {
			return;
		}
		double amount = frameAmount;
		// Frame-blend blur decays the previous frame by `blend` EVERY FRAME, so
		// a fixed blend means the trail's real length depends entirely on your
		// framerate: 0.92 leaves a long smear at 60fps and almost nothing at
		// 240fps, and it visibly lurches whenever the framerate moves. That's
		// what made it feel steppy rather than smooth.
		//
		// Deriving the blend from elapsed TIME fixes it. Treating the trail as
		// exponential decay with a time constant tau, the per-frame factor is
		// exp(-dt/tau) -- so the trail lasts the same wall-clock time at any
		// framerate, and stays smooth while the framerate swings. Same maximum
		// blur, but buttery instead of framerate-locked.
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 1.0 / 60.0 : (now - lastNanos) / 1_000_000_000.0;
		lastNanos = now;
		// Clamp dt so a hitch or a paused frame can't blow the trail away.
		dt = Math.min(0.1, Math.max(1.0 / 480.0, dt));

		// amount 1..10 -> tau 0.02s..0.20s. 0.20s matches the old maximum smear
		// at 60fps, so "10" still means what it used to.
		double tau = Math.max(0.0, amount / 10.0) * 0.20;
		double blend = tau <= 0.0 ? 0.0 : Math.exp(-dt / tau);
		// Cap below 1.0: at 1.0 the accumulator never decays and the screen
		// smears permanently.
		blend = Math.min(0.97, Math.max(0.0, blend));
		try {
			net.minecraft.client.renderer.PostChain effect = mc.gameRenderer.currentEffect();
			if (effect != null) {
				// 1.20 has no PostChain.setUniform — set "Amount" on each pass directly.
				for (PostPass pass : ((PostChainAccessor) effect).originclient$passes()) {
					pass.getEffect().safeGetUniform("Amount").set((float) blend);
				}
			}
		} catch (Throwable ignored) {
			// a different post effect may be active; ignore
		}
	}

	/** True when the game's post-effect slot holds Origin's blur chain (PostChain name = its location). */
	private static boolean isOurs(Minecraft mc) {
		try {
			net.minecraft.client.renderer.PostChain effect = mc.gameRenderer.currentEffect();
			return effect != null && EFFECT_NAME.equals(effect.getName());
		} catch (Throwable t) {
			return false;
		}
	}
}
