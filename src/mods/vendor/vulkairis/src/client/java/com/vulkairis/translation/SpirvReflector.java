package com.vulkairis.translation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal, dependency-free SPIR-V reflector.
 *
 * <p>Shader resources are discovered from the compiled SPIR-V itself (decorations, variables,
 * pointer/type chains) instead of fragile global assumptions or GLSL source regex matching.
 * This is deliberately a focused subset of what a full reflection library would provide: it is
 * enough to build correct descriptor bindings, vertex input descriptions and push-constant sizes
 * for the controlled renderer and to validate cross-stage expectations.</p>
 *
 * <p>If the binary is malformed the reflector marks the returned reflection incomplete rather
 * than throwing, and callers must treat an incomplete result as a failure signal, not as proof
 * of correctness.</p>
 */
public final class SpirvReflector {

    // Instruction opcodes (SPIR-V spec).
    private static final int OP_NAME = 5;
    private static final int OP_MEMBER_NAME = 6;
    private static final int OP_ENTRY_POINT = 15;
    private static final int OP_TYPE_BOOL = 20;
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_MATRIX = 24;
    private static final int OP_TYPE_IMAGE = 25;
    private static final int OP_TYPE_SAMPLED_IMAGE = 27;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_CONSTANT = 43;
    private static final int OP_VARIABLE = 59;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;

    // Decorations.
    private static final int DEC_BLOCK = 2;
    private static final int DEC_BUFFER_BLOCK = 3;
    private static final int DEC_ARRAY_STRIDE = 6;
    private static final int DEC_BUILT_IN = 11;
    private static final int DEC_LOCATION = 30;
    private static final int DEC_BINDING = 33;
    private static final int DEC_DESCRIPTOR_SET = 34;
    private static final int DEC_OFFSET = 35;

    // Storage classes.
    private static final int SC_UNIFORM_CONSTANT = 0;
    private static final int SC_INPUT = 1;
    private static final int SC_UNIFORM = 2;
    private static final int SC_OUTPUT = 3;
    private static final int SC_PUSH_CONSTANT = 9;
    private static final int SC_STORAGE_BUFFER = 12;

    // Execution models.
    private static final int MODEL_VERTEX = 0;

    private static final int SPIRV_MAGIC = 0x07230203;

    private SpirvReflector() {
    }

    public static ShaderReflection reflect(ByteBuffer spirv) {
        ShaderReflection reflection = new ShaderReflection();
        if (spirv == null || spirv.remaining() < 20) {
            reflection.markIncomplete();
            return reflection;
        }

        ByteBuffer view = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int[] words;
        try {
            int[] ints = new int[view.remaining() / 4];
            view.asIntBuffer().get(ints);
            words = ints;
        } catch (RuntimeException t) {
            reflection.markIncomplete();
            return reflection;
        }

        if (words.length < 5 || words[0] != SPIRV_MAGIC) {
            reflection.markIncomplete();
            return reflection;
        }

        State state = new State();
        try {
            parse(words, state);
            build(reflection, state);
        } catch (RuntimeException t) {
            // Keep whatever was successfully gathered but flag the result as untrustworthy.
            reflection.markIncomplete();
            return reflection;
        }
        return reflection;
    }

