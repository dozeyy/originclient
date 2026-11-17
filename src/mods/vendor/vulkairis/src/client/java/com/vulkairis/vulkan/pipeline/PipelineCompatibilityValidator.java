package com.vulkairis.vulkan.pipeline;

import com.vulkairis.translation.ShaderReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure, native-free validation of a graphics pipeline's cross-stage interface.
 *
 * <p>A Vulkan graphics pipeline is only valid when every fragment stage input has a matching
 * vertex stage output at the same {@code location} with a compatible component count. This class
 * inspects reflected {@link ShaderReflection} metadata and reports human-readable problems; it
 * never touches a device, a driver or any native handle so it can run headlessly in tests.</p>
 */
public final class PipelineCompatibilityValidator {

    private PipelineCompatibilityValidator() {
    }

    /**
     * Validates the vertex/fragment interface described by the two reflections.
     *
     * @param vertex              reflection of the vertex stage ({@code null} is a problem)
     * @param fragment            reflection of the fragment stage ({@code null} is a problem)
     * @param fragmentOutputCount number of color attachments the pipeline supports; a
     *                            non-positive value means "unspecified" and skips that check
     * @return an empty list when the stages are compatible, otherwise one message per problem
     */
    public static List<String> validate(ShaderReflection vertex, ShaderReflection fragment,
                                        int fragmentOutputCount) {
        List<String> problems = new ArrayList<>();

        if (vertex == null) {
            problems.add("vertex reflection is null");
        }
        if (fragment == null) {
            problems.add("fragment reflection is null");
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        if (!vertex.isComplete()) {
            problems.add("vertex reflection is incomplete");
        }
        if (!fragment.isComplete()) {
            problems.add("fragment reflection is incomplete");
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        List<ShaderReflection.StageOutput> vertexOutputs = vertex.getStageOutputs();
        List<ShaderReflection.StageInput> fragmentInputs = fragment.getStageInputs();

        if (vertexOutputs.isEmpty() || fragmentInputs.isEmpty()) {
            problems.add("cannot verify stage interface: reflection incomplete/empty");
        } else {
            for (ShaderReflection.StageInput input : fragmentInputs) {
                ShaderReflection.StageOutput match = null;
                for (ShaderReflection.StageOutput output : vertexOutputs) {
                    if (output.location() == input.location()) {
                        match = output;
                        break;
                    }
                }
                if (match == null) {
                    problems.add("fragment input '" + input.name() + "' at location " + input.location()
                            + " has no matching vertex output");
                } else if (match.componentCount() != input.componentCount()) {
                    problems.add("stage interface mismatch at location " + input.location()
                            + ": fragment input '" + input.name() + "' expects "
                            + input.componentCount() + " components but vertex output '"
                            + match.name() + "' provides " + match.componentCount());
                }
            }
        }

        if (fragmentOutputCount > 0) {
            int declaredOutputs = fragment.getStageOutputs().size();
            if (declaredOutputs > fragmentOutputCount) {
                problems.add("fragment stage declares " + declaredOutputs
                        + " output(s) but the pipeline supports only " + fragmentOutputCount);
            }
        }

        return problems;
    }

    /**
     * Validates that every reflected vertex attribute has a corresponding location supplied by the
     * bound vertex format. Used to reject a pipeline whose vertex input state cannot feed the shader.
     *
     * @param suppliedLocations attribute locations the {@code VertexFormat} actually provides
     * @return an empty list when every reflected attribute is supplied, otherwise one message each
     */
    public static List<String> validateVertexFormat(ShaderReflection vertex, Set<Integer> suppliedLocations) {
        List<String> problems = new ArrayList<>();
        if (vertex == null) {
            problems.add("vertex reflection is null");
            return problems;
        }
        if (!vertex.isComplete()) {
            problems.add("vertex reflection is incomplete");
            return problems;
        }

        for (ShaderReflection.VertexAttribute attribute : vertex.getVertexAttributes()) {
            if (suppliedLocations == null || !suppliedLocations.contains(attribute.location())) {
                problems.add("vertex attribute '" + attribute.name() + "' at location "
                        + attribute.location() + " is not supplied by the vertex format");
            }
        }
        return problems;
    }
}
