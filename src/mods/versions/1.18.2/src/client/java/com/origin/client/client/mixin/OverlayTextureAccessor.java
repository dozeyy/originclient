package com.origin.client.client.mixin;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Hit Color repaints the red rows of vanilla's 16x16 overlay texture -- the
// texture every entity shader samples for the hurt flash (OverlayTexture.texture,
// private, decompiled 1.18.1 (the 1.18.2 code base)).
@Mixin(OverlayTexture.class)
public interface OverlayTextureAccessor {
	@Accessor("texture")
	DynamicTexture originclient$texture();
}