    private static void parse(int[] words, State state) {
        int pos = 5; // skip header
        while (pos < words.length) {
            int word = words[pos];
            int wordCount = (word >>> 16) & 0xFFFF;
            int opcode = word & 0xFFFF;

            if (wordCount == 0) {
                if (pos + 1 >= words.length) break;
                wordCount = words[pos + 1];
                pos += 2;
            } else {
                pos += 1;
            }
            if (wordCount < 1 || pos + wordCount - 1 > words.length) break;

            int operandCount = wordCount - 1;
            switch (opcode) {
                case OP_NAME -> {
                    if (operandCount >= 2) {
                        state.names.put(words[pos], decodeString(words, pos + 1, pos + operandCount));
                    }
                }
                case OP_MEMBER_NAME -> {
                    if (operandCount >= 3) {
                        state.memberNames
                                .computeIfAbsent(words[pos], k -> new HashMap<>())
                                .put(words[pos + 1], decodeString(words, pos + 2, pos + operandCount));
                    }
                }
                case OP_ENTRY_POINT -> {
                    if (operandCount >= 2) {
                        state.executionModel = words[pos];
                        if (operandCount >= 3 && state.entryPointName == null) {
                            String entryName = decodeString(words, pos + 2, pos + operandCount);
                            if (entryName != null && !entryName.isBlank()) {
                                state.entryPointName = entryName;
                            }
                        }
                    }
                }
                case OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX,
                     OP_TYPE_IMAGE, OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY,
                     OP_TYPE_STRUCT, OP_TYPE_POINTER -> {
                    if (operandCount >= 1) {
                        int resultId = words[pos];
                        state.typeOpcode.put(resultId, opcode);
                        state.typeOperands.put(resultId, slice(words, pos + 1, operandCount - 1));
                    }
                }
                case OP_CONSTANT -> {
                    if (operandCount >= 3) {
                        int resultId = words[pos + 1];
                        // 64-bit constants occupy two operands; combine when present.
                        long value = (operandCount >= 4)
                                ? ((long) words[pos + 2] & 0xFFFFFFFFL) | (((long) words[pos + 3]) << 32)
                                : (words[pos + 2] & 0xFFFFFFFFL);
                        state.constants.put(resultId, value);
                    }
                }
                case OP_VARIABLE -> {
                    if (operandCount >= 3) {
                        state.variables.add(new Variable(words[pos], words[pos + 1], words[pos + 2]));
                    }
                }
                case OP_DECORATE -> {
                    if (operandCount >= 2) {
                        int target = words[pos];
                        int decoration = words[pos + 1];
                        int literal = (operandCount >= 3) ? words[pos + 2] : 0;
                        applyDecoration(state, target, decoration, literal);
                    }
                }
                case OP_MEMBER_DECORATE -> {
                    if (operandCount >= 3) {
                        int structId = words[pos];
                        int member = words[pos + 1];
                        int decoration = words[pos + 2];
                        int literal = (operandCount >= 4) ? words[pos + 3] : 0;
                        if (decoration == DEC_OFFSET) {
                            state.memberOffsets
                                    .computeIfAbsent(structId, k -> new HashMap<>())
                                    .put(member, literal);
                        }
                    }
                }
                default -> {
                    // Not needed for reflection.
                }
            }

            pos += wordCount - 1;
        }
    }

    private static void applyDecoration(State state, int target, int decoration, int literal) {
        switch (decoration) {
            case DEC_BINDING -> state.bindings.put(target, literal);
            case DEC_DESCRIPTOR_SET -> state.descriptorSets.put(target, literal);
            case DEC_LOCATION -> state.locations.put(target, literal);
            case DEC_ARRAY_STRIDE -> state.arrayStrides.put(target, literal);
            case DEC_BUILT_IN -> state.builtIns.add(target);
            case DEC_BLOCK -> state.blockStructs.add(target);
            case DEC_BUFFER_BLOCK -> state.bufferBlockStructs.add(target);
            default -> {
            }
        }
    }

