package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

// Motion Blur: previous-frame accumulation post chains (the classic
// "phosphor" technique) run through vanilla's own post-effect pipeline.
// 1.21.11 note: PostChain uniforms are baked per pass, so the 0..10 slider maps
// onto the three baked chain variants (motion_blur_1/2/3.json).
// 26.3: vanilla keeps a per-frame LIST of requested post effects (rebuilt in
// GameRenderer.update); GameRendererMixin appends requested() to it. A chain that
// fails to load is dropped by vanilla itself (failedPostEffects), so the feature
// silently no-ops on a driver that can't run it.
public final class MotionBlur {
	private static final Identifier[] EFFECTS = {
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_1"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_2"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_3"),
	};

	private static int step = 0; // 0 = off, 1..3 = variant strength

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = Mods.on("motionblur") ? Mods.num("motionblur", "amount") : 0;
		// 0..10 slider -> off / light / medium / heavy
		step = amount < 0.5 ? 0 : (int) Math.min(3, Math.ceil(Math.min(10.0, amount) / 10.0 * 3.0));
	}

	/** The chain to run this frame, or null when Motion Blur is off. */
	public static Identifier requested() {
		return step == 0 ? null : EFFECTS[step - 1];
	}
}
