package com.vulkairis.plan;

import com.vulkairis.bridge.StandardUniforms;
import com.vulkairis.bridge.UniformBridge;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Phase 5: std140 UBO packer.
 *
 * <p>Packs game state, matrices, and custom uniform values into direct ByteBuffers
 * matching the exact std140 offsets defined in {@link UniformPlan}.</p>
 */
public final class UniformBlockPacker {

    private UniformBlockPacker() {
    }

    /**
     * Packs standard and custom uniforms into target buffer according to the given plan.
     */
    public static void pack(UniformPlan plan, UniformBridge bridge, Map<String, float[]> customValues, ByteBuffer target) {
        if (plan == null || target == null || target.remaining() < plan.totalSizeBytes()) {
            return;
        }

        target.order(ByteOrder.LITTLE_ENDIAN);
        int basePos = target.position();

        // Standard snapshot
        Map<String, float[]> standardValues = StandardUniforms.snapshot(
                bridge, bridge != null ? bridge.getCameraPosition() : null,
                bridge != null ? bridge.getFrameCounter() : 0);

        for (Map.Entry<String, UniformPlan.MemberSpec> entry : plan.members().entrySet()) {
            String name = entry.getKey();
            UniformPlan.MemberSpec spec = entry.getValue();

            float[] data = resolveUniformData(name, standardValues, customValues, bridge);
            if (data == null || data.length == 0) {
                continue;
            }

            int memberOffset = basePos + spec.offsetBytes();
            List<Integer> slots = spec.floatSlots();

            if (slots.isEmpty() || slots.size() == data.length) {
                // Direct sequential write if tightly packed or slots match
                int writeCount = Math.min(data.length, spec.sizeBytes() / 4);
                for (int i = 0; i < writeCount; i++) {
                    int slotOffset = memberOffset + (slots.isEmpty() ? i * 4 : slots.get(i) * 4);
                    if (slotOffset + 4 <= basePos + plan.totalSizeBytes() && slotOffset + 4 <= target.limit()) {
                        target.putFloat(slotOffset, data[i]);
                    }
                }
            } else {
                int writeCount = Math.min(data.length, slots.size());
                for (int i = 0; i < writeCount; i++) {
                    int slotOffset = memberOffset + slots.get(i) * 4;
                    if (slotOffset + 4 <= basePos + plan.totalSizeBytes() && slotOffset + 4 <= target.limit()) {
                        target.putFloat(slotOffset, data[i]);
                    }
                }
            }
        }

        target.position(basePos + plan.totalSizeBytes());
    }

    private static float[] resolveUniformData(String name, Map<String, float[]> standardValues,
                                              Map<String, float[]> customValues, UniformBridge bridge) {
        if (customValues != null && customValues.containsKey(name)) {
            return customValues.get(name);
        }

        // Direct standard names
        if (standardValues.containsKey(name)) {
            return standardValues.get(name);
        }

        // Legacy & Vulkairis alias names
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("projection") || lower.equals("gl_projectionmatrix")) {
            return standardValues.get(StandardUniforms.Standard.PROJECTION.uniformName());
        }
        if (lower.equals("modelview") || lower.equals("modelviewmatrix") || lower.equals("gl_modelviewmatrix")) {
            return standardValues.get(StandardUniforms.Standard.MODEL_VIEW.uniformName());
        }
        if (lower.equals("vulkairis_modelviewprojectionmatrix") || lower.equals("gl_modelviewprojectionmatrix")) {
            if (bridge != null) {
                Matrix4f mvp = new Matrix4f(bridge.getProjectionMatrix()).mul(bridge.getModelViewMatrix());
                float[] out = new float[16];
                mvp.get(out);
                return out;
            }
        }
        if (lower.startsWith("vulkairis_texturematrix") || lower.startsWith("gl_texturematrix")) {
            // Identity matrix default for texture matrix
            float[] id = new float[16];
            id[0] = id[5] = id[10] = id[15] = 1.0f;
            return id;
        }
        if (lower.equals("viewwidth") || lower.equals("screenwidth")) {
            return new float[]{bridge != null ? bridge.getScreenWidth() : 1920};
        }
        if (lower.equals("viewheight") || lower.equals("screenheight")) {
            return new float[]{bridge != null ? bridge.getScreenHeight() : 1080};
        }
        if (lower.equals("frametime") || lower.equals("frametimecounter")) {
            return standardValues.get(StandardUniforms.Standard.FRAME_TIME.uniformName());
        }

        return null;
    }
}
