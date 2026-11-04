package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

// Motion Blur: previous-frame accumulation post chains (the classic
// "phosphor" technique) run through vanilla's own post-effect pipeline.
// 1.21.11 note: PostChain uniforms are baked per pass, so the 0..10 slider maps
// onto the ten baked chains, one per notch (motion_blur_1..10.json).
// 26.3: vanilla keeps a per-frame LIST of requested post effects (rebuilt in
// GameRenderer.update); GameRendererMixin appends requested() to it. A chain that
// fails to load is dropped by vanilla itself (failedPostEffects), so the feature
// silently no-ops on a driver that can't run it.
public final class MotionBlur {
	// Each chain's Blend is 1.21.1's live curve at 60 fps (exp(-dt/tau), tau = notch/10 * 0.2 s):
	// 0.43 at 1 up to 0.92 at 10, so every notch is a real step, as on 1.21.1 (JP-3).
	private static final Identifier[] EFFECTS = {
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_1"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_2"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_3"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_4"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_5"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_6"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_7"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_8"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_9"),
			Identifier.fromNamespaceAndPath("originclient", "motion_blur_10"),
	};

	private static int step = 0; // 0 = off, 1..10 = the slider notch's chain

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = Mods.on("motionblur") ? Mods.num("motionblur", "amount") : 0;
		// 0..10 slider -> off, or the chain baked for that notch
		step = amount < 0.5 ? 0 : (int) Math.min(10, Math.round(amount));
	}

	/** The chain to run this frame, or null when Motion Blur is off. */
	public static Identifier requested() {
		return step == 0 ? null : EFFECTS[step - 1];
	}
}
