package com.vulkairis.client.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.pbr.TextureTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSystem.class, priority = 1100, remap = false)
public class VulkairisRenderSystemMixin {

    @Inject(method = "_setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("HEAD"), remap = false)
    private static void vulkairis$onSetShaderTextureLocation(int unit, ResourceLocation location, CallbackInfo ci) {
        if (location != null) {
            try {
                Minecraft client = Minecraft.getInstance();
                if (client != null && client.getTextureManager() != null) {
                    AbstractTexture texture = client.getTextureManager().getTexture(location);
                    if (texture != null) {
                        TextureTracker.INSTANCE.onSetShaderTexture(unit, texture.getId());
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Inject(method = "_setShaderTexture(II)V", at = @At("HEAD"), remap = false)
    private static void vulkairis$onSetShaderTextureId(int unit, int textureId, CallbackInfo ci) {
        try {
            TextureTracker.INSTANCE.onSetShaderTexture(unit, textureId);
        } catch (Exception ignored) {
        }
    }
}
