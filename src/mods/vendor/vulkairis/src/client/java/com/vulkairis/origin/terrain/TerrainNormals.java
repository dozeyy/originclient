package com.vulkairis.origin.terrain;

import com.vulkairis.bridge.VulkanModTerrainHook;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.vertex.TerrainRenderType;

/**
 * Origin (2026-11-17): the quad normal for pack terrain, carried in the alpha byte of VulkanMod's packed
 * chunk vertex colour.
 *
 * <p>VulkanMod's compressed chunk vertex (16 bytes: position, light, UV, RGBA8 colour) has no normal:
 * {@code CompressedVertexBuilder.vertex} receives the quad's packed normal and drops it. Pack terrain
 * then lit every face as a top face (MakeUp: sides as bright as the grass on top). The normal is exact
 * per quad (the block model's face normal, or the computed one for rotated quads), so it is kept:
 * octahedral-encoded into 4+4 bits (15 levels per axis, so the six block face directions decode exactly and
 * diagonal plant quads within about 10 degrees) in place of the colour alpha, which
 * vanilla chunk geometry always sets to 1.0.</p>
 *
 * <p>Only while a pack's terrain programs are linked ({@link #ENCODE}); VulkanMod's own terrain shader
 * multiplies by that alpha, so a flip of the switch rebuilds every chunk. The pack-side decode
 * ({@link TerrainVertexAdapter}) treats 0xFF (a chunk meshed before the switch) as "up" and always
 * uses alpha 1.0.</p>
 */
public final class TerrainNormals {
    /** Byte that marks "no normal stored" (vanilla alpha 255); never produced by {@link #encode}. */
    public static final int NONE = 0xFF;

    /** True while chunk meshes carry the encoded normal. Read by chunk builder threads. */
    public static volatile boolean ENCODE;

    private TerrainNormals() {
    }

    /**
     * Render thread, once a frame: encode while the pack's gbuffers_terrain is linked; a change
     * rebuilds all chunks so no mesh mixes the two alpha meanings for longer than one rebuild.
     */
    public static void update() {
        boolean want;
        try {
            want = VulkanModTerrainHook.isBound() && VulkanModTerrainHook.getPackPipeline(TerrainRenderType.SOLID) != null;
        } catch (Throwable t) {
            want = false;
        }
        if (want == ENCODE) {
            return;
        }
        ENCODE = want;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    /** VulkanMod's packed normal (signed bytes x, y, z at bits 0, 8, 16) as one octahedral byte. */
    public static int encode(int packedNormal) {
        float x = (byte) packedNormal;
        float y = (byte) (packedNormal >> 8);
        float z = (byte) (packedNormal >> 16);
        float s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (s == 0f) {
            return NONE;
        }
        float px = x / s;
        float py = y / s;
        if (z < 0f) {
            float ox = (1f - Math.abs(py)) * (px >= 0f ? 1f : -1f);
            float oy = (1f - Math.abs(px)) * (py >= 0f ? 1f : -1f);
            px = ox;
            py = oy;
        }
        int qx = Math.round((px * 0.5f + 0.5f) * 14f);
        int qy = Math.round((py * 0.5f + 0.5f) * 14f);
        return Math.min(Math.max(qx, 0), 14) | (Math.min(Math.max(qy, 0), 14) << 4);
    }

    /** Java mirror of the GLSL decode in {@link TerrainVertexAdapter}, for tests. */
    static float[] decode(int b) {
        if (b == NONE) {
            return new float[]{0f, 1f, 0f};
        }
        float px = (b & 15) / 14f * 2f - 1f;
        float py = (b >> 4) / 14f * 2f - 1f;
        float nz = 1f - Math.abs(px) - Math.abs(py);
        float t = Math.max(-nz, 0f);
        px += px >= 0f ? -t : t;
        py += py >= 0f ? -t : t;
        float len = (float) Math.sqrt(px * px + py * py + nz * nz);
        return new float[]{px / len, py / len, nz / len};
    }
}