    private static void build(ShaderReflection reflection, State state) {
        boolean vertexStage = state.executionModel == MODEL_VERTEX;
        if (state.entryPointName != null) {
            reflection.setEntryPointName(state.entryPointName);
        }
        reflection.setExecutionModel(state.executionModel);

        for (Variable variable : state.variables) {
            int[] pointer = state.typeOperands.get(variable.typeId);
            if (pointer == null || pointer.length < 2
                    || state.typeOpcode.get(variable.typeId) == null
                    || state.typeOpcode.get(variable.typeId) != OP_TYPE_POINTER) {
                continue;
            }
            int storageClass = pointer[0];
            int pointeeType = pointer[1];
            String name = state.names.getOrDefault(variable.id, "var_" + variable.id);
            int binding = state.bindings.getOrDefault(variable.id, 0);
            int set = state.descriptorSets.getOrDefault(variable.id, 0);

            switch (storageClass) {
                case SC_INPUT -> {
                    if (!state.builtIns.contains(variable.id)
                            && state.locations.containsKey(variable.id)) {
                        int location = state.locations.get(variable.id);
                        int components = componentCount(state, pointeeType);
                        String inputName = state.names.get(variable.id);
                        if (inputName == null || inputName.isBlank()) {
                            inputName = "input_" + location;
                        }
                        reflection.addStageInput(inputName, location, typeName(state, pointeeType), components);
                        if (vertexStage) {
                            // Vertex inputs remain VertexAttributes so vertex-format validation can use them.
                            reflection.addVertexAttribute(name, location, typeName(state, pointeeType), components);
                        }
                    }
                }
                case SC_OUTPUT -> {
                    if (!state.builtIns.contains(variable.id)
                            && state.locations.containsKey(variable.id)) {
                        int location = state.locations.get(variable.id);
                        String outputName = state.names.get(variable.id);
                        if (outputName == null || outputName.isBlank()) {
                            outputName = "output_" + location;
                        }
                        reflection.addStageOutput(outputName, location,
                                typeName(state, pointeeType), componentCount(state, pointeeType));
                    }
                }
                case SC_UNIFORM_CONSTANT -> {
                    // An arrayed descriptor (e.g. sampler2D tex[4]) is a single binding whose
                    // descriptor count is the array length, not several fabricated bindings.
                    int count = arrayLength(state, pointeeType);
                    reflection.addSampler(name, binding, binding, count);
                }
                case SC_UNIFORM, SC_STORAGE_BUFFER -> {
                    boolean block = state.blockStructs.contains(pointeeType)
                            || state.bufferBlockStructs.contains(pointeeType);
                    if (block) {
                        int size = typeSize(state, pointeeType);
                        if (storageClass == SC_UNIFORM) {
                            reflection.setUboBlockSize(Math.max(reflection.getUboBlockSize(), size));
                        }
                        List<ShaderReflection.UniformInfo> members =
                                collectStructMembers(reflection, state, pointeeType, name);
                        reflection.addUniformBlock(name, set, binding, stageFlag(state), size, members);
                    } else {
                        reflection.addUniform(name, "buffer", set, typeSize(state, pointeeType));
                    }
                }
                case SC_PUSH_CONSTANT -> {
                    int size = typeSize(state, pointeeType);
                    reflection.setPushConstantSize(Math.max(reflection.getPushConstantSize(), size));
                    collectStructMembers(reflection, state, pointeeType, name);
                }
                default -> {
                    // Function/private/workgroup variables: not part of the shader stage interface.
                }
            }
        }
    }

    private static List<ShaderReflection.UniformInfo> collectStructMembers(ShaderReflection reflection, State state, int structId, String blockName) {
        List<ShaderReflection.UniformInfo> members = new ArrayList<>();
        int[] memberTypes = state.typeOperands.get(structId);        if (memberTypes == null) return members;
        Map<Integer, Integer> offsets = state.memberOffsets.get(structId);
        Map<Integer, String> names = state.memberNames.get(structId);

        for (int i = 0; i < memberTypes.length; i++) {
            int memberType = memberTypes[i];
            int offset = (offsets != null) ? offsets.getOrDefault(i, 0) : 0;
            String memberName = (names != null && names.get(i) != null)
                    ? names.get(i)
                    : "member_" + i;
            String qualified = blockName.isEmpty() ? memberName : blockName + "." + memberName;
            int size = typeSize(state, memberType);
            String type = typeName(state, memberType);
            reflection.addUniform(qualified, type, offset, size);
            members.add(new ShaderReflection.UniformInfo(memberName, type, offset, size));
        }
        return members;
    }

    /** Maps the module's execution model to a Vulkan shader-stage flag. */
    private static int stageFlag(State state) {
        return switch (state.executionModel) {
            case MODEL_VERTEX -> 0x1;      // VK_SHADER_STAGE_VERTEX_BIT
            case 4 -> 0x10;                // VK_SHADER_STAGE_FRAGMENT_BIT
            default -> 0x1 | 0x10;
        };
    }

