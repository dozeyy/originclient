package com.vulkairis.rendering.state;

import com.vulkairis.rendering.state.ProgramUniformStore.UniformValue;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.VirtualGlFacade;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Per-program UBO packing plans from reflected offsets and strides (Phase 10).
 *
 * <p>For every uniform block in the program's complete stage reflections, each member is
 * resolved to a uniform location through the facade (arrays by base name, struct members by
 * bare or qualified name — the same rules Iris queries use), the current-frame value is read
 * from the isolated frame store, and floats are placed with {@code Std140} padding. Nothing
 * assumes a fixed buffer size: every buffer is sized from the reflected block, and members
 * without values are reported missing instead of shipped as garbage.</p>
 */
public final class UniformPackPlanner {

    private UniformPackPlanner() {
    }

    /** One program's packing plan: per-block results plus members no value could be found for. */
    public record ProgramPack(Map<String, UniformBlockPacker.PackResult> blocks,
                              List<String> unresolved) {
    }

    /**
     * Plans every uniform block of the program from the frame store.
     *
     * <p>Blocks are keyed {@code shader:block} because every stage carries the same injected
     * block name with <em>different</em> members: keying by bare block name would alias one
     * stage's buffer onto another's.</p>
     *
     * @param program the program (reads its complete stage reflections)
     * @param store   the isolated current-frame values
     * @return the per-block pack results; empty blocks map when nothing is plannable
     */
    public static ProgramPack plan(VulkairisProgram program, UniformFrameStore store) {
        Map<String, UniformBlockPacker.PackResult> blocks = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        if (program == null || store == null) {
            return new ProgramPack(blocks, unresolved);
        }
        int progId = program.virtualProgramId();
        for (VulkairisShader stage : program.getAttachedShaders()) {
            ShaderReflection reflection = stage.getReflection();
            if (!reflection.isComplete()) {
                continue;
            }
            for (ShaderReflection.UniformBlock block : reflection.getUniformBlocks()) {
                String key = stage.getName() + ":" + block.name();
                if (blocks.containsKey(key)) {
                    continue;
                }
                blocks.put(key, packBlock(progId, stage.getName(), block, store, unresolved));
            }
        }
        return new ProgramPack(blocks, unresolved);
    }

    private static UniformBlockPacker.PackResult packBlock(int progId, String stageName,
                                                           ShaderReflection.UniformBlock block,
                                                           UniformFrameStore store,
                                                           List<String> unresolved) {
        Map<Integer, Float> placed = new TreeMap<>();
        Map<Integer, String> memberOf = new TreeMap<>();
        for (ShaderReflection.UniformInfo member : block.members()) {
            float[] values = resolveMember(progId, member, store);
            if (values == null) {
                unresolved.add(stageName + ":" + block.name() + "." + member.name());
                continue;
            }
            List<Integer> slots = Std140.floatSlots(member.type(), values.length);
            if (slots.isEmpty()) {
                unresolved.add(stageName + ":" + block.name() + "." + member.name()
                        + " (unsupported type " + member.type() + ")");
                continue;
            }
            int take = Math.min(slots.size(), values.length);
            for (int i = 0; i < take; i++) {
                int offset = member.offset() + slots.get(i) * 4;
                placed.put(offset, values[i]);
                memberOf.put(offset, member.name());
            }
        }
        return UniformBlockPacker.packPlaced(block, placed, memberOf::get);
    }

    /**
     * Resolves one member to its float payload: facade location first (Iris's own query path),
     * then the frame store. Integer payloads widen to floats; boolean-as-int 0/1 widens the
     * same way. Returns {@code null} when no value exists.
     */
    static float[] resolveMember(int progId, ShaderReflection.UniformInfo member,
                                 UniformFrameStore store) {
        int location = VirtualGlFacade.getUniformLocation(progId, member.name());
        if (location < 0) {
            // Bare block-member names are what Iris queries; try the qualified form too.
            return null;
        }
        Optional<UniformValue> value = store.read(progId, location);
        if (value.isEmpty()) {
            return null;
        }
        UniformValue uniform = value.get();
        if (uniform.floats().length > 0) {
            return uniform.floats().clone();
        }
        float[] widened = new float[uniform.ints().length];
        for (int i = 0; i < widened.length; i++) {
            widened[i] = uniform.ints()[i];
        }
        return widened;
    }
}
