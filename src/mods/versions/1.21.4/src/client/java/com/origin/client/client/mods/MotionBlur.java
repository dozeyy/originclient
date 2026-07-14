package com.origin.client.client.mods;

import com.origin.client.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

// Motion Blur: previous-frame accumulation post chains (the classic
// "phosphor" technique) loaded through vanilla's own post-effect pipeline.
// The old private loadEffect + live "Amount" uniform are gone in this era
// (PostChain uniforms are baked per pass), so the 0..10 slider maps onto the
// three baked chain variants (motion_blur_1/2/3.json). Fully guarded: if the
// shader fails on some driver, the feature silently no-ops.
public final class MotionBlur {
	private static final ResourceLocation[] EFFECTS = {
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_1"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_2"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_3"),
	};

	private static int loadedStep = 0; // 0 = off, 1..3 = variant strength
	private static boolean broken = false;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		int step = amount < 0.5 ? 0 : (int) Math.min(3, Math.ceil(Math.min(10.0, amount) / 10.0 * 3.0));

		if (step == loadedStep) {
			return;
		}
		try {
			if (step == 0) {
				((GameRendererAccessor) mc.gameRenderer).originclient$clearPostEffect();
			} else {
				((GameRendererAccessor) mc.gameRenderer).originclient$setPostEffect(EFFECTS[step - 1]);
			}
			loadedStep = step;
		} catch (Throwable t) {
			broken = true;
			loadedStep = 0;
			try { ((GameRendererAccessor) mc.gameRenderer).originclient$clearPostEffect(); } catch (Throwable ignored) { }
			com.origin.client.OriginClient.LOGGER.warn("Motion blur shader failed to load; feature disabled this session", t);
		}
	}
}
