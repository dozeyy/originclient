package com.origin.client.client.mixin.core;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Target: EntityLivingBase.getLook(float), the ray the crosshair target is cast along
// (EntityRenderer.getMouseOver: the block outline, what a click hits).
// Why: 1.8.9's "mouse delay". For a living entity it follows the HEAD yaw
// (rotationYawHead), which the local player copies from its body yaw only once a
// tick, while the camera turns every frame (Entity.setAngles moves rotationYaw and
// prevRotationYaw together, so the camera's interpolated yaw already holds the new
// aim). The target therefore trails the crosshair by up to a tick. 1.9+ casts the
// local player's ray along the same rotation the camera shows; with General >
// Modern Mouse on, 1.8.9 does too (the camera's interpolated pitch and body yaw,
// exactly Entity.getLook). Other entities keep vanilla's ray.
// HEAD + cancel only (OptiFine-safe; EntityLivingBase isn't an OptiFine class).
// One field compare and a cached option read per call: no frame cost. If this
// stops applying, 1.8.9's own look delay is back; nothing breaks.
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseLookMixin {
	@Inject(method = "getLook", at = @At("HEAD"), cancellable = true)
	private void originclient$noLookDelay(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		try {
			Entity self = (Entity) (Object) this;
			if (self != Minecraft.getMinecraft().thePlayer || !Mods.bool(Mods.GENERAL_ID, "modernMouse")) {
				return;
			}
			float pitch = self.prevRotationPitch + (self.rotationPitch - self.prevRotationPitch) * partialTicks;
			float yaw = self.prevRotationYaw + (self.rotationYaw - self.prevRotationYaw) * partialTicks;
			// Entity.getVectorForRotation (protected there), the same lookup-table trig
			float cy = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
			float sy = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
			float cp = -MathHelper.cos(-pitch * 0.017453292F);
			float sp = MathHelper.sin(-pitch * 0.017453292F);
			cir.setReturnValue(new Vec3(sy * cp, sp, cy * cp));
		} catch (Throwable t) {
			// vanilla's ray
		}
	}
}
