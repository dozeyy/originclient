package com.vulkairis.translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates generated SPIR-V bytecode headers and structure.
 *
 * <p>Beyond the mandatory header checks (magic, version, schema, id bound) this performs
 * dependency-free structural sanity checks that catch truncated/broken modules before a driver
 * ever sees them: every instruction is checked to fit inside the module, at least one
 * {@code OpEntryPoint} must exist, the set of declared result ids is gathered from
 * {@code OpType*}/{@code OpVariable}/{@code OpFunction}/... and the ids referenced by
 * {@code OpEntryPoint}, {@code OpName} and {@code OpDecorate} are verified to resolve to a
 * declared id. Failures never throw: they are reported through {@link ValidationResult}.</p>
 *
 * <p>Known limit: this is structural validation, not a full semantic validator. A native
 * {@code spirv-val} (SPIRV-Tools) dependency is unavailable to the offline 1.21.1 build, so it
 * remains future work; in the meantime shaderc's own legalization plus these checks gate every
 * module before reflection and pipeline creation.</p>
 */
public class SpirvValidator {
    public static final int SPIRV_MAGIC = 0x07230203;

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/SpirvValidator");

    /** Opcodes that never declare a result id, so their second operand must not be collected. */
    private static final Set<Integer> NO_RESULT_OPCODES = Set.of(
            56,  // OpFunctionEnd
            62,  // OpStore
            63,  // OpCopyMemory
            64,  // OpCopyMemorySized
            71,  // OpDecorate
            72,  // OpMemberDecorate
            74,  // OpGroupDecorate
            75,  // OpGroupMemberDecorate
            99,  // OpImageWrite
            246, // OpLoopMerge
            247, // OpSelectionMerge
            249, // OpBranch
            250, // OpBranchConditional
            251, // OpSwitch
            252, // OpKill
            253, // OpReturn
            254, // OpReturnValue
            255, // OpUnreachable
            256, // OpLifetimeStart
            257  // OpLifetimeStop
    );

    /** Outcome of validating a SPIR-V module. */
    public record ValidationResult(boolean valid, String detail) {
        public static ValidationResult ok(String detail) {
            return new ValidationResult(true, detail);
        }

        public static ValidationResult failure(String detail) {
            return new ValidationResult(false, detail);
        }
    }

