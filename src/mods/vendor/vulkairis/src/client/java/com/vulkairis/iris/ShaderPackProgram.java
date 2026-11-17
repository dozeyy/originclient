package com.vulkairis.iris;

import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.joml.Vector3i;

import java.util.Optional;

/**
 * A single executable program discovered inside an Iris shader pack.
 *
 * <p>A shader pack is not one shader: it is an ordered set of program groups (setup and begin
 * passes, shadow passes, gbuffers, deferred, composite, and final). Before Vulkairis can build a
 * Vulkan render graph it must know the complete set and whether each program is a raster graphics
 * program or a compute program. This model is that inventory.</p>
 *
 * <p>{@link #executionOrder()} is derived from the Iris {@link ProgramGroup} declaration order,
 * which is the order the groups execute in the real pipeline: setup/begin precede shadows, which
 * precede gbuffers, which precede deferred/composite/final post-processing.</p>
 */
public sealed interface ShaderPackProgram
        permits ShaderPackProgram.Graphics, ShaderPackProgram.Compute {

    /** The program's source name, e.g. {@code gbuffers_terrain} or {@code composite1}. */
    String name();

    /** The Iris program group this program belongs to. */
    ProgramGroup group();

    /** Whether this is a graphics or compute program. */
    ShaderProgramKind kind();

    /** Position of this program within its program array, or {@code 0} for single programs. */
    int arrayIndex();

    /** Length of the program array this program belongs to, or {@code 1} for single programs. */
    int arrayLength();

    /**
     * Relative execution order across the whole pack, derived from {@link ProgramGroup} order.
     * A lower value must execute before a higher value.
     *
     * @return the ordinal of the owning {@link ProgramGroup}
     */
    default int executionOrder() {
        return group().ordinal();
    }

    /** @return {@code true} for compute programs */
    default boolean isCompute() {
        return kind() == ShaderProgramKind.COMPUTE;
    }

    /** @return {@code true} for graphics programs */
    default boolean isGraphics() {
        return kind() == ShaderProgramKind.GRAPHICS;
    }

    /** @return {@code true} when this program is one element of a multi-element array */
    default boolean isArrayElement() {
        return arrayLength() > 1;
    }

    /**
     * A single-line, human-readable identity for logs and diagnostics.
     *
     * @return a description such as {@code "gbuffers_terrain [Gbuffers] graphics (v+f)"}
     */
    String describe();

    /**
     * A raster graphics program: a vertex/fragment (optionally geometry/tessellation) pipeline.
     *
     * <p>{@code source} is the originating Iris {@link ProgramSource} for a discovered program. It
     * is permitted to be {@code null} for synthetic instances used by the catalog's unit tests,
     * where constructing a real {@code ProgramSource} would require a full Iris pack.</p>
     */
    record Graphics(
            String name,
            ProgramGroup group,
            int arrayIndex,
            int arrayLength,
            ProgramSource source,
            boolean hasVertex,
            boolean hasFragment,
            boolean hasGeometry,
            boolean hasTessControl,
            boolean hasTessEval,
            String fallbackName
    ) implements ShaderPackProgram {

        public Graphics {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (group == null) {
                throw new IllegalArgumentException("group must not be null");
            }
            if (arrayIndex < 0) {
                arrayIndex = 0;
            }
            if (arrayLength < 1) {
                arrayLength = 1;
            }
        }

        @Override
        public ShaderProgramKind kind() {
            return ShaderProgramKind.GRAPHICS;
        }

        /** @return the Iris fallback program name, if this program declares a fallback */
        public Optional<String> fallback() {
            return Optional.ofNullable(fallbackName);
        }

        @Override
        public String describe() {
            StringBuilder stages = new StringBuilder();
            if (hasVertex) stages.append('v');
            if (hasFragment) stages.append('f');
            if (hasGeometry) stages.append('+');
            if (hasTessControl) stages.append("tc");
            if (hasTessEval) stages.append("te");
            String index = isArrayElement() ? " #" + arrayIndex : "";
            return name + " [" + group + "] graphics (" + (stages.length() == 0 ? "-" : stages) + ")"
                    + index;
        }
    }

    /**
     * A compute program, dispatched against storage images/buffers and sampled images.
     *
     * <p>{@code source} is the originating Iris {@link ComputeSource} for a discovered program and
     * may be {@code null} for synthetic test instances. {@code workGroups} is the declared
     * workgroup count when Iris exposes one, otherwise {@code null}.</p>
     */
    record Compute(
            String name,
            ProgramGroup group,
            int arrayIndex,
            int arrayLength,
            ComputeSource source,
            Vector3i workGroups
    ) implements ShaderPackProgram {

        public Compute {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (group == null) {
                throw new IllegalArgumentException("group must not be null");
            }
            if (arrayIndex < 0) {
                arrayIndex = 0;
            }
            if (arrayLength < 1) {
                arrayLength = 1;
            }
        }

        @Override
        public ShaderProgramKind kind() {
            return ShaderProgramKind.COMPUTE;
        }

        @Override
        public String describe() {
            String groups = (workGroups != null)
                    ? workGroups.x + "x" + workGroups.y + "x" + workGroups.z
                    : "?";
            String index = isArrayElement() ? " #" + arrayIndex : "";
            return name + " [" + group + "] compute (" + groups + ")" + index;
        }
    }
}
