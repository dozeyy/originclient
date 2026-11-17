package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PBRTextureManager.class, remap = false)
public class PBRTextureManagerMixin {

    @Inject(method = "close()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void onCloseSafely(CallbackInfo ci) {
        ci.cancel();
    }
}
