package com.vulkairis.origin.composite;

import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Origin, CI only ({@code -Dvulkairis.composite.probe=true}, set by tools/vulkan-probe/vulkan.init.gradle).
 * Once, 60 post-chain frames in, reads three pixels (top, centre, bottom) of every post-chain image
 * back from the GPU and logs them, so a wrong frame can be pinned to the pass that produced it without
 * a debugger. Never runs for players: the readback stalls the GPU.
 */
final class CompositeProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/CompositeProbe");
    static final boolean ENABLED = Boolean.getBoolean("vulkairis.composite.probe");
    private static final int DUMP_AT_FRAME = 60;
    private static int frames;
    private static boolean done;

    private CompositeProbe() {
    }

    /** True on the one frame whose previous results should be dumped. */
    static boolean due() {
        if (!ENABLED || done) {
            return false;
        }
        if (++frames < DUMP_AT_FRAME) {
            return false;
        }
        done = true;
        return true;
    }

    static void plan(CompositeRouting.Plan plan, IrisPassSource.Chain chain) {
        StringBuilder sb = new StringBuilder("plan: scene=colortex").append(chain.sceneBuffer())
                .append(" final=").append(chain.finalName()).append(" finalReads=")
                .append(Arrays.toString(plan.finalReadSides()));
        for (CompositeRouting.PassRoute r : plan.passes()) {
            sb.append("\n  ").append(r.name()).append(" draw=").append(Arrays.toString(r.drawBuffers()))
                    .append(" write=").append(Arrays.toString(r.writeSides()))
                    .append(" reads=").append(Arrays.toString(r.readSides()));
        }
        LOGGER.info("[Vulkairis/CompositeProbe] {}", sb);
    }

    static void dump(String label, VulkanImage image) {
        if (image == null) {
            return;
        }
        long bytes = (long) image.width * image.height * image.formatSize;
        long ptr = MemoryUtil.nmemAlloc(bytes);
        try {
            ImageUtil.downloadTexture(image, ptr);
            StringBuilder sb = new StringBuilder();
            for (float fy : new float[] {0.1f, 0.5f, 0.9f}) {
                int x = image.width / 2;
                int y = (int) (image.height * fy);
                long at = ptr + ((long) y * image.width + x) * image.formatSize;
                sb.append(" row").append(y).append('=').append(decode(image, at));
            }
            LOGGER.info("[Vulkairis/CompositeProbe] {} fmt={} {}x{}{}", label, image.format, image.width,
                    image.height, sb);
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis/CompositeProbe] {} readback failed: {}", label, t.toString());
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private static String decode(VulkanImage image, long at) {
        switch (image.format) {
            case 97: { // R16G16B16A16_SFLOAT
                float[] v = new float[4];
                for (int i = 0; i < 4; i++) {
                    v[i] = Float.float16ToFloat(MemoryUtil.memGetShort(at + 2L * i));
                }
                return String.format("(%.3f,%.3f,%.3f,%.3f)", v[0], v[1], v[2], v[3]);
            }
            case 100: // R32_SFLOAT
                return String.format("(%.5f)", MemoryUtil.memGetFloat(at));
            default: {
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < image.formatSize; i++) {
                    sb.append(i == 0 ? "" : ",").append(MemoryUtil.memGetByte(at + i) & 0xFF);
                }
                return sb.append(')').toString();
            }
        }
    }
}
