package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.origin.flywheelvulkan.backend.VkDrawManager;

import dev.engine_room.flywheel.backend.engine.DrawManager;

/**
 * Target: Flywheel 1.0.6-44 {@code dev.engine_room.flywheel.impl.FlwDebugInfo}
 * {@code private static void addMeshDebugInfo(StringBuilder, DrawManager)} (javap on the pinned
 * jar). Flywheel-only types, so remap = false.
 *
 * <p>Why: that method calls {@code drawManager.meshPool().pooledMeshes()}. Flywheel's MeshPool is
 * GL (its constructor makes GL buffers), so origin:vulkan returns null there and keeps its meshes
 * in its own Vulkan pool. This prints that pool's count and skips the GL path.
 *
 * <p>If it stops applying: {@code /flywheel debug} (Flywheel's debug info command) throws a
 * NullPointerException while origin:vulkan is the backend. Rendering is unaffected. The plugin
 * only applies it on the pinned Flywheel build, and defaultRequire = 1 fails loudly at boot.
 */
@Mixin(targets = "dev.engine_room.flywheel.impl.FlwDebugInfo", remap = false)
abstract class FlwDebugInfoMixin {
	@Inject(method = "addMeshDebugInfo(Ljava/lang/StringBuilder;Ldev/engine_room/flywheel/backend/engine/DrawManager;)V",
			at = @At("HEAD"), cancellable = true, remap = false)
	private static void origin$vulkanMeshes(StringBuilder out, DrawManager<?> drawManager, CallbackInfo ci) {
		if (drawManager instanceof VkDrawManager vk) {
			out.append("\n- Meshes (origin:vulkan): ").append(vk.meshCount());
			ci.cancel();
		}
	}
}
