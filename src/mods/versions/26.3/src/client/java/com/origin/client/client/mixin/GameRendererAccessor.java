package com.origin.client.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

// 26.3: no invokers needed any more -- Motion Blur appends its chain to the
// public GameRenderer.getRequestedPostEffects() list (GameRendererMixin), and
// Camera.getFov() is public (26.2). Kept as an empty accessor so the mixin
// config keeps its 26.2 shape.
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
}