    /**
     * Validates the module and returns the outcome instead of throwing.
     *
     * @return {@code valid=true} when the header and basic instruction structure are sane,
     *         otherwise {@code valid=false} with a human-readable reason.
     */
    public static ValidationResult validate(ByteBuffer spirv) {
        if (spirv == null) {
            return failure("SPIR-V bytecode buffer is null");
        }

        if (spirv.remaining() < 20) {
            return failure("SPIR-V buffer too small for header (size: " + spirv.remaining() + " bytes)");
        }

        if (spirv.remaining() % 4 != 0) {
            return failure("SPIR-V buffer size must be a multiple of 4 bytes (size: " + spirv.remaining() + ")");
        }

        ByteBuffer slice = spirv.slice();
        int magic = slice.order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (magic != SPIRV_MAGIC) {
            // Check big-endian.
            magic = slice.order(ByteOrder.BIG_ENDIAN).getInt(0);
            if (magic != SPIRV_MAGIC) {
                return failure(String.format("Invalid SPIR-V magic number: 0x%08X (expected 0x%08X)",
                        magic, SPIRV_MAGIC));
            }
        }

        int bound = slice.getInt(12);
        if (bound <= 0) {
            return failure("Invalid SPIR-V ID bound: " + bound);
        }

        // SPIR-V version word: must be non-zero and no newer than 1.6 (the newest schema this
        // validator understands). The pinned profile emits 1.3 (Vulkan 1.1).
        int version = slice.getInt(4);
        if (version == 0) {
            return failure("Invalid SPIR-V version 0x00000000");
        }
        if (Integer.compareUnsigned(version, 0x00010600) > 0) {
            return failure(String.format("Unsupported SPIR-V version 0x%08X (newer than 1.6)", version));
        }

        // Schema word is reserved and must be zero.
        int schema = slice.getInt(16);
        if (schema != 0) {
            return failure(String.format("Invalid SPIR-V schema 0x%08X (reserved, must be zero)", schema));
        }

        int[] words;
        try {
            int wordCount = slice.remaining() / 4;
            words = new int[wordCount];
            for (int i = 0; i < wordCount; i++) {
                words[i] = slice.getInt(i * 4);
            }
        } catch (RuntimeException t) {
            return failure("Failed to read SPIR-V instruction words: " + t.getMessage());
        }

        // First pass: verify instruction framing and gather every declared result id.
        Set<Integer> declaredIds = new HashSet<>();
        boolean hasEntryPoint = false;
        int pos = 5; // skip the 5-word header
        while (pos < words.length) {
            int instructionIndex = pos;
            int word = words[pos];
            int wordCount = (word >>> 16) & 0xFFFF;
            int opcode = word & 0xFFFF;

            int operandStart;
            if (wordCount == 0) {
                if (pos + 1 >= words.length) {
                    return failureAt(instructionIndex, opcode,
                            "instruction uses an extended word count with no following word");
                }
                wordCount = words[pos + 1];
                operandStart = pos + 2;
            } else {
                operandStart = pos + 1;
            }

            if (wordCount < 1) {
                return failureAt(instructionIndex, opcode, "instruction declares an empty word count");
            }
            if (operandStart + (wordCount - 1) > words.length) {
                return failureAt(instructionIndex, opcode, "instruction word count " + wordCount
                        + " overflows the module (" + words.length + " words)");
            }

            if (opcode == 15) { // OpEntryPoint
                hasEntryPoint = true;
            }
            collectDeclaredId(words, operandStart, wordCount - 1, opcode, declaredIds);
            pos = operandStart + (wordCount - 1);
        }

        if (!hasEntryPoint) {
            return failure("SPIR-V module declares no OpEntryPoint");
        }

        // Second pass: verify that ids referenced by interface/debug instruction resolve.
        pos = 5;
        while (pos < words.length) {
            int instructionIndex = pos;
            int word = words[pos];
            int wordCount = (word >>> 16) & 0xFFFF;
            int opcode = word & 0xFFFF;

            int operandStart;
            if (wordCount == 0) {
                wordCount = words[pos + 1];
                operandStart = pos + 2;
            } else {
                operandStart = pos + 1;
            }

            String problem = verifyReferences(words, operandStart, wordCount - 1, opcode,
                    declaredIds, bound, instructionIndex);
            if (problem != null) {
                return failure(problem);
            }
            pos = operandStart + (wordCount - 1);
        }

        return ValidationResult.ok("SPIR-V structure valid (" + declaredIds.size() + " declared ids)");
    }

    /** Convenience boolean view of {@link #validate(ByteBuffer)} for callers that only need a flag. */
    public static boolean isValid(ByteBuffer spirv) {
        return validate(spirv).valid();
    }

    private static void collectDeclaredId(int[] words, int start, int operandCount, int opcode,
                                          Set<Integer> declaredIds) {
        if (operandCount <= 0) return;
        Integer resultIndex = resultOperandIndex(opcode);
        if (resultIndex != null && resultIndex < operandCount) {
            declaredIds.add(words[start + resultIndex]);
        }
    }

