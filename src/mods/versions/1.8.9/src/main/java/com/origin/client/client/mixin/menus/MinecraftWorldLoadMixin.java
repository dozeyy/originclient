package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.OriginScreenRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

// Smooth world-loading frames for singleplayer. 1.8.9: while the integrated
// server prepares the spawn area, launchIntegratedServer's wait loop repaints
// the loading screen and then sleeps 200 ms -- five frames a second, so the
// rings and the bar stepped visibly (1.21.1 fixed the same "jumpy" complaint
// with RingField's scene clock). With the Origin scene up the loop sleeps 33 ms
// instead: ~30 frames a second, the rate OriginLoadingRenderer throttles to.
// The loop only polls the server's status, so polling more often changes
// nothing else.
// RISK (OptiFine replaces Minecraft): a constant hook may miss in OptiFine's
// copy of the method; defaultRequire 0 then keeps vanilla's 200 ms (still
// correct, just five frames a second). Watch dev-shots for "Mixin apply" lines.
@Mixin(Minecraft.class)
public abstract class MinecraftWorldLoadMixin {

	@ModifyConstant(method = "launchIntegratedServer", constant = @Constant(longValue = 200L))
	private long originclient$loadingFrameMs(long sleepMs) {
		return OriginScreenRenderer.isActive() ? 33L : sleepMs;
	}
}
