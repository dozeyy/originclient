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

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		boolean want = amount >= 0.5;

		// Vanilla empties the post-effect slot behind our back: F5 and spectating a mob
		// (checkEntityPostEffect) and every resource reload (F3+T, a pack change, the startup
		// reload). So the slot itself is the truth -- a field read and a String compare per
		// tick -- and the blur is put back whenever it went missing.
		if (loaded && !isOurs(mc)) {
			loaded = false;
		}
		if (want != loaded) {
			if (mc.getOverlay() != null) {
				return;   // a resource reload is running; try again once it has finished
			}
			try {
				if (want) {
					if (mc.gameRenderer.currentEffect() != null) {
						return;   // another post effect (spectating a creeper/spider/enderman) owns the slot: let it be
					}
					((GameRendererAccessor) mc.gameRenderer).originclient$loadEffect(EFFECT);
					if (!isOurs(mc)) {
						// loadEffect swallows a missing/bad shader (logs, leaves the slot empty):
						// latch off instead of retrying (and logging) every tick.
						broken = true;
						com.origin.client.OriginClient.LOGGER.warn("Motion blur post chain did not load; feature disabled this session");
						return;
					}
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

		if (loaded) {
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
				var effect = mc.gameRenderer.currentEffect();
				if (effect != null) {
					// 1.20 has no PostChain.setUniform — set "Amount" on each pass directly.
					for (PostPass pass : ((PostChainAccessor) effect).originclient$passes()) {
						pass.getEffect().safeGetUniform("Amount").set((float) blend);
					}
				}
			} catch (Throwable ignored) {
				// a different post effect may be active; ignore
			}
		} else {
			lastNanos = 0;
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
