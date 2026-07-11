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

	private static boolean loaded = false;
	private static boolean broken = false;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		boolean want = amount >= 0.5;

		if (want != loaded) {
			try {
				if (want) {
					((GameRendererAccessor) mc.gameRenderer).originclient$loadEffect(EFFECT);
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
			// map 0..10 -> 0..0.92 blend of the previous frame (higher = blurrier)
			double blend = Math.min(0.92, Math.max(0.0, amount / 10.0) * 0.92);
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
		}
	}
}
