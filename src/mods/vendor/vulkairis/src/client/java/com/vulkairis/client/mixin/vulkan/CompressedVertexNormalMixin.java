package com.vulkairis.client.mixin.vulkan;

import com.vulkairis.origin.terrain.TerrainNormals;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Origin (2026-11-17). Target: VulkanMod's {@code VertexBuilder.CompressedVertexBuilder.vertex(long ptr,
 * float x, float y, float z, int color, float u, float v, int light, int normal)}, at TAIL, after it has
 * written the 16-byte chunk vertex (colour int at ptr + 12, alpha in its top byte at ptr + 15).
 *
 * <p>Why: the compressed format has no normal and VulkanMod drops the {@code normal} argument, so pack
 * terrain lit side faces like tops. While a pack's terrain is linked ({@link TerrainNormals#ENCODE})
 * the alpha byte (always 1.0 for chunk geometry) is replaced by the octahedral-encoded quad normal,
 * which {@code TerrainVertexAdapter} decodes. Without a pack nothing is written.</p>
 *
 * <p>What breaks without it: pack terrain falls back to an up normal for every face (no side shading,
 * no per-face shadow bias).</p>
 */
@Mixin(targets = "net.vulkanmod.render.vertex.VertexBuilder$CompressedVertexBuilder", remap = false)
public class CompressedVertexNormalMixin {
    @Inject(method = "vertex(JFFFIFFII)V", at = @At("TAIL"))
    private void vulkairis$storeNormal(long ptr, float x, float y, float z, int color, float u, float v,
                                       int light, int normal, CallbackInfo ci) {
        if (TerrainNormals.ENCODE) {
            MemoryUtil.memPutByte(ptr + 15, (byte) TerrainNormals.encode(normal));
        }
    }
}