    /**
     * std140 size in bytes. Matrices are arrays of columns with a 16-byte stride and arrays use
     * their SPIR-V {@code ArrayStride} decoration when present.
     */
    private static int typeSize(State state, int typeId) {
        Integer opcode = state.typeOpcode.get(typeId);
        if (opcode == null) return 0;
        int[] operands = state.typeOperands.get(typeId);
        if (operands == null) return 0;

        return switch (opcode) {
            case OP_TYPE_BOOL -> 4;
            case OP_TYPE_INT, OP_TYPE_FLOAT -> Math.max(1, width(operands) / 8);
            case OP_TYPE_VECTOR -> operands.length >= 2 ? typeSize(state, operands[0]) * operands[1] : 0;
            case OP_TYPE_MATRIX -> operands.length >= 2
                    ? alignUp(typeSize(state, operands[0]), 16) * operands[1]
                    : 0;
            case OP_TYPE_ARRAY -> {
                int elementSize = operands.length >= 1 ? typeSize(state, operands[0]) : 0;
                long count = (operands.length >= 2) ? state.constants.getOrDefault(operands[1], 0L) : 0L;
                int stride = state.arrayStrides.getOrDefault(typeId, -1);
                if (stride <= 0) stride = alignUp(elementSize, 16);
                yield stride * (int) count;
            }
            case OP_TYPE_RUNTIME_ARRAY -> {
                int elementSize = operands.length >= 1 ? typeSize(state, operands[0]) : 0;
                int stride = state.arrayStrides.getOrDefault(typeId, -1);
                yield stride > 0 ? stride : alignUp(elementSize, 16);
            }
            case OP_TYPE_STRUCT -> {
                Map<Integer, Integer> offsets = state.memberOffsets.get(typeId);
                int max = 0;
                for (int i = 0; i < operands.length; i++) {
                    int offset = (offsets != null) ? offsets.getOrDefault(i, 0) : 0;
                    max = Math.max(max, offset + typeSize(state, operands[i]));
                }
                yield alignUp(max, 16);
            }
            default -> 0;
        };
    }

    private static int arrayLength(State state, int typeId) {
        Integer opcode = state.typeOpcode.get(typeId);
        int[] operands = state.typeOperands.get(typeId);
        if (opcode != null && opcode == OP_TYPE_ARRAY && operands != null && operands.length >= 2) {
            return state.constants.getOrDefault(operands[1], 1L).intValue();
        }
        if (opcode != null && opcode == OP_TYPE_RUNTIME_ARRAY) {
            return 0;
        }
        return 1;
    }

    /**
     * Number of components carried by a (possibly arrayed) scalar/vector interface type:
     * {@code vec2}=2, {@code vec3}=3, {@code vec4}=4, scalars (float/int/bool)=1. Unknown shapes
     * fall back to 1 rather than guessing.
     */
    private static int componentCount(State state, int typeId) {
        Integer opcode = state.typeOpcode.get(typeId);
        if (opcode == null) return 1;
        int[] operands = state.typeOperands.get(typeId);
        return switch (opcode) {
            case OP_TYPE_VECTOR -> (operands != null && operands.length >= 2) ? Math.max(1, operands[1]) : 1;
            case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY ->
                    (operands != null && operands.length >= 1) ? componentCount(state, operands[0]) : 1;
            default -> 1;
        };
    }

    private static String descriptorType(State state, int typeId) {
        Integer opcode = state.typeOpcode.get(typeId);
        if (opcode == null) return "unknown";
        return switch (opcode) {
            case OP_TYPE_SAMPLED_IMAGE -> "combined_image_sampler";
            case OP_TYPE_IMAGE -> {
                int[] operands = state.typeOperands.get(typeId);
                int sampled = (operands != null && operands.length >= 6) ? operands[5] : 1;
                yield sampled == 2 ? "storage_image" : "sampled_image";
            }
            case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> {
                int[] operands = state.typeOperands.get(typeId);
                int length = arrayLength(state, typeId);
                String element = (operands != null && operands.length >= 1)
                        ? descriptorType(state, operands[0])
                        : "unknown";
                yield element + "[" + length + "]";
            }
            case OP_TYPE_STRUCT -> "uniform_buffer";
            default -> "unknown";
        };
    }

