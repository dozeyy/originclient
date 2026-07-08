package com.origin.client.client.mods;

import com.origin.client.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

// Motion Blur: a previous-frame accumulation post chain (the classic
// "phosphor" technique), loaded through vanilla's own post-effect pipeline
// so resize/processing come for free. Three strength variants are separate
// shader assets (a post chain's uniforms are baked into its JSON). The
// program/shader files live under the minecraft namespace (served from our
// jar) because pass program names can't carry a namespace; the post JSONs
// are properly namespaced. Everything is guarded: if the shader fails to
// load on some driver/version, the feature silently no-ops.
public final class MotionBlur {
	private static int applied = 0; // 0 = off, 1..3 = variant
	private static boolean broken = false;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		int desired = !broken && Mods.on("motionblur")
				? (int) Math.max(1, Math.min(3, Mods.num("motionblur", "amount"))) : 0;
		if (desired == applied) {
			return;
		}
		try {
			if (applied != 0 || desired == 0) {
				mc.gameRenderer.shutdownEffect();
			}
			if (desired != 0) {
				((GameRendererAccessor) mc.gameRenderer).originclient$loadEffect(
						ResourceLocation.fromNamespaceAndPath("originclient", "shaders/post/motion_blur_" + desired + ".json"));
			}
			applied = desired;
		} catch (Throwable t) {
			broken = true;
			applied = 0;
			com.origin.client.OriginClient.LOGGER.warn("Motion blur shader failed to load; feature disabled this session", t);
		}
	}
}
