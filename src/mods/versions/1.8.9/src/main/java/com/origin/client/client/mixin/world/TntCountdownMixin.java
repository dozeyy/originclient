package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.TntCountdown;
import net.minecraft.client.renderer.entity.RenderTNTPrimed;
import net.minecraft.entity.item.EntityTNTPrimed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TNT Countdown: after vanilla draws a lit TNT (RenderTNTPrimed.doRender; full
// descriptor so the synthetic Entity bridge isn't matched), draw the fuse label
// above it. 1.8.9 renderers get the entity's camera-relative x/y/z instead of a
// posed stack, and vanilla has popped its block matrix by RETURN.
@Mixin(RenderTNTPrimed.class)
public abstract class TntCountdownMixin {
	@Inject(method = "doRender(Lnet/minecraft/entity/item/EntityTNTPrimed;DDDFF)V", at = @At("RETURN"))
	private void originclient$fuseLabel(EntityTNTPrimed tnt, double x, double y, double z, float yaw,
										float partialTicks, CallbackInfo ci) {
		try {
			TntCountdown.render(tnt, x, y, z, partialTicks);
		} catch (Throwable ignored) {
			// a label must never take the world render down
		}
	}
}
