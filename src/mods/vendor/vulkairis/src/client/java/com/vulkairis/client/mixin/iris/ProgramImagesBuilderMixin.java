package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

/**
 * Mixin into Iris's ProgramImages$Builder to safely ignore extra image unit requests
 * rather than throwing IllegalStateException.
 */
@Mixin(value = ProgramImages.Builder.class, remap = false)
public class ProgramImagesBuilderMixin {
    @Shadow private int nextImageUnit;
    @Shadow @Final private int maxImageUnits;

    @Inject(method = "addTextureImage", at = @At("HEAD"), cancellable = true)
    private void vulkairis$guardAddTextureImage(IntSupplier texture, InternalTextureFormat format, String name, CallbackInfo ci) {
        if (this.nextImageUnit >= this.maxImageUnits) {
            ci.cancel();
        }
    }
}
