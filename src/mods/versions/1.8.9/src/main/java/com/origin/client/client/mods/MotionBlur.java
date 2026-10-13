package com.origin.client.client.mods;

import com.origin.client.client.hooks.WorldHooks;
import com.origin.client.client.mixin.world.ShaderGroupAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;

// Motion Blur: a previous-frame accumulation post chain (the classic
// "phosphor" technique) loaded through vanilla's own post-effect pipeline. A
// single chain covers the whole range — the blend strength is a live `Amount`
// uniform driven by the 0..10 slider (0 = off, 10 = maximum), so the slider is
// smooth end to end instead of three fixed variants. Fully guarded: if the
// shader fails on some driver, the feature silently no-ops.
//
// 1.8.9: the chain runs in EntityRenderer's own post-effect slot (the one the
// spectator creeper/spider/enderman views and F4's "super secret settings" use):
// EntityRenderer.loadShader(ResourceLocation) is public here, stopUseShader()
// clears it, getShaderGroup() reads it back, and the uniform is set on each
// pass's ShaderManager (ShaderGroupAccessor reaches the private pass list). The
// chain's programs live in assets/minecraft/shaders/program (1.8.9's ShaderManager
// takes no namespace), the chain itself at originclient:shaders/post/motion_blur.json.
// With OptiFine shaders or Fast Render on (or the FBO option off) vanilla post
// chains can't run, so the blur stays off (WorldHooks.postChainsUsable()).
public final class MotionBlur {
	private static final ResourceLocation EFFECT =
			new ResourceLocation("originclient", "shaders/post/motion_blur.json");

	private static boolean loaded = false;
	private static boolean broken = false;
	/** Last frame's timestamp, so the blend can be derived from real elapsed time. */
	private static long lastNanos = 0L;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur") && WorldHooks.postChainsUsable())
				? Mods.num("motionblur", "amount") : 0;
		boolean want = amount >= 0.5 && mc.theWorld != null;

		if (loaded && !isOurs(mc)) {
			// something else (spectator view, F4) replaced the slot: let it be
			loaded = false;
		}
		if (want != loaded) {
			try {
				if (want) {
					if (mc.entityRenderer.getShaderGroup() != null) {
						return;   // another post effect owns the slot right now; try again later
					}
					mc.entityRenderer.loadShader(EFFECT);
					if (!isOurs(mc)) {
						// 1.8.9's loadShader swallows its IOException (logs, leaves the
						// slot empty) and no-ops without framebuffer support: latch off
						// instead of retrying (and logging) every tick.
						broken = true;
						com.origin.client.OriginClient.LOGGER.warn("Motion blur post chain did not load; feature disabled this session");
						return;
					}
				} else if (isOurs(mc)) {
					mc.entityRenderer.stopUseShader();
				}
				loaded = want && isOurs(mc);
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
			// 240fps, and it visibly lurches whenever the framerate moves.
			//
			// Deriving the blend from elapsed TIME fixes it. Treating the trail as
			// exponential decay with a time constant tau, the per-frame factor is
			// exp(-dt/tau) -- so the trail lasts the same wall-clock time at any
			// framerate, and stays smooth while the framerate swings.
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
				ShaderGroup effect = mc.entityRenderer.getShaderGroup();
				if (effect instanceof ShaderGroupAccessor) {
					for (Shader pass : ((ShaderGroupAccessor) effect).origin$listShaders()) {
						ShaderUniform u = pass.getShaderManager().getShaderUniform("Amount");
						if (u != null) {
							u.set((float) blend);
						}
					}
				}
			} catch (Throwable ignored) {
				// a different post effect may be active; ignore
			}
		} else {
			lastNanos = 0;
		}
	}

	private static boolean isOurs(Minecraft mc) {
		try {
			ShaderGroup g = mc.entityRenderer.getShaderGroup();
			return g != null && EFFECT.toString().equals(g.getShaderGroupName());
		} catch (Throwable t) {
			return false;
		}
	}
}
