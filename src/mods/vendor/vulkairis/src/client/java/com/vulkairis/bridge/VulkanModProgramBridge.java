package com.vulkairis.bridge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.SourceProvenance;
import com.vulkairis.translation.LegacyGlsl;
import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.vulkan.descriptor.DescriptorContractValidator;
import com.vulkairis.vulkan.descriptor.DescriptorSetLayoutBuilder;
import com.vulkairis.vulkan.pipeline.TerrainVertexContract;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.UnifiedTranslationService;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links an intercepted Iris program into VulkanMod's rendering system.
 *
 * <p>VulkanMod's renderer can only bind its own {@link Pipeline} type through
 * {@code VkGlProgram.glUseProgram(int)}. Iris uses our virtual program ids as if they were GL
 * program handles, which VulkanMod's registry does not know about, so before this bridge existed
 * {@code glUseProgram} silently did nothing and no shader ever executed (the observed
 * "shaders load but the image never changes" symptom).</p>
 *
 * <p>This bridge compiles the intercepted GLSL into a real VulkanMod {@link GraphicsPipeline},
 * derives its descriptor layout from SPIR-V reflection, and registers it under the virtual program
 * id so that {@code glUseProgram} resolves to a genuine Vulkan pipeline. Failures are reported
 * explicitly — a program is never silently left as a no-op.</p>
 *
 * <p>Prototype limitations (documented, not hidden): the vertex format is chosen per program from
 * a small table of {@link DefaultVertexFormat} constants that covers the shader's declared vertex
 * inputs (smallest first), and uniform/UBO <em>values</em> are not yet transported from Iris's
 * {@code glUniform*} calls into the pipeline's buffers. UBOs are declared as VulkanMod manual UBOs
 * so pipeline creation does not require VulkanMod to own per-uniform suppliers; writing those
 * buffers from Iris state is the next milestone.</p>
 */
