package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.origin.client.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

// Motion Blur: previous-frame accumulation post chains (the classic
// "phosphor" technique) loaded through vanilla's own post-effect pipeline.
// The old private loadEffect + live "Amount" uniform are gone in this era
// (PostChain uniforms are baked per pass), so the 0..10 slider maps onto the
// ten baked chains, one per notch (motion_blur_1..10.json). Fully guarded: if the
// shader fails on some driver, the feature silently no-ops.
//
// 1.21.2-1.21.4 only: post-chain targets are frame-graph internals, taken from
// a pool each frame and handed back at its end (the "persistent" target flag
// arrived in 1.21.5), and a pass whose output nothing reads later that frame is
// culled. So a chain cannot keep last frame by itself here. The chains name
// their last-frame target PREV_TARGET, and PostChainPrevTargetMixin swaps it
// for the Origin-owned texture below; imported as external, the pass that
// writes it is never culled and its contents survive into the next frame.
public final class MotionBlur {
	public static final String PREV_TARGET = "originclient:motion_prev";
	// Each chain's Blend is 1.21.1's live curve at 60 fps (exp(-dt/tau), tau = notch/10 * 0.2 s):
	// 0.43 at 1 up to 0.92 at 10, so every notch is a real step, as on 1.21.1 (JP-3).
	private static final ResourceLocation[] EFFECTS = {
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_1"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_2"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_3"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_4"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_5"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_6"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_7"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_8"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_9"),
			ResourceLocation.fromNamespaceAndPath("originclient", "motion_blur_10"),
	};

	private static int loadedStep = 0; // 0 = off, 1..10 = the slider notch's chain
	private static boolean broken = false;
	private static TextureTarget prev;

	private MotionBlur() {
	}

	public static void tick(Minecraft mc) {
		double amount = (!broken && Mods.on("motionblur")) ? Mods.num("motionblur", "amount") : 0;
		int step = amount < 0.5 || mc.level == null ? 0 : (int) Math.min(10, Math.round(amount));

		// The game empties the post-effect slot by itself (F5 and spectating a mob go
		// through checkEntityPostEffect). Watch the slot, not just our own flag, so
		// the blur comes back once it is free again (a plain id swap, no reload).
		ResourceLocation current = mc.gameRenderer.currentPostEffect();
		if (loadedStep != 0 && !EFFECTS[loadedStep - 1].equals(current)) {
			loadedStep = 0;
			releasePrev();   // never blend a frame from before the gap
		}
		if (step == loadedStep) {
			return;
		}
		try {
			if (step == 0) {
				if (isOurs(current)) {
					((GameRendererAccessor) mc.gameRenderer).originclient$clearPostEffect();
				}
				releasePrev();   // so turning it back on never blends a stale frame
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
			try { ((GameRendererAccessor) mc.gameRenderer).originclient$clearPostEffect(); releasePrev(); } catch (Throwable ignored) { }
			com.origin.client.OriginClient.LOGGER.warn("Motion blur shader failed to load; feature disabled this session", t);
		}
	}

	// The last-frame texture, matched to the main framebuffer (render thread only:
	// called from PostChain.addToFrame). A new target is cleared to alpha 0, which
	// the shader reads as "no previous frame" -- so a resize just skips one blend.
	public static RenderTarget prevTarget() {
		RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
		if (prev == null || prev.width != main.width || prev.height != main.height) {
			releasePrev();
			prev = new TextureTarget(main.width, main.height, false);
			com.origin.client.OriginClient.LOGGER.info("Motion blur: last-frame target attached ({}x{})", main.width, main.height);
		}
		return prev;
	}

	private static boolean isOurs(ResourceLocation id) {
		for (ResourceLocation e : EFFECTS) {
			if (e.equals(id)) {
				return true;
			}
		}
		return false;
	}

	private static void releasePrev() {
		if (prev != null) {
			prev.destroyBuffers();
			prev = null;
		}
	}
}