    /** Full GLSL type identity, including component signedness, both matrix dimensions and arrays. */
    private static String typeName(State state, int typeId) {
        Integer opcode = state.typeOpcode.get(typeId);
        int[] operands = state.typeOperands.get(typeId);
        if (opcode == null || operands == null) return "unknown";
        return switch (opcode) {
            case OP_TYPE_BOOL -> "bool";
            case OP_TYPE_INT -> width(operands) == 64 ? "int64_t" : "int";
            case OP_TYPE_FLOAT -> width(operands) == 64 ? "double" : "float";
            case OP_TYPE_VECTOR -> operands.length >= 2
                    ? scalarPrefix(state, operands[0]) + "vec" + operands[1]
                    : "unknown";
            case OP_TYPE_MATRIX -> matrixName(state, operands);
            case OP_TYPE_ARRAY -> arrayName(state, operands, false);
            case OP_TYPE_RUNTIME_ARRAY -> arrayName(state, operands, true);
            case OP_TYPE_SAMPLED_IMAGE -> "sampler2D";
            case OP_TYPE_IMAGE -> "image";
            case OP_TYPE_STRUCT -> "struct";
            default -> "unknown";
        };
    }

    private static String scalarPrefix(State state, int scalarTypeId) {
        Integer opcode = state.typeOpcode.get(scalarTypeId);
        if (opcode == null) return "";
        int[] operands = state.typeOperands.get(scalarTypeId);
        return switch (opcode) {
            case OP_TYPE_BOOL -> "b";
            case OP_TYPE_INT -> "i";
            case OP_TYPE_FLOAT -> (operands != null && operands.length >= 1 && operands[0] == 64) ? "d" : "";
            default -> "";
        };
    }

    private static String matrixName(State state, int[] operands) {
        if (operands.length < 2) return "mat?";
        int columns = operands[1];
        int[] columnOperands = state.typeOperands.get(operands[0]);
        int rows = (columnOperands != null && columnOperands.length >= 2) ? columnOperands[1] : columns;
        return "mat" + columns + "x" + rows;
    }

    private static String arrayName(State state, int[] operands, boolean runtime) {
        if (operands.length < 1) return "unknown";
        String element = typeName(state, operands[0]);
        if (runtime) return element + "[]";
        long count = (operands.length >= 2) ? state.constants.getOrDefault(operands[1], 0L) : 0L;
        return element + "[" + count + "]";
    }

    private static int width(int[] operands) {
        return (operands != null && operands.length >= 1) ? operands[0] : 32;
    }

    private static int alignUp(int value, int alignment) {
        if (alignment <= 1) return value;
        return ((value + alignment - 1) / alignment) * alignment;
    }

    private static int[] slice(int[] words, int from, int count) {
        if (count <= 0) return new int[0];
        int[] out = new int[count];
        System.arraycopy(words, from, out, 0, count);
        return out;
    }

    private static String decodeString(int[] words, int from, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < endExclusive; i++) {
            int word = words[i];
            for (int shift = 0; shift < 32; shift += 8) {
                char c = (char) ((word >>> shift) & 0xFF);
                if (c == 0) return sb.toString();
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private record Variable(int typeId, int id, int storageClass) {
    }

    private static final class State {
        final Map<Integer, String> names = new HashMap<>();
        final Map<Integer, Map<Integer, String>> memberNames = new HashMap<>();
        final Map<Integer, Integer> typeOpcode = new HashMap<>();
        final Map<Integer, int[]> typeOperands = new HashMap<>();
        final Map<Integer, Long> constants = new HashMap<>();
        final List<Variable> variables = new ArrayList<>();
        final Map<Integer, Integer> bindings = new HashMap<>();
        final Map<Integer, Integer> descriptorSets = new HashMap<>();
        final Map<Integer, Integer> locations = new HashMap<>();
        final Map<Integer, Integer> arrayStrides = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> memberOffsets = new HashMap<>();
        final Set<Integer> builtIns = new HashSet<>();
        final Set<Integer> blockStructs = new HashSet<>();
        final Set<Integer> bufferBlockStructs = new HashSet<>();
        int executionModel = -1;
        String entryPointName = null;
    }
}
