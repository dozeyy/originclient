package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.PackIcons;
import net.minecraft.client.resources.AbstractResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;

// SETTINGS > Performance > Pack Icon Downscale. AbstractResourcePack.getPackImage()
// is what the resource pack list turns into its icon texture (zip and folder packs;
// the built-in pack overrides it). RETURN, so whatever the pack produced is shrunk
// to 64 px before it reaches the GPU; any failure leaves the original image.
// If this stops applying, big pack icons upload at full size again (slower list).
@Mixin(AbstractResourcePack.class)
public abstract class PackIconMixin {
	@Inject(method = "getPackImage", at = @At("RETURN"), cancellable = true)
	private void originclient$smallIcon(CallbackInfoReturnable<BufferedImage> cir) {
		BufferedImage img = cir.getReturnValue();
		BufferedImage small = PackIcons.shrink(img);
		if (small != img) {
			cir.setReturnValue(small);
		}
	}
}
