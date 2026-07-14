package com.origin.client.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.21.11 removed Camera's public position getter; hitbox distance culling
// and the block-entity culling mixin still need the exact camera Vec3.
@Mixin(Camera.class)
public interface CameraAccessor {
	@Accessor("position")
	Vec3 originclient$position();
}