    /**
     * Operand index of an instruction's result id, or {@code null} when the opcode has no result.
     * Most value-producing opcodes carry a result type followed by the result id, while types and
     * a few others carry only the result id.
     */
    private static Integer resultOperandIndex(int opcode) {
        switch (opcode) {
            // Result id is the first operand.
            case 7:   // OpString
            case 11:  // OpExtInstImport
            case 19:  // OpTypeVoid
            case 20:  // OpTypeBool
            case 21:  // OpTypeInt
            case 22:  // OpTypeFloat
            case 23:  // OpTypeVector
            case 24:  // OpTypeMatrix
            case 25:  // OpTypeImage
            case 26:  // OpTypeSampler
            case 27:  // OpTypeSampledImage
            case 28:  // OpTypeArray
            case 29:  // OpTypeRuntimeArray
            case 30:  // OpTypeStruct
            case 31:  // OpTypeOpaque
            case 32:  // OpTypePointer
            case 33:  // OpTypeFunction
            case 34:  // OpTypeEvent
            case 35:  // OpTypeDeviceEvent
            case 36:  // OpTypeReserveId
            case 37:  // OpTypeQueue
            case 38:  // OpTypePipe
            case 73:  // OpDecorationGroup
            case 248: // OpLabel
                return 0;

            // Result id is the second operand (result type precedes it).
            case 1:   // OpUndef
            case 12:  // OpExtInst
            case 41:  // OpConstantTrue
            case 42:  // OpConstantFalse
            case 43:  // OpConstant
            case 44:  // OpConstantComposite
            case 45:  // OpConstantSampler
            case 46:  // OpConstantNull
            case 48:  // OpSpecConstantTrue
            case 49:  // OpSpecConstantFalse
            case 50:  // OpSpecConstant
            case 51:  // OpSpecConstantComposite
            case 52:  // OpSpecConstantOp
            case 54:  // OpFunction
            case 55:  // OpFunctionParameter
            case 57:  // OpFunctionCall
            case 59:  // OpVariable
                return 1;

            default:
                // Broad fallback: value-producing instructions from OpImage onward follow the
                // [result type, result id, ...] shape. Over-collecting a literal here is harmless
                // for the reference check; the explicit no-result set avoids the common control
                // flow and memory instructions.
                if (opcode >= 60 && !NO_RESULT_OPCODES.contains(opcode)) {
                    return 1;
                }
                return null;
        }
    }

    /** Returns a failure detail string for the first bad id reference, or {@code null} if all are fine. */
    private static String verifyReferences(int[] words, int start, int operandCount, int opcode,
                                           Set<Integer> declaredIds, int bound, int instructionIndex) {
        switch (opcode) {
            case 5,   // OpName
                 71 -> { // OpDecorate
                if (operandCount < 1) return null;
                return checkId(words[start], instructionIndex, opcode, declaredIds, bound);
            }
            case 15 -> { // OpEntryPoint: <model> <entry id> <name string> <interface ids...>
                if (operandCount < 2) return null;
                String entryProblem = checkId(words[start + 1], instructionIndex, opcode, declaredIds, bound);
                if (entryProblem != null) return entryProblem;

                if (operandCount >= 3) {
                    int nameStart = start + 2;
                    int nameWords = literalStringWordCount(words, nameStart, start + operandCount);
                    int interfaceStart = nameStart + nameWords;
                    for (int i = interfaceStart; i < start + operandCount; i++) {
                        String interfaceProblem = checkId(words[i], instructionIndex, opcode,
                                declaredIds, bound);
                        if (interfaceProblem != null) return interfaceProblem;
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String checkId(int id, int instructionIndex, int opcode,
                                  Set<Integer> declaredIds, int bound) {
        if (id <= 0 || id >= bound) {
            return "instruction #" + instructionIndex + " (opcode " + opcode
                    + ") references out-of-range id %" + id + " (id bound " + bound + ")";
        }
        if (!declaredIds.contains(id)) {
            return "instruction #" + instructionIndex + " (opcode " + opcode
                    + ") references undeclared id %" + id;
        }
        return null;
    }

    /** Number of words consumed by a null-terminated literal string starting at {@code start}. */
    private static int literalStringWordCount(int[] words, int start, int endExclusive) {
        for (int i = start; i < endExclusive; i++) {
            int word = words[i];
            for (int shift = 0; shift < 32; shift += 8) {
                if (((word >>> shift) & 0xFF) == 0) {
                    return (i - start) + 1;
                }
            }
        }
        return Math.max(0, endExclusive - start);
    }

    private static ValidationResult failure(String detail) {
        LOGGER.warn("[Vulkairis/SpirvValidator] SPIR-V validation failed: {}", detail);
        return ValidationResult.failure(detail);
    }

    private static ValidationResult failureAt(int instructionIndex, int opcode, String reason) {
        return failure("instruction #" + instructionIndex + " (opcode " + opcode + "): " + reason);
    }
}
