package com.vulkairis.client.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderTarget.class)
public class RenderTargetExtMixin implements Blaze3dRenderTargetExt {
    @Unique
    private int iris$depthBufferVersion = 0;
    @Unique
    private int iris$colorBufferVersion = 0;

    @Override
    public int iris$getDepthBufferVersion() {
        return iris$depthBufferVersion;
    }

    @Override
    public int iris$getColorBufferVersion() {
        return iris$colorBufferVersion;
    }
}