public final class VulkanModProgramBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ProgramBridge");

    private static final Pattern SAMPLER_DECL = Pattern.compile(
            "uniform\\s+(sampler\\w+|image\\w+)\\s+(\\w+)\\s*;");

    /** Matches a {@code binding = N} token anywhere in a source; used to verify density. */
    private static final Pattern BINDING_QUALIFIER = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");

    /**
     * Ordered candidate vertex formats used to cover the vertex inputs an intercepted shader
     * declares. Ordered smallest (fewest elements) first so the first format that contains every
     * required attribute name wins.
     *
     * <p>Program-family roots (plan &#167;4.3 &#167;3.7) and the format that covers each:</p>
     * <ul>
     *   <li>Fullscreen/composite procedural passes: no vertex inputs at all (attribute-less,
     *       {@code gl_VertexID}-driven; see {@link #isProceduralFullscreenInputs}) or the
     *       Iris composite root ({@code iris_Position} + {@code iris_UV}, normalized to
     *       {@code Position} + {@code UV0} by {@code ShaderTranslator}) &mdash; covered by
     *       {@code POSITION} when input-less, {@code POSITION_TEX} otherwise. The pipeline
     *       still binds a real format (VulkanMod requires one); a shader that consumes no
     *       inputs simply ignores the provided attributes.</li>
     *   <li>Terrain ({@code BLOCK}-class: {@code Position/Color/UV0/UV2/Normal}) &mdash;
     *       covered by {@code BLOCK}.</li>
     *   <li>Entity ({@code Position/UV0} plus {@code gl_Color}/{@code gl_Normal} legacy
     *       attribs via {@link #LEGACY_ATTRIB_ALIASES}) &mdash; covered by
     *       {@code POSITION_TEX_COLOR_NORMAL}; full overlay data by {@code NEW_ENTITY}.</li>
     *   <li>Sky ({@code Position} only) &mdash; covered by {@code POSITION}.</li>
     *   <li>Cloud/fixed-function ({@code ftransform()}/{@code gl_MultiTexCoord*} legacy
     *       attribs via {@link #LEGACY_ATTRIB_ALIASES}) &mdash; covered through the same
     *       alias map, never a guaranteed {@code failLink}.</li>
     * </ul>
     *
     * <p>Depth is orthogonal to this table: every format here feeds a forward-Z pipeline
     * (Vulkan {@code [0, 1]}, clear {@code 1.0}, compare {@code LESS_OR_EQUAL}); format
     * selection never alters depth semantics and shadow clear values are owned by the
     * target layer, not by this selection.</p>
     *
     * <p>Element compositions verified against the 1.21.1 {@code DefaultVertexFormat}
     * (Blaze3D) class in this dev environment: {@code POSITION=[Position]};
     * {@code POSITION_COLOR=[Position,Color]}; {@code POSITION_TEX=[Position,UV0]};
     * {@code POSITION_TEX_COLOR=[Position,UV0,Color]};
     * {@code POSITION_COLOR_NORMAL=[Position,Color,Normal]};
     * {@code POSITION_COLOR_LIGHTMAP=[Position,Color,UV2]};
     * {@code POSITION_COLOR_TEX_LIGHTMAP=[Position,Color,UV0,UV2]};
     * {@code POSITION_TEX_LIGHTMAP_COLOR=[Position,UV0,UV2,Color]};
     * {@code POSITION_TEX_COLOR_NORMAL=[Position,UV0,Color,Normal]};
     * {@code BLOCK=[Position,Color,UV0,UV2,Normal]};
     * {@code PARTICLE=[Position,UV0,Color,UV2]};
     * {@code NEW_ENTITY=[Position,Color,UV0,UV1,UV2,Normal]}.</p>
     *
     * <p>Note: 1.21.1 has <em>no</em> {@code POSITION_COLOR_TEX} constant; the
     * position + colour + UV0 format is named {@code POSITION_TEX_COLOR}.</p>
     */
    private static final List<VertexFormat> VERTEX_FORMAT_CANDIDATES = List.of(
            DefaultVertexFormat.POSITION,
            DefaultVertexFormat.POSITION_COLOR,
            DefaultVertexFormat.POSITION_TEX,
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            DefaultVertexFormat.POSITION_TEX_COLOR,
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
            DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
            DefaultVertexFormat.BLOCK,
            DefaultVertexFormat.PARTICLE,
            DefaultVertexFormat.NEW_ENTITY);

    /**
     * Legacy fixed-function vertex attributes (as introduced by
     * {@code LegacyGlsl}: {@code vulkairis_Vertex} from {@code gl_Vertex}/
     * {@code ftransform()}, and so on) mapped onto the vanilla attribute that
     * carries the same per-vertex data.
     *
     * <p>Explicit reason per entry (why sharing the vanilla slot is sound):</p>
     * <ul>
     *   <li>{@code vulkairis_Vertex} (vec4 object-space position) to {@code Position}
     *       (vec3 float): the same mesh position; a vec4 input fed by a vec3 buffer
     *       defaults its {@code w} to {@code 1}, matching homogeneous coords.</li>
     *   <li>{@code vulkairis_Normal} to {@code Normal}: both vec3 object-space normals,
     *       exact match.</li>
     *   <li>{@code vulkairis_Color} to {@code Color}: both vec4 RGBA (vanilla
     *       {@code Color} is normalized bytes read as float vec4).</li>
     *   <li>{@code vulkairis_MultiTexCoord0} (vec4) to {@code UV0} (vec2 float base
     *       texture): unit 0 is the base texture in the compatibility profile; a vec4
     *       input fed by a vec2 buffer defaults its {@code zw} to {@code (0, 1)}.</li>
     *   <li>{@code vulkairis_MultiTexCoord1} (vec4) to {@code UV2} (ivec2 short
     *       lightmap): unit 1 is the lightmap in compatibility packs and vanilla
     *       {@code UV2} is the lightmap slot (overlay {@code UV1} has no legacy
     *       equivalent). Known limitation, documented not hidden: {@code UV2} is an
     *       integer buffer while the legacy varying is float, so exact float
     *       conversion ({@code vec2(UV2)}) is future shader-side work; the alias still
     *       unblocks format selection and location assignment so fixed-function packs
     *       are no longer a guaranteed {@code failLink}.</li>
     * </ul>
     *
     * <p>{@link #chooseVertexFormat} resolves through this map before coverage checks,
     * and {@link #buildVertexInputLocations} assigns each legacy name the same location
     * as its vanilla counterpart, so the SPIR-V input lands on the slot the pipeline's
     * vertex input state already provides.</p>
     */
    public static final Map<String, String> LEGACY_ATTRIB_ALIASES = Map.of(
            "vulkairis_Vertex", "Position",
            "vulkairis_Normal", "Normal",
            "vulkairis_Color", "Color",
            "vulkairis_MultiTexCoord0", "UV0",
            "vulkairis_MultiTexCoord1", "UV2");

    private static final Map<Integer, GraphicsPipeline> LINKED = new ConcurrentHashMap<>();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();
    private static final int MAX_FAILURES = 3;
    private static volatile boolean disabled = false;
    private static boolean reflectionReady = false;
    private static Constructor<?> glProgramCtor;
    private static Method glProgramBindPipeline;
    private static Field glProgramMap;

    private VulkanModProgramBridge() {
    }

    /**
     * Attempts to link a program. Returns true only if a real Vulkan pipeline was created and
     * registered. Never throws: failures are logged with diagnostics.
     */
    public static boolean link(VulkairisProgram program) {
        if (program == null) return false;
        int id = program.getVirtualProgramId();
        if (LINKED.containsKey(id)) return true;
        if (disabled) return false;

        if (!VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice())) {
            StageDiagnostics.warn("Program linking", program.getName() + ": Vulkan device not ready");
            return false;
        }

        VulkairisShader vert0 = program.getVertexShader();
        VulkairisShader frag0 = program.getFragmentShader();
        if (vert0 == null || frag0 == null) {
            return failLink(program.getName(), "missing vertex or fragment stage");
        }
        if (vert0.glslSource() == null || frag0.glslSource() == null) {
            return failLink(program.getName(), "missing GLSL source");
        }

        try {
            // Union-first driver path (plan §4 gap B-1): the base GLSL comes from the canonical
            // program-together translation (one shared VulkairisUniformBlock over both stages),
            // never from per-stage preprocessing. The dense 0..N-1 assignment below is the single
            // shared contract (ShaderTranslator.buildUnifiedBindingMap: injected block first,
            // then first-seen vertex->fragment).
            DriverSources base = resolveDriverSources(program);
            VulkairisShader vert = base.vert() != null ? base.vert() : vert0;
            VulkairisShader frag = base.frag() != null ? base.frag() : frag0;
            String vertSrc = base.vertGlsl();
            String fragSrc = base.fragGlsl();
            if (vertSrc == null || fragSrc == null) {
                return failLink(program.getName(), "missing GLSL source");
            }

            // The VulkanMod path needs explicit dense bindings: its descriptor array is sized
            // buffers+images but indexed by the raw binding number, so preprocessGlsl's binding
            // 100 (and any Iris sampler bindings) would index out of range. Compact every
            // descriptor into a dense 0..N-1 range before handing the source to VulkanMod,
            // using one shared map so both stages agree on bindings.
            Map<String, Integer> bindings = ShaderTranslator.buildUnifiedBindingMap(vertSrc, fragSrc);
            // remapDescriptorBindings already routes opaque declarations through
            // ensureDescriptorBindings; the explicit second pass is an idempotent belt-and-braces
            // guarantee that every sampler/image in the source handed to setShaderSrc carries the
            // same dense binding that buildDescriptorConfig() will register. Without it, a bare
            // `uniform sampler2D x;` reaches Shaderc, which rejects it for lacking layout(binding).
            String vertRemapped = ShaderTranslator.ensureDescriptorBindings(
                    ShaderTranslator.remapDescriptorBindings(vertSrc, bindings), bindings);
            String fragRemapped = ShaderTranslator.ensureDescriptorBindings(
                    ShaderTranslator.remapDescriptorBindings(fragSrc, bindings), bindings);

            if (!areBindingsDense(vertRemapped, fragRemapped, bindings.size())) {
                return failLink(program.getName(),
                        "descriptor bindings are not dense 0.." + Math.max(0, bindings.size() - 1)
                                + " after remap (injected block binding " + ShaderTranslator.UNIFORM_BLOCK_BINDING
                                + "); refusing to let VulkanMod throw IndexOutOfBoundsException");
            }

            // VulkanMod compiles the raw source handed to setShaderSrc directly with Shaderc, which
            // rejects any user in/out lacking an explicit location ("SPIR-V requires location for
            // user input/output"). Iris relies on glBindAttribLocation and ships bare declarations,
            // so pick a vertex format that covers the shader's inputs, then inject locations that
            // agree with that format and with the shared varying interface between the stages.
            List<String> requiredInputs = ShaderTranslator.collectVertexInputNames(vertRemapped);
            // Terrain programs must use VulkanMod's compressed terrain vertex format so the
            // pipeline's vertex input state matches the buffers VulkanMod actually feeds.
            VertexFormat vertexFormat = null;
            try {
                boolean terrain = false;
                String pname = program.getName();
                if (pname != null) {
                    for (com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.TerrainPass pass
                            : com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.TerrainPass.values()) {
                        if (pname.equals(com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.programNameFor(pass))) {
                            terrain = true;
                            break;
                        }
                    }
                }
                if (terrain) {
                    VertexFormat compressed = null;
                    try {
                        Class<?> cvf = Class.forName("net.vulkanmod.render.vertex.CustomVertexFormat");
                        java.lang.reflect.Field f = cvf.getField("COMPRESSED_TERRAIN");
                        Object v = f.get(null);
                        if (v instanceof VertexFormat vf) {
                            compressed = vf;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (compressed != null) {
                        java.util.Set<String> names = new java.util.HashSet<>(compressed.getElementAttributeNames());
                        boolean covered = true;
                        for (String in : requiredInputs) {
                            String resolved = LEGACY_ATTRIB_ALIASES.getOrDefault(in, in);
                            if (!names.contains(resolved)) {
                                covered = false;
                                break;
                            }
                        }
                        if (covered) {
                            vertexFormat = compressed;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (vertexFormat == null) {
                vertexFormat = chooseVertexFormat(requiredInputs);
            }
            if (vertexFormat == null) {
                return failLink(program.getName(),
                        "no DefaultVertexFormat covers vertex inputs " + requiredInputs
                                + "; refusing to build a pipeline whose SPIR-V would lack locations");
            }
            Map<String, Integer> vertexInputLocations = buildVertexInputLocations(vertexFormat);
            List<String> uncovered = new ArrayList<>();
            for (String name : requiredInputs) {
                if (!vertexInputLocations.containsKey(name)) {
                    uncovered.add(name);
                }
            }
            if (!uncovered.isEmpty()) {
                return failLink(program.getName(),
                        "vertex inputs " + uncovered + " are not covered by the chosen format "
                                + vertexFormat.getElementAttributeNames());
            }
            Map<String, Integer> varyingLocations = buildVaryingLocations(vertRemapped, fragRemapped);

            String vertFinal = ShaderTranslator.injectLayoutLocations(
                    vertRemapped, true, vertexInputLocations, varyingLocations);
            String fragFinal = ShaderTranslator.injectLayoutLocations(
                    fragRemapped, false, vertexInputLocations, varyingLocations);

            Pipeline.Builder builder = Pipeline.builder(vertexFormat, program.getName());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, vertFinal);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, fragFinal);

            PipelineConfig config = buildDescriptorConfig(vert, frag, bindings, vertSrc, fragSrc, vertRemapped, fragRemapped);
            if (config == null) {
                return failLink(program.getName(),
                        "could not build a config entry for every descriptor binding; refusing to leave a"
                                + " gap that VulkanMod would index out of range");
            }
            builder.applyConfig(config);

            // Convert every UBO descriptor into a ManualUBO so Vulkairis owns writing its
            // std140 contents (uploadUniforms) instead of relying on VulkanMod's uniform
            // suppliers, which only know OptiFine's legacy uniform names.
            try {
                java.util.List<UBO> ubos = builder.getUBOs();
                for (int i = 0; i < ubos.size(); i++) {
                    UBO ubo = ubos.get(i);
                    if (ubo instanceof ManualUBO) {
                        continue;
                    }
                    int size = Math.max(ubo.getSize(), 16);
                    ManualUBO manual = new ManualUBO(ubo.getBinding(), ubo.getStages(),
                            (size + 3) / 4);
                    ubos.set(i, manual);
                }
            } catch (Throwable ignored) {
            }

            GraphicsPipeline pipeline;
            try {
                pipeline = builder.createGraphicsPipeline();
            } catch (Throwable t) {
                dumpFailedSource(program.getName(), vertFinal, fragFinal);
                throw t;
            }
            LINKED.put(id, pipeline);

            if (!registerWithGlProgram(id, pipeline)) {
                LINKED.remove(id);
                pipeline.scheduleCleanUp();
                return failLink(program.getName(), "could not register VkGlProgram " + id);
            }

            CONSECUTIVE_FAILURES.set(0);
            program.advanceLifecycle(ShaderLifecycleState.PIPELINE_CREATED);
            StageDiagnostics.pass("Program linking", program.getName()
                    + " -> VkGlProgram " + id + " (" + pipeline.getImageDescriptors().size()
                    + " images, " + pipeline.getBuffers().size() + " UBOs)");
            return true;
        } catch (Throwable t) {
            return failLink(program.getName(), t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), t);
        }
    }

    /**
     * Writes the final VulkanMod-bound vertex/fragment sources to {@code run/vulkairis/debug/} when
     * pipeline creation fails, so a Shaderc error line number can be resolved against the exact
     * text handed to VulkanMod. Development aid; failures here must never mask the original error.
     */
    private static void dumpFailedSource(String name, String vertFinal, String fragFinal) {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().resolve("vulkairis").resolve("debug");
            java.nio.file.Files.createDirectories(dir);
            String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
            java.nio.file.Files.writeString(dir.resolve(safe + ".vert.glsl"), vertFinal);
            java.nio.file.Files.writeString(dir.resolve(safe + ".frag.glsl"), fragFinal);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Driver base sources for {@link #link}: the single place where the VulkanMod-bound path
     * obtains its pre-remap GLSL.
     *
     * @param vertGlsl vertex-stage base GLSL (union-translated when {@code unified})
     * @param fragGlsl fragment-stage base GLSL (union-translated when {@code unified})
     * @param vert     vertex shader to use for reflection (freshly re-registered when the
     *                 unified service path recompiled, otherwise the program's own shader)
     * @param frag     fragment shader to use for reflection (same freshness rule)
     * @param unified  whether both stages share the canonical program-together union block;
     *                 {@code false} only when every union attempt failed and the legacy
     *                 per-stage preprocessing fallback was used
     */
    public record DriverSources(String vertGlsl, String fragGlsl,
                                VulkairisShader vert, VulkairisShader frag, boolean unified) {
    }

    /**
     * Resolves the driver base sources for a program, union-first (plan §4 gap B-1).
     *
     * <ol>
     *   <li>Canonical path: the {@link UnifiedTranslationService} outcome for this program at
     *       the current {@link SourceProvenance} generation (reused when
     *       {@code ProgramCreatorMixin} already translated, computed otherwise). Both stages
     *       then share one identical {@code VulkairisUniformBlock}; generation gating comes
     *       from the service outcome key, so a stale generation is never reused.</li>
     *   <li>Direct fallback: {@link ProgramTranslator#translate} over the attached shaders'
     *       current sources at the current generation (union without recompilation), for
     *       programs linked without the mixin path.</li>
     *   <li>Legacy fallback: per-stage {@code preprocessGlsl} (pre-union behaviour), only
     *       when every union attempt failed.</li>
     * </ol>
     *
     * <p>Device-free: performs translation only, never touches the Vulkan device, so tests can
     * exercise the union identity the driver links against. Iris owns pack content
     * (include expansion reads Iris pack files at the current generation); VulkanMod owns
     * submission (downstream dense bindings, locations and pipeline creation).</p>
     *
     * @param program the program to resolve; its attached shaders must be present
     * @return the base sources plus the shaders to reflect against
     */
    public static DriverSources resolveDriverSources(VulkairisProgram program) {
        VulkairisShader vert = program.getVertexShader();
        VulkairisShader frag = program.getFragmentShader();

        // Canonical path: the service already runs the program-together translation (union
        // block, union varyings, legacy builtins, fragment outputs) and recompiles each stage
        // from the unified GLSL with fresh reflection. The mixin calls it just before link(),
        // so this is usually a generation-gated cache hit, not a second compile.
        try {
            long generation = SourceProvenance.currentGeneration();
            UnifiedTranslationService.TranslationOutcome outcome =
                    UnifiedTranslationService.getOutcome(program.getName(), generation);
            if (outcome == null || !outcome.succeeded()) {
                outcome = UnifiedTranslationService.translateProgram(
                        program.getName(), program.getAttachedShaderIds());
            }
            if (outcome != null && outcome.succeeded() && outcome.program() != null) {
                ProgramTranslator.TranslatedStage vertStage =
                        outcome.program().stages().get("VERTEX");
                ProgramTranslator.TranslatedStage fragStage =
                        outcome.program().stages().get("FRAGMENT");
                if (vertStage != null && fragStage != null
                        && vertStage.glsl() != null && fragStage.glsl() != null) {
                    // The service re-registered both shaders with unified GLSL and fresh
                    // reflection under the same virtual ids; re-resolve so the descriptor
                    // config below sizes the union block from the union reflection.
                    program.resolveAttachedShaders();
                    VulkairisShader freshVert = program.getVertexShader();
                    VulkairisShader freshFrag = program.getFragmentShader();
                    if (freshVert != null && freshFrag != null) {
                        vert = freshVert;
                        frag = freshFrag;
                    }
                    return new DriverSources(
                            vertStage.glsl(), fragStage.glsl(), vert, frag, true);
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the direct union attempt below.
        }

        // Direct union fallback: same canonical translator, no recompilation. Covers programs
        // linked without the mixin path (or whose service recompile failed while the pure
        // translation still succeeds); the VulkanMod path recompiles anyway with its own
        // dense bindings and locations.
        try {
            String packId = "unknown-pack";
            try {
                String current = IrisBridge.getCurrentPackName();
                if (current != null && !current.isBlank()) {
                    packId = current;
                }
            } catch (Throwable ignored) {
            }
            long generation = SourceProvenance.currentGeneration();
            List<ProgramTranslator.StageUnit> units = List.of(
                    new ProgramTranslator.StageUnit("VERTEX",
                            vert != null ? vert.glslSource() : "", vert != null ? vert.getName() : "program.vsh"),
                    new ProgramTranslator.StageUnit("FRAGMENT",
                            frag != null ? frag.glslSource() : "", frag != null ? frag.getName() : "program.fsh"));
            ProgramTranslator.TranslatedProgram translated =
                    ProgramTranslator.translate(program.getName(), units, packId, generation);
            if (translated != null) {
                ProgramTranslator.TranslatedStage vertStage = translated.stages().get("VERTEX");
                ProgramTranslator.TranslatedStage fragStage = translated.stages().get("FRAGMENT");
                if (vertStage != null && fragStage != null
                        && vertStage.glsl() != null && fragStage.glsl() != null) {
                    return new DriverSources(
                            vertStage.glsl(), fragStage.glsl(), vert, frag, true);
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the legacy per-stage path below.
        }

        // Legacy fallback (pre-union behaviour): per-stage preprocessing. Only reached when
        // every union attempt failed; the stages may then carry differing uniform blocks that
        // still share dense binding 0 until the pack is fixed.
        String vertSrc = ShaderTranslator.normalizeTypesForVulkanMod(
                ShaderTranslator.preprocessGlsl(
                        vert != null ? vert.glslSource() : null, true,
                        vert != null ? vert.getName() : null));
        String fragSrc = ShaderTranslator.normalizeTypesForVulkanMod(
                ShaderTranslator.preprocessGlsl(
                        frag != null ? frag.glslSource() : null, false,
                        frag != null ? frag.getName() : null));
        return new DriverSources(vertSrc, fragSrc, vert, frag, false);
    }

    /**
     * Records a link failure, warns only for the first few programs, then disables the feature for
     * the session so a pack that the VulkanMod SPIR-V compiler cannot handle does not flood the log.
     * The originating throwable is logged with its full stack trace so future failures are
     * diagnosable from a single log capture.
     */
    private static boolean failLink(String name, String reason) {
        return failLink(name, reason, null);
    }

    /**
     * Variant that carries the originating {@link Throwable}. The stack trace is logged once per
     * failing program (alongside the human-readable reason) while keeping the existing
     * failure-count disabling behaviour.
     */
    private static boolean failLink(String name, String reason, Throwable cause) {
        int n = CONSECUTIVE_FAILURES.incrementAndGet();
        if (n <= MAX_FAILURES) {
            if (cause != null) {
                LOGGER.warn("[Vulkairis/ProgramBridge] Program linking failed for '{}': {}", name, reason, cause);
            }
            StageDiagnostics.warn("Program linking", name + ": " + reason);
        }
        if (n == MAX_FAILURES) {
            disabled = true;
            StageDiagnostics.warn("Program linking",
                    "disabling Iris pipeline linking for this session after " + n
                            + " failures; VulkanMod's SPIR-V compiler currently rejects this pack's GLSL");
        }
        return false;
    }

    /**
     * Verifies that the union of binding numbers used across both rewritten stages is exactly
     * {@code 0..count-1}, each used at least once and none out of range. This catches a descriptor
     * the remap failed to recognise (for example an unexpected layout form), which would otherwise
     * reach VulkanMod and throw {@code IndexOutOfBoundsException}.
     *
     * <p>Kept on the driver path alongside the shared dense-binding contract
     * ({@link ShaderTranslator#buildUnifiedBindingMap}): the helper assigns
     * {@code 0..N-1} (injected block first, then first-seen vertex-&gt;fragment) and this gate
     * refuses to let VulkanMod throw when the rewritten sources disagree.</p>
     */
    private static boolean areBindingsDense(String vertSrc, String fragSrc, int count) {
        Set<Integer> seen = new HashSet<>();
        for (String source : List.of(vertSrc, fragSrc)) {
            Matcher matcher = BINDING_QUALIFIER.matcher(source);
            while (matcher.find()) {
                int binding;
                try {
                    binding = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    return false;
                }
                if (binding < 0 || binding >= count) {
                    return false;
                }
                seen.add(binding);
            }
        }
        return seen.size() == count;
    }

    /**
     * Whether the declared vertex inputs are a procedural fullscreen root: no vertex inputs
     * at all (an attribute-less fullscreen triangle/quad driven by {@code gl_VertexID}).
     *
     * <p>Such passes still link against the smallest candidate format (VulkanMod requires a
     * {@code VertexFormat} per pipeline); the shader simply consumes none of the provided
     * attributes. A {@code null} list counts as procedural for the same reason an empty one
     * does: there is nothing to cover, so there is nothing that can fail coverage.</p>
     *
     * @param requiredInputs vertex {@code in} names declared by the (translated) vertex stage;
     *        may be {@code null}
     * @return {@code true} when the pass declares no vertex inputs
     */
    public static boolean isProceduralFullscreenInputs(List<String> requiredInputs) {
        return requiredInputs == null || requiredInputs.isEmpty();
    }

    /**
     * Picks the smallest {@link DefaultVertexFormat} from {@link #VERTEX_FORMAT_CANDIDATES} whose
     * element attribute names contain every required vertex input name.
     *
     * <p>Legacy {@code vulkairis_*} inputs resolve through {@link #LEGACY_ATTRIB_ALIASES}
     * first, so a fixed-function vertex shader (for example {@code ftransform()} plus
     * {@code gl_Color}) selects the vanilla format that already provides that data
     * instead of failing the link with an uncoverable name.</p>
     *
     * <p>Procedural fullscreen passes (see {@link #isProceduralFullscreenInputs}) declare no
     * inputs and therefore select the smallest candidate ({@code POSITION}): every format
     * trivially covers the empty set, and smallest-first keeps the vertex input state
     * minimal for passes that ignore it.</p>
     *
     * @return the first covering format, or {@code null} if none covers all required names
     */
    public static VertexFormat chooseVertexFormat(List<String> requiredInputs) {
        if (isProceduralFullscreenInputs(requiredInputs)) {
            return DefaultVertexFormat.POSITION;
        }
        Set<String> required = new HashSet<>();
        if (requiredInputs != null) {
            for (String name : requiredInputs) {
                required.add(LEGACY_ATTRIB_ALIASES.getOrDefault(name, name));
            }
        }
        for (VertexFormat format : VERTEX_FORMAT_CANDIDATES) {
            if (new HashSet<>(format.getElementAttributeNames()).containsAll(required)) {
                return format;
            }
        }
        return null;
    }

    /**
     * Maps each element attribute name of {@code format} to its index in the format's element list.
     *
     * <p>VulkanMod's {@code GraphicsPipeline.getAttributeDescriptions} assigns
     * {@code VkVertexInputAttributeDescription.location = i} for the element at list index
     * {@code i}, so these indices are exactly the shader input locations the SPIR-V must declare.
     * Legacy {@code vulkairis_*} names from {@link #LEGACY_ATTRIB_ALIASES} are additionally
     * mapped to their vanilla counterpart's index, so injected locations agree with the
     * pipeline's vertex input state.</p>
     */
    public static Map<String, Integer> buildVertexInputLocations(VertexFormat format) {
        Map<String, Integer> locations = new LinkedHashMap<>();
        if (format == null) {
            return locations;
        }
        List<String> names = format.getElementAttributeNames();
        for (int i = 0; i < names.size(); i++) {
            locations.putIfAbsent(names.get(i), i);
        }
        for (Map.Entry<String, String> alias : LEGACY_ATTRIB_ALIASES.entrySet()) {
            Integer vanilla = locations.get(alias.getValue());
            if (vanilla != null) {
                locations.putIfAbsent(alias.getKey(), vanilla);
            }
        }
        return locations;
    }

    /**
     * Builds the single varying-location map shared by both stages: every vertex {@code out} name in
     * declaration order, then any fragment {@code in} names not already present. Sharing the map
     * guarantees a vertex output and its matching fragment input receive the same location.
     *
     * <p>Split-aware (plan §4 remainder): each declaration expands to scalar leaves via
     * {@link LegacyGlsl#splitVarying} / {@link LegacyGlsl#splitStructVarying} and the leaf
     * run is numbered densely via {@link LegacyGlsl#assignDenseLocations} (the rank==location
     * discipline: a matrix run never overlaps the next varying, and structs use member
     * counts instead of the single-slot fallback). The map carries <em>both</em> the base
     * name (first leaf's location, for unsplit declarations) and every leaf name, so
     * union-split sources (leaf declarations) and legacy per-stage sources (base
     * declarations) inject from the same dense assignment.</p>
     */
    private static Map<String, Integer> buildVaryingLocations(String vertSrc, String fragSrc) {
        List<LegacyGlsl.VaryingDecl> vertDecls = LegacyGlsl.collectVaryingDecls(vertSrc, "out");
        List<LegacyGlsl.VaryingDecl> fragDecls = LegacyGlsl.collectVaryingDecls(fragSrc, "in");
        List<LegacyGlsl.VaryingDecl> ordered = new ArrayList<>();
        Set<String> seenBases = new HashSet<>();
        for (LegacyGlsl.VaryingDecl decl : vertDecls) {
            if (decl != null && seenBases.add(decl.name())) {
                ordered.add(decl);
            }
        }
        for (LegacyGlsl.VaryingDecl decl : fragDecls) {
            if (decl != null && seenBases.add(decl.name())) {
                ordered.add(decl);
            }
        }
        List<LegacyGlsl.SplitLeaf> leaves = new ArrayList<>();
        for (LegacyGlsl.VaryingDecl decl : ordered) {
            leaves.addAll(LegacyGlsl.expandVaryingLeaves(
                    decl.type(), decl.name(), decl.arrayLength(), decl.structMembers()));
        }
        Map<String, Integer> leafLocations = LegacyGlsl.assignDenseLocations(leaves);
        Map<String, Integer> varyings = new LinkedHashMap<>();
        for (LegacyGlsl.VaryingDecl decl : ordered) {
            List<LegacyGlsl.SplitLeaf> declLeaves = LegacyGlsl.expandVaryingLeaves(
                    decl.type(), decl.name(), decl.arrayLength(), decl.structMembers());
            if (declLeaves.isEmpty() || !leafLocations.containsKey(declLeaves.get(0).name())) {
                continue;
            }
            varyings.putIfAbsent(decl.name(), leafLocations.get(declLeaves.get(0).name()));
            for (LegacyGlsl.SplitLeaf leaf : declLeaves) {
                Integer location = leafLocations.get(leaf.name());
                if (location != null) {
                    varyings.putIfAbsent(leaf.name(), location);
                }
            }
        }
        return varyings;
    }

    /**
     * Builds the VulkanMod descriptor config from the shared dense binding map. Samplers and the
     * injected uniform block are emitted at their remapped bindings (never their raw reflected
     * bindings), and the block is emitted once even when both stages declare it, with the stage
     * flags merged.
     *
     * <p>Returns {@code null} if any descriptor named in {@code bindings} cannot be emitted. Dense
     * bindings only stay safe when the config has exactly one entry per binding: a skipped entry
     * would leave an uninitialised slot while a higher binding is still indexed, which is the same
     * {@code IndexOutOfBoundsException} this remap exists to prevent.</p>
     */
    private static PipelineConfig buildDescriptorConfig(VulkairisShader vert, VulkairisShader frag,
                                                        Map<String, Integer> bindings,
                                                        String vertSrc, String fragSrc,
                                                        String vertRemapped, String fragRemapped) {
        PipelineConfig.Builder config = PipelineConfig.builder();
        String mergedSource = vertRemapped + "\n" + fragRemapped;

        // Reflection ran on the pre-remap sources, so a reflected block still carries its raw
        // binding. Correlate a descriptor with its reflected block by that raw binding (robust to a
        // named block instance, whose reflected variable name is the instance, not the block type).
        Map<String, Integer> rawBindings = new LinkedHashMap<>();
        for (String source : List.of(vertSrc, fragSrc)) {
            for (Map.Entry<String, Integer> raw : ShaderTranslator.collectDescriptorBindings(source).entrySet()) {
                rawBindings.putIfAbsent(raw.getKey(), raw.getValue());
            }
        }

        int imageIdx = 0;
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            String name = entry.getKey();
            int binding = entry.getValue();

            if (findSampler(vert, frag, name) != null) {
                try {
                    String type = samplerGlslType(mergedSource, name);
                    config.addImageDescriptor(binding, type, name, imageIdx++);
                } catch (Throwable t) {
                    LOGGER.warn("[Vulkairis/ProgramBridge] Could not add sampler '{}' at binding {}: {}",
                            name, binding, t.getMessage());
                    return null;
                }
                continue;
            }

            // Not a sampler: treat as a reflected interface block (the injected block or a
            // pack-declared one). Merge stage flags/size across the stages that declare it.
            // The union path strips every binding (reflection assigns later), so a descriptor
            // may have no pre-remap binding at all: correlate by reflected name first and by
            // raw binding second (robust to a named block instance, whose reflected variable
            // name is the instance, not the block type).
            Integer rawBinding = ShaderTranslator.UNIFORM_BLOCK_NAME.equals(name)
                    ? ShaderTranslator.UNIFORM_BLOCK_BINDING
                    : rawBindings.get(name);

            int stage = 0;
            int size = 0;
            boolean found = false;
            for (VulkairisShader shader : List.of(vert, frag)) {
                for (ShaderReflection.UniformBlock block : shader.getReflection().getUniformBlocks()) {
                    if (!matchesBlock(block, name, rawBinding)) {
                        continue;
                    }
                    found = true;
                    stage |= block.stage();
                    size = Math.max(size, block.size());
                }
            }
            if (!found) {
                LOGGER.warn("[Vulkairis/ProgramBridge] Descriptor '{}' (binding {}, raw {}) has no reflected block",
                        name, binding, rawBinding);
                return null;
            }
            try {
                if (stage == 0) {
                    stage = 0x1 | 0x10; // VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                }
                // A manual UBO (binding/stage/size, no members) so VulkanMod skips its uniform-supplier
                // lookup; Vulkairis owns writing this buffer.
                PipelineConfig.UB.Builder ub = PipelineConfig.UB.builder(binding, stage);
                ub.setSize(Math.max(size, 16));
                config.addUB(ub.build());
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis/ProgramBridge] Could not add UBO '{}' at binding {}: {}",
                        name, binding, t.getMessage());
                return null;
            }
        }

        PipelineConfig built = config.build();
        int entries = built.ubs.size() + built.imageDescriptors.size();
        if (entries != bindings.size()) {
            LOGGER.warn("[Vulkairis/ProgramBridge] Descriptor config has {} entries for {} dense bindings",
                    entries, bindings.size());
            return null;
        }
        return built;
    }

    private static ShaderReflection.SamplerBinding findSampler(VulkairisShader vert, VulkairisShader frag, String name) {
        ShaderReflection.SamplerBinding sampler = vert.getReflection().getSamplers().get(name);
        if (sampler == null) {
            sampler = frag.getReflection().getSamplers().get(name);
        }
        return sampler;
    }

    /**
     * Whether a reflected block corresponds to the descriptor named {@code name}. Matches by reflected
     * variable name when possible, but falls back to the pre-remap raw binding so a named block
     * instance (whose reflected name is the instance, not the GLSL block type) still resolves.
     * A {@code null} raw binding (union-translated sources carry no bindings) matches by name only.
     */
    private static boolean matchesBlock(ShaderReflection.UniformBlock block, String name, Integer rawBinding) {
        if (block.name().equals(name)) {
            return true;
        }
        return rawBinding != null && block.binding() == rawBinding;
    }

    private static String samplerGlslType(String source, String name) {
        if (source != null) {
            Matcher matcher = SAMPLER_DECL.matcher(source);
            while (matcher.find()) {
                if (matcher.group(2).equals(name)) {
                    String glslType = matcher.group(1);
                    return glslType.startsWith("image") ? "image2D" : "sampler2D";
                }
            }
        }
        return "sampler2D";
    }

    /**
     * Inserts a {@code VkGlProgram} entry for the virtual id via reflection, so VulkanMod's own
     * {@code glUseProgram} path binds the pipeline at the correct point in the frame.
     */
    private static boolean registerWithGlProgram(int id, Pipeline pipeline) {
        try {
            Class<?> glProgramClass = Class.forName("net.vulkanmod.gl.VkGlProgram");
            if (!reflectionReady) {
                glProgramCtor = glProgramClass.getDeclaredConstructor(int.class);
                glProgramCtor.setAccessible(true);
                glProgramBindPipeline = glProgramClass.getMethod("bindPipeline", Pipeline.class);
                glProgramMap = glProgramClass.getDeclaredField("map");
                glProgramMap.setAccessible(true);
                reflectionReady = true;
            }

            Object glProgram = glProgramCtor.newInstance(id);
            glProgramBindPipeline.invoke(glProgram, pipeline);

            Object map = glProgramMap.get(null);
            if (map instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> typed = (Map<Object, Object>) raw;
                typed.put(id, glProgram);
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis/ProgramBridge] VkGlProgram registration failed for id {}: {}", id, t.getMessage());
            return false;
        }
    }

    public static GraphicsPipeline getLinkedPipeline(int virtualProgramId) {
        return LINKED.get(virtualProgramId);
    }

    /**
     * Obtains a GraphicsPipeline for a specific terrain render type.
     * Currently only supports gbuffers_terrain.
     */
    public static GraphicsPipeline getTerrainPipeline(String programName) {
        int id = terrainLinkedId(programName);
        return id > 0 ? getLinkedPipeline(id) : null;
    }

    /**
     * Pack program name for a VulkanMod terrain render type, mirroring
     * {@link com.vulkairis.vulkan.pipeline.TerrainPipelineDoor} naming with the
     * cutout/translucent -> terrain fallback handled by {@link #getTerrainPipelineForType}.
     */
    public static String programNameForTerrainType(net.vulkanmod.render.vertex.TerrainRenderType type) {
        if (type == null) {
            return "gbuffers_terrain";
        }
        return switch (type) {
            case SOLID, CUTOUT_MIPPED, TRIPWIRE -> "gbuffers_terrain";
            case CUTOUT -> "gbuffers_terrain_cutout";
            case TRANSLUCENT -> "gbuffers_translucent";
        };
    }

    /**
     * Resolves the linked pack pipeline for a VulkanMod terrain render type, applying the
     * cutout/translucent -> gbuffers_terrain fallback chain.
     */
    public static GraphicsPipeline getTerrainPipelineForType(net.vulkanmod.render.vertex.TerrainRenderType type) {
        String program = programNameForTerrainType(type);
        int id = terrainLinkedId(program);
        if (id <= 0 && !"gbuffers_terrain".equals(program)) {
            id = terrainLinkedId("gbuffers_terrain");
        }
        return id > 0 ? getLinkedPipeline(id) : null;
    }

    /**
     * Uploads the per-frame uniform payload (OfGlobals std140 block) into every ManualUBO
     * of the linked pipeline so VulkanMod's bind path copies fresh values before draw.
     * Never throws.
     */
    public static void uploadUniforms(GraphicsPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        try {
            UniformBridge bridge = UniformBridge.getInstance();
            java.util.List<UBO> ubos = pipeline.getBuffers();
            if (ubos == null) {
                return;
            }
            for (UBO ubo : ubos) {
                if (!(ubo instanceof ManualUBO manual)) {
                    continue;
                }
                int size = Math.max(ubo.getSize(), 16);
                java.nio.ByteBuffer buf = MANUAL_UBO_BUFFERS.computeIfAbsent(ubo, k -> {
                    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(size)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    return b;
                });
                if (buf.capacity() < size) {
                    buf = java.nio.ByteBuffer.allocateDirect(size)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    MANUAL_UBO_BUFFERS.put(ubo, buf);
                }
                buf.clear();
                // Zero-fill first so unused fields stay deterministic.
                for (int i = 0; i < size; i++) {
                    buf.put((byte) 0);
                }
                buf.clear();
                // Fill the std140 OfGlobals payload into the front of the buffer.
                try {
                    bridge.writeOfGlobals(buf);
                } catch (Throwable ignored) {
                }
                buf.flip();
                long addr = org.lwjgl.system.MemoryUtil.memAddress0(buf);
                manual.setSrc(addr, buf.remaining());
                manual.setUpdate(true);
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/ProgramBridge] Uniform upload notice: {}",
                    t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<UBO, java.nio.ByteBuffer> MANUAL_UBO_BUFFERS =
            new java.util.concurrent.ConcurrentHashMap<>();


    // ------------------------------------------------------------------
    // Phase D terrain door (narrow additions; existing link() behavior unchanged).
    //
    // The frozen SodiumPrograms path asks here, by pack program name, whether a VulkanMod
    // pipeline is already linked for the handed terrain pass. Registration is explicit:
    // linkTerrainProgram() records the virtual id a successful link() produced, so the
    // door's HEAD decision (TerrainPipelineDoor.decideCompile) can return the pipeline
    // handle path without re-solving descriptors every draw.
    // ------------------------------------------------------------------

    private static final Map<String, Integer> TERRAIN_PROGRAM_IDS = new ConcurrentHashMap<>();

    /**
     * Records the virtual program id backing a terrain pack program.
     *
     * @param programName pack program name (e.g. {@code "gbuffers_terrain"})
     * @param virtualProgramId linked virtual id, or {@code <= 0} to clear the entry
     */
    public static void noteTerrainProgram(String programName, int virtualProgramId) {
        if (programName == null || programName.isBlank()) {
            return;
        }
        if (virtualProgramId <= 0) {
            TERRAIN_PROGRAM_IDS.remove(programName);
        } else {
            TERRAIN_PROGRAM_IDS.put(programName, virtualProgramId);
        }
    }

    /**
     * @param programName pack program name
     * @return the linked virtual id with a live pipeline, or 0 when none
     */
    public static int terrainLinkedId(String programName) {
        if (programName == null || programName.isBlank()) {
            return 0;
        }
        Integer id = TERRAIN_PROGRAM_IDS.get(programName);
        if (id == null || id <= 0) {
            return 0;
        }
        return LINKED.containsKey(id) ? id : 0;
    }

    /**
     * Head-of-compile terrain decision for an Iris pass name.
     *
     * @param irisPassName handed pass name (e.g. {@code "TERRAIN"})
     * @return pack decision when a live pipeline is linked, otherwise a fallback decision
     *         naming the missing pipeline
     */
    public static com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.DoorDecision terrainCompileDecision(
            String irisPassName) {
        com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.TerrainPass pass =
                com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.forPassName(irisPassName);
        String program = com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.programNameFor(pass);
        return com.vulkairis.vulkan.pipeline.TerrainPipelineDoor.decideCompile(pass, terrainLinkedId(program));
    }

    /** Shader stages consuming terrain descriptors: vertex + fragment. */
    public static final long TERRAIN_STAGE_FLAGS = 0x1L | 0x10L;

    /**
     * Merged current-generation reflection for a terrain pack program, or {@code null}
     * when unavailable. Merges the vertex + fragment uniform blocks, samplers and
     * uniforms of the registered program (terrain registration first, then registry
     * lookup by pack name), deduplicated by descriptor name. Device-free: registry reads
     * only, never touches the Vulkan device.
     *
     * @param programName pack program name (e.g. {@code "gbuffers_terrain"})
     * @return merged reflection, or {@code null} when the program or either stage is unknown
     */
    public static ShaderReflection terrainReflectionFor(String programName) {
        try {
            if (programName == null || programName.isBlank()) {
                return null;
            }
            com.vulkairis.translation.VulkairisProgram program = null;
            Integer id = TERRAIN_PROGRAM_IDS.get(programName);
            if (id != null) {
                program = VulkanShaderRegistry.getProgram(id);
            }
            if (program == null) {
                program = VulkanShaderRegistry.findProgramByName(programName);
            }
            if (program == null) {
                return null;
            }
            VulkairisShader vert = program.getVertexShader();
            VulkairisShader frag = program.getFragmentShader();
            if (vert == null || frag == null
                    || vert.getReflection() == null || frag.getReflection() == null) {
                return null;
            }
            ShaderReflection merged = new ShaderReflection();
            java.util.Set<String> seenBlocks = new java.util.HashSet<>();
            boolean complete = true;
            for (VulkairisShader shader : java.util.List.of(vert, frag)) {
                ShaderReflection reflection = shader.getReflection();
                complete &= reflection.isComplete();
                for (ShaderReflection.UniformBlock block : reflection.getUniformBlocks()) {
                    if (block != null && seenBlocks.add(block.name())) {
                        merged.addUniformBlock(block.name(), block.set(), block.binding(),
                                block.stage(), block.size(), block.members());
                    }
                }
            }
            java.util.Set<String> seenSamplers = new java.util.HashSet<>();
            for (VulkairisShader shader : java.util.List.of(vert, frag)) {
                ShaderReflection reflection = shader.getReflection();
                for (ShaderReflection.SamplerBinding sampler
                        : reflection.getSamplers().values()) {
                    if (sampler != null && seenSamplers.add(sampler.name())) {
                        merged.addSampler(sampler.name(), sampler.binding(),
                                sampler.textureUnit(), sampler.descriptorCount());
                    }
                }
                for (Map.Entry<String, ShaderReflection.UniformInfo> uniform
                        : reflection.getUniforms().entrySet()) {
                    ShaderReflection.UniformInfo info = uniform.getValue();
                    if (info != null && !merged.hasUniform(info.name())) {
                        merged.addUniform(info.name(), info.type(), info.offset(), info.size());
                    }
                }
            }
            if (!complete) {
                merged.markIncomplete();
            }
            return merged;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Provided descriptor bindings for the linked terrain pipeline, correlated by
     * descriptor name into the required plan's numbering domain.
     *
     * <p>The linked pipeline carries dense post-remap binding numbers while pack
     * reflections carry pre-remap numbers, so a raw numeric comparison would refuse
     * every pack path on renumbering alone. Instead each required plan entry is matched
     * to the linked descriptor with the same name: matches are emitted in the required
     * numbering (renumbering never fails the gate), type drift is reported with the
     * pipeline's actual type, and pack-required names absent from the pipeline are
     * omitted so validation reports them missing. Array counts are mirrored from the
     * required plan because VulkanMod's descriptor getters expose no array count;
     * presence and type are strictly checked.</p>
     *
     * @param linkedVirtualProgramId linked virtual id with a live pipeline
     * @param reflection merged pack reflection the required plan is built from
     * @param stageFlags stage bits consuming the resources
     * @return correlated provided specs, or {@code null} when no live pipeline or no
     *         reflection is available (the lenient gate then skips, it never refuses)
     */
    public static List<DescriptorContractValidator.BindingSpec> terrainProvidedFor(
            int linkedVirtualProgramId, ShaderReflection reflection, long stageFlags) {
        try {
            if (reflection == null || linkedVirtualProgramId <= 0) {
                return null;
            }
            GraphicsPipeline pipeline = LINKED.get(linkedVirtualProgramId);
            if (pipeline == null) {
                return null;
            }
            List<DescriptorSetLayoutBuilder.DescriptorBinding> plan =
                    DescriptorSetLayoutBuilder.forGraphics(reflection, stageFlags);
            if (plan.isEmpty()) {
                return List.of();
            }
            Map<String, Integer> linkedTypes = new java.util.HashMap<>();
            Map<String, Long> linkedStages = new java.util.HashMap<>();
            try {
                List<UBO> buffers = pipeline.getBuffers();
                if (buffers != null) {
                    for (UBO ub : buffers) {
                        if (ub != null && ub.name != null) {
                            linkedTypes.putIfAbsent(ub.name, ub.getType());
                            linkedStages.putIfAbsent(ub.name, (long) ub.getStages());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                List<ImageDescriptor> images = pipeline.getImageDescriptors();
                if (images != null) {
                    for (ImageDescriptor image : images) {
                        if (image != null && image.name != null) {
                            linkedTypes.putIfAbsent(image.name, image.getType());
                            linkedStages.putIfAbsent(image.name, (long) image.getStages());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            List<DescriptorContractValidator.BindingSpec> provided = new ArrayList<>();
            for (DescriptorSetLayoutBuilder.DescriptorBinding required : plan) {
                Integer linkedType = linkedTypes.get(required.name());
                if (linkedType == null) {
                    continue;
                }
                long stages = linkedStages.getOrDefault(required.name(), required.stageFlags());
                provided.add(new DescriptorContractValidator.BindingSpec(
                        required.binding(), linkedType,
                        required.descriptorCount(), stages));
            }
            return List.copyOf(provided);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return the extended terrain vertex stride in bytes (68)
     * @see TerrainVertexContract#EXTENDED_STRIDE_BYTES
     */
    public static int terrainVertexStride() {
        return TerrainVertexContract.EXTENDED_STRIDE_BYTES;
    }

    /**
     * @return the Sodium 0.8 compact terrain base stride in bytes (20, preserved verbatim)
     * @see TerrainVertexContract#BASE_STRIDE_BYTES
     */
    public static int terrainVertexBaseStride() {
        return TerrainVertexContract.BASE_STRIDE_BYTES;
    }

    /**
     * @return extended attribute name to byte offset, in append order after the base
     * @see TerrainVertexContract#offsets()
     */
    public static Map<String, Integer> terrainVertexOffsets() {
        return TerrainVertexContract.offsets();
    }

    /**
     * Maps each extended attribute name to its shader input location: base inputs occupy
     * locations {@code 0..baseLocationCount-1}, extensions follow in append order.
     *
     * @param baseLocationCount locations consumed by the base layout
     * @return attribute name to input location, in append order
     * @see TerrainVertexContract#inputLocations(int)
     */
    public static Map<String, Integer> terrainVertexInputLocations(int baseLocationCount) {
        return TerrainVertexContract.inputLocations(baseLocationCount);
    }

    /**
     * Whether every required vertex input is served by the vanilla block fetch.
     *
     * @param requiredInputs vertex input names a pack shader declares
     * @return {@code true} when no extended attribute fetch is needed
     * @see TerrainVertexContract#isCoveredByBlock(java.util.Collection)
     */
    public static boolean isTerrainVertexCoveredByBlock(List<String> requiredInputs) {
        return TerrainVertexContract.isCoveredByBlock(requiredInputs);
    }

    /**
     * @return single-line evidence for why {@code DefaultVertexFormat.BLOCK} suffices on
     *         the Sodium 0.8 compact path and where the extended stride applies
     * @see TerrainVertexContract#describeBlockMapping()
     */
    public static String terrainVertexEvidence() {
        return TerrainVertexContract.describeBlockMapping();
    }

    /** Clears terrain program registrations. Test/shutdown helper; leaves LINKED untouched. */
    public static void clearTerrainRegistrations() {
        TERRAIN_PROGRAM_IDS.clear();
    }

    public static void unlink(int virtualProgramId) {
        GraphicsPipeline pipeline = LINKED.remove(virtualProgramId);
        if (pipeline != null) {
            try {
                pipeline.scheduleCleanUp();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cleanUp() {
        for (GraphicsPipeline pipeline : LINKED.values()) {
            try {
                pipeline.scheduleCleanUp();
            } catch (Throwable ignored) {
            }
        }
        LINKED.clear();
    }
}
