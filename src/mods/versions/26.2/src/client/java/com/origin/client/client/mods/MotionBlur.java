package com.origin.client.client.mods;

import com.origin.client.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

// Motion Blur: previous-frame accumulation post chains (the classic
// "phosphor" technique) loaded through vanilla's own post-effect pipeline.
// 1.21.11 note: the old private loadEffect + live "Amount" uniform are gone
// (PostChain uniforms are baked per pass now), so the 0..10 slider maps onto
// the ten baked chains, one per notch (motion_blur_1..10.json) instead of one
// live-uniform chain. Fully guarded: if the shader fails on some driver, the
// feature silently no-ops.
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

	private static int loadedStep = 0; // 0 = off, 1..10 = the slider notch's chain
	private static boolean broken = false;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		// 0..10 slider -> off, or the chain baked for that notch
		int step = amount < 0.5 || mc.level == null ? 0 : (int) Math.min(10, Math.round(amount));

		// The game empties the post-effect slot by itself (F5 and spectating a mob go
		// through checkEntityPostEffect). Watch the slot, not just our own flag, so
		// the blur comes back once it is free again (a plain id swap, no reload).
		Identifier current = mc.gameRenderer.currentPostEffect();
		if (loadedStep != 0 && !EFFECTS[loadedStep - 1].equals(current)) {
			loadedStep = 0;
		}
		if (step == loadedStep) {
			return;
		}
		try {
			if (step == 0) {
				if (isOurs(current)) {
					((GameRendererAccessor) mc.gameRenderer).originclient$clearPostEffect();
				}
			} else {
				if (current != null && !isOurs(current)) {
					return;   // vanilla's creeper/spider/enderman view (or another mod) owns the slot: wait
				}
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

	private static boolean isOurs(Identifier id) {
		for (Identifier e : EFFECTS) {
			if (e.equals(id)) {
				return true;
			}
		}
		return false;
	}
}
