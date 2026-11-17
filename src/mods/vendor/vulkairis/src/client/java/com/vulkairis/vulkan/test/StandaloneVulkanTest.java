package com.vulkairis.vulkan.test;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vulkairis.core.DebugRenderMode;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.pass.MainPass;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controlled Vulkan renderer (plan sections 12 and 13).
 *
 * <p>Two debug modes exercise this class:</p>
 * <ul>
 *     <li>{@code TRIANGLE} — the built-in test shaders. A failure proves the Vulkan pipeline,
 *     not the translator, is at fault.</li>
 *     <li>{@code SHADER_TEST} — user-editable GLSL from {@code run/vulkairis/debug_shader.*}.
 *     Editing the files and watching the rendered image change proves GLSL is being compiled to
 *     SPIR-V, turned into real {@code VkShaderModule}s, attached to a real pipeline and drawn.</li>
 * </ul>
 *
 * <p>Both paths run through the same in-process Shaderc + VkShaderModule + VkPipeline code that
 * the Iris interception path uses. Neither path re-feeds GLSL to a VulkanMod pipeline and neither
 * issues a raw OpenGL call.</p>
 */
public class StandaloneVulkanTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/StandaloneTest");

    private static final String BASIC_VERT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 1) in vec4 Color;
            layout (location = 0) out vec4 vertexColor;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vertexColor = Color;
            }
            """;

    private static final String BASIC_FRAG = """
            #version 450 core
            layout (location = 0) in vec4 vertexColor;
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vertexColor;
            }
            """;

    private static final String DISK_VERT_DEFAULT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 1) in vec4 Color;
            layout (location = 0) out vec4 vertexColor;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vertexColor = Color;
            }
            """;

    private static final String DISK_FRAG_DEFAULT = """
            #version 450 core
            layout (location = 0) in vec4 vertexColor;
            layout (location = 0) out vec4 fragColor;
            // Edit this shader and watch the controlled quad change.
            void main() {
                fragColor = vec4(fract(vertexColor.r + 0.15), vertexColor.g, 0.95, 1.0);
            }
            """;

    /**
     * Vertex stage shared by every diagnostic visualizer.
     *
     * <p>The built-in controlled vertex format ({@code DefaultVertexFormat.POSITION_COLOR}) has
     * only a position and a colour attribute — there is no UV or normal input. Rather than
     * fabricate attributes, the visualizers derive a stable 0..1 UV from the quad's actual NDC
     * position ({@code Position.xy + 0.5}) and pass it to the fragment stage. Modes that would
     * need a real normal (or a real lightmap coordinate) are documented as approximations in
     * {@link #executeVisualizerDraw}.</p>
     */
    private static final String VISUALIZER_VERT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 1) in vec4 Color;
            layout (location = 0) out vec4 vertexColor;
            layout (location = 1) out vec2 vUv;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vertexColor = Color;
                vUv = Position.xy + 0.5;
            }
            """;

    private static final Path SHADER_DIR = resolveShaderDir();
    private static final Path DISK_VERT = SHADER_DIR.resolve("debug_shader.vert");
    private static final Path DISK_FRAG = SHADER_DIR.resolve("debug_shader.frag");

    /**
     * Resolves the debug shader directory without making class initialisation depend on a live
     * Fabric loader. The controlled-draw policy helper is unit-testable from a plain JUnit JVM,
     * and a null loader there must not turn into an {@link ExceptionInInitializerError}; in-game
     * the loader is always present and this behaves exactly as before.
     */
    private static Path resolveShaderDir() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            if (loader != null) {
                return loader.getGameDir().resolve("vulkairis");
            }
        } catch (Throwable ignored) {
            // Not running under Fabric (for example a unit test JVM); fall through.
        }
        return Path.of("vulkairis").toAbsolutePath();
    }

    // Built-in (TRIANGLE) state.
    private static VulkairisShader vertShader = null;
    private static VulkairisShader fragShader = null;
    private static long testDescriptorSetLayout = 0L;
    private static long testDescriptorSet = 0L;
    private static long testPipelineLayout = 0L;
    private static final Map<Long, Long> PIPELINES_BY_RENDER_PASS = new ConcurrentHashMap<>();
    private static int drawCount = 0;
    private static final int MAX_TEST_DRAWS = 600;
    private static boolean testPassed = false;
    private static boolean initialized = false;

    // SHADER_TEST state.
    private static final Map<Long, Long> SHADER_TEST_PIPELINES = new ConcurrentHashMap<>();
    private static VulkairisShader diskVert = null;
    private static VulkairisShader diskFrag = null;
    private static volatile long diskLastModified = -1L;
    private static volatile boolean diskReady = false;
    private static int diskDrawCount = 0;
    private static final int MAX_DISK_DRAWS = 600;
    private static boolean diskPassed = false;

    // Diagnostic visualizer state (plan sections 6 and 9). Unlike the one-shot proofs above,
    // visualizers keep drawing while their mode is selected, so there is no draw cap.
    private static VulkairisShader visualizerVert = null;
    private static final Map<DebugRenderMode, VulkairisShader> VISUALIZER_FRAGS = new ConcurrentHashMap<>();
    private static final Map<String, Long> VISUALIZER_PIPELINES = new ConcurrentHashMap<>();

    // Last viewport/scissor extent the controlled path applied. Dynamic Vulkan state is sticky
    // across render passes, so it is recorded here and restored before GUI geometry is recorded.
    private static volatile int lastViewportWidth = 0;
    private static volatile int lastViewportHeight = 0;

    // World-pass gating / diagnostics (plan sections 1, 5 and 13).
    private static final long DIAGNOSTIC_FRAME_INTERVAL = 200L;
    private static final long SKIP_LOG_INTERVAL = 120L;
    private static long lastDiagnosticFrame = Long.MIN_VALUE;
    private static boolean lastDiagnosticValid = false;
    private static boolean lastWorldPassActive = false;
    private static long lastSkipFrame = -SKIP_LOG_INTERVAL;
    private static long lastGuiSkipFrame = -SKIP_LOG_INTERVAL;

    /**
     * Pure, side-effect-free policy for whether a controlled draw is allowed to record.
     *
     * <p>Extracted so the "never leak onto the menu" rule is structural and unit-testable rather
     * than buried in a renderer call. A controlled draw is only permitted when <em>all</em> of the
     * following hold: the mode is a controlled mode, a level and player are loaded, no GUI screen
     * is open, the currently bound framebuffer is the main-pass framebuffer, and the bound render
     * pass id is non-zero.</p>
     */
    public static boolean shouldDrawControlled(DebugRenderMode mode, boolean levelLoaded,
                                               boolean playerPresent, boolean screenOpen,
                                               boolean boundTargetsMainPass, boolean passIdNonZero) {
        return mode != null && mode.isControlled()
                && levelLoaded
                && playerPresent
                && !screenOpen
                && boundTargetsMainPass
                && passIdNonZero;
    }

    /**
     * True only when a controlled draw would land in a real world frame.
     *
     * <p>VulkanMod opens its main render pass every frame — including the title screen — so the
     * bound framebuffer alone cannot distinguish a world frame from a menu frame. The decisive
     * signal is Minecraft's client state: no loaded level/player, or an open GUI screen, means no
     * world frame. On top of that we require the bound pass to target the main pass framebuffer,
     * which excludes the swap chain and off-screen/post targets.</p>
     *
     * <p>This gathers the environment and delegates the decision to
     * {@link #shouldDrawControlled}; the policy itself stays pure and testable.</p>
     */
    public static boolean isWorldPassActive(Renderer renderer) {
        Minecraft client = Minecraft.getInstance();
        boolean levelLoaded = client != null && client.level != null;
        boolean playerPresent = client != null && client.player != null;
        boolean screenOpen = client != null && client.screen != null;

        boolean boundTargetsMainPass = false;
        boolean passIdNonZero = false;
        if (renderer != null) {
            MainPass mainPass = renderer.getMainPass();
            if (mainPass != null) {
                Framebuffer mainFramebuffer = mainPass.getMainFramebuffer();
                boundTargetsMainPass = mainFramebuffer != null
                        && renderer.getBoundFramebuffer() == mainFramebuffer;
            }
            RenderPass boundPass = renderer.getBoundRenderPass();
            passIdNonZero = boundPass != null && boundPass.getId() != 0L;
        }

        // Every controlled mode shares the same world-only policy, so TRIANGLE is a representative
        // sentinel here; callers that need the per-mode answer call shouldDrawControlled directly.
        return shouldDrawControlled(DebugRenderMode.TRIANGLE, levelLoaded, playerPresent, screenOpen,
                boundTargetsMainPass, passIdNonZero);
    }

    /**
     * Low-noise, at-most-once-per-frame diagnostic describing the render target currently being
     * drawn to. It logs on world/menu transitions and then at most every
     * {@link #DIAGNOSTIC_FRAME_INTERVAL} frames, which is enough to prove the menu never enters
     * the controlled draw path.
     */
    public static void logRenderTargetDiagnostic(DebugRenderMode mode) {
        Renderer renderer = Renderer.getInstance();
        if (renderer == null) return;

        // VulkanMod also calls endRenderPass() defensively with nothing bound; those carry no
        // target information. Sample only calls that are ending a real pass.
        RenderPass pass = renderer.getBoundRenderPass();
        if (pass == null || pass.getId() == 0L) return;

        long frame = VulkairisController.getInstance().getFrameCount();
        if (frame == lastDiagnosticFrame) return;
        lastDiagnosticFrame = frame;

        boolean worldActive = isWorldPassActive(renderer);
        boolean transition = !lastDiagnosticValid || worldActive != lastWorldPassActive;
        boolean intervalElapsed = frame > 0L && frame % DIAGNOSTIC_FRAME_INTERVAL == 0L;
        if (!transition && !intervalElapsed) return;

        lastDiagnosticValid = true;
        lastWorldPassActive = worldActive;

        Minecraft client = Minecraft.getInstance();
        Framebuffer bound = renderer.getBoundFramebuffer();
        MainPass mainPass = renderer.getMainPass();
        Framebuffer main = mainPass != null ? mainPass.getMainFramebuffer() : null;

        LOGGER.info("[Vulkairis/Test] frame={} mode={} worldPass={} boundFb={}#{} boundPass=0x{} mainFb={}#{} levelLoaded={} screen={}",
                frame,
                mode,
                worldActive,
                bound != null ? bound.getClass().getSimpleName() : "none",
                bound != null ? Integer.toHexString(System.identityHashCode(bound)) : "0",
                pass != null ? Long.toHexString(pass.getId()) : "0",
                main != null ? main.getClass().getSimpleName() : "none",
                main != null ? Integer.toHexString(System.identityHashCode(main)) : "0",
                client != null && client.level != null,
                client != null && client.screen != null ? client.screen.getClass().getSimpleName() : "none");
    }

    /** Explicit, rate-limited log for a controlled quad suppressed because no world pass is active. */
    private static void logSkippedNoWorldPass(DebugRenderMode mode) {
        long frame = VulkairisController.getInstance().getFrameCount();
        if (frame - lastSkipFrame < SKIP_LOG_INTERVAL) return;
        lastSkipFrame = frame;

        Minecraft client = Minecraft.getInstance();
        LOGGER.info("[Vulkairis/Test] Skipping controlled {} quad: no active world/main pass (frame={}, levelLoaded={}, screen={})",
                mode,
                frame,
                client != null && client.level != null,
                client != null && client.screen != null ? client.screen.getClass().getSimpleName() : "none");
    }

    /**
     * Explicit, rate-limited log for a controlled quad suppressed specifically because a GUI
     * screen is open. This is the menu-leak signal: if it ever fires while a screen is visible the
     * gate is doing its job.
     */
    private static void logSkippedGuiOpen(DebugRenderMode mode) {
        long frame = VulkairisController.getInstance().getFrameCount();
        if (frame - lastGuiSkipFrame < SKIP_LOG_INTERVAL) return;
        lastGuiSkipFrame = frame;

        Minecraft client = Minecraft.getInstance();
        LOGGER.info("[Vulkairis/Test] Skipping controlled {} quad: GUI screen open (frame={}, levelLoaded={}, screen={})",
                mode,
                frame,
                client != null && client.level != null,
                client != null && client.screen != null ? client.screen.getClass().getSimpleName() : "none");
    }

    /** Picks the most informative suppression reason for the current frame. */
    private static void logSkipped(DebugRenderMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.screen != null) {
            logSkippedGuiOpen(mode);
        } else {
            logSkippedNoWorldPass(mode);
        }
    }

    public static synchronized boolean initTestPipeline() {
        if (initialized) return true;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) {
            LOGGER.warn("[Vulkairis/Test] Vulkan device not live yet.");
            return false;
        }

        try {
            LOGGER.info("[Vulkairis/Test] Initializing controlled Vulkan renderer...");
            PipelineManager.getOrCreatePipelineCache();
            StageDiagnostics.pass("Vulkan device", "live");

            vertShader = SpirvCompiler.compileShader("test_vert", BASIC_VERT, ShaderType.VERTEX);
            fragShader = SpirvCompiler.compileShader("test_frag", BASIC_FRAG, ShaderType.FRAGMENT);

            if (vertShader == null || fragShader == null
                    || vertShader.getVkShaderModule() == 0L || fragShader.getVkShaderModule() == 0L) {
                StageDiagnostics.fail("VkShaderModule creation", "test shaders produced no module", null);
                return false;
            }
            StageDiagnostics.pass("VkShaderModule creation",
                    "vert=0x" + Long.toHexString(vertShader.getVkShaderModule())
                            + ", frag=0x" + Long.toHexString(fragShader.getVkShaderModule()));

            testDescriptorSetLayout = VulkanDescriptorManager.getOrCreateDescriptorSetLayout(List.of(
                    new VulkanDescriptorManager.BindingInfo(0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                            VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 1)
            ));
            testDescriptorSet = VulkanDescriptorManager.allocateDescriptorSet(testDescriptorSetLayout);
            if (testDescriptorSet != 0L) {
                VulkanDescriptorManager.updateTextureBinding(testDescriptorSet, 0, 0);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
                if (testDescriptorSetLayout != 0L) {
                    layoutInfo.pSetLayouts(stack.longs(testDescriptorSetLayout));
                }

                LongBuffer pLayout = stack.mallocLong(1);
                int res = VK10.vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
                if (res != VK10.VK_SUCCESS) {
                    StageDiagnostics.fail("Pipeline layout", "vkCreatePipelineLayout error " + res, null);
                    return false;
                }
                testPipelineLayout = pLayout.get(0);
            }
            StageDiagnostics.pass("Pipeline layout", "0x" + Long.toHexString(testPipelineLayout));

            initialized = true;
            return true;
        } catch (RuntimeException t) {
            StageDiagnostics.fail("Controlled renderer initialization", t.getMessage(), t);
            return false;
        }
    }

    private static long getOrCreatePipelineForPass(long renderPassId) {
        return PIPELINES_BY_RENDER_PASS.computeIfAbsent(renderPassId, passId -> {
            VkDevice device = VulkanBackendAdapter.getDevice();
            VulkanPipelineFactory.registerShaderPair(vertShader, fragShader);
            VulkanPipelineFactory.PipelineParams params = new VulkanPipelineFactory.PipelineParams(
                    vertShader.getVkShaderModule(),
                    fragShader.getVkShaderModule(),
                    testPipelineLayout,
                    passId,
                    DefaultVertexFormat.POSITION_COLOR,
                    false,
                    false,
                    false,
                    true,
                    PipelineManager.getOrCreatePipelineCache()
            );
            long pipeline = VulkanPipelineFactory.createGraphicsPipeline(device, params);
            StageDiagnostics.pass("Graphics pipeline", "0x" + Long.toHexString(pipeline)
                    + " for render pass 0x" + Long.toHexString(passId));
            return pipeline;
        });
    }

    /** Built-in controlled quad (debug mode {@code TRIANGLE}). */
    public static void executeTestDraw() {
        executeTestDraw(Renderer.getCommandBuffer());
    }

    public static void executeTestDraw(VkCommandBuffer cmdBuffer) {
        if (!initTestPipeline()) return;
        if (drawCount >= MAX_TEST_DRAWS) return;

        try {
            Renderer renderer = Renderer.getInstance();
            if (cmdBuffer == null) cmdBuffer = Renderer.getCommandBuffer();
            if (renderer == null || cmdBuffer == null) return;

            if (!isWorldPassActive(renderer)) {
                logSkipped(DebugRenderMode.TRIANGLE);
                return;
            }

            RenderPass boundPass = renderer.getBoundRenderPass();
            if (boundPass == null || boundPass.getId() == 0L) return;

            long pipeline = getOrCreatePipelineForPass(boundPass.getId());
            if (pipeline == 0L) return;

            drawQuad(cmdBuffer, renderer, pipeline, testPipelineLayout, testDescriptorSet);

            drawCount++;
            if (!testPassed) {
                testPassed = true;
                StageDiagnostics.pass("Command recording",
                        "vkCmdBindPipeline + viewport/scissor + descriptor set + vkCmdDraw(4)");
                StageDiagnostics.pass("Draw submitted", "controlled quad via real VkShaderModules "
                        + "(vert=0x" + Long.toHexString(vertShader.getVkShaderModule())
                        + ", frag=0x" + Long.toHexString(fragShader.getVkShaderModule()) + ")");
            }
        } catch (NullPointerException ignored) {
            // Active render pass not bound yet; retry next frame.
        } catch (RuntimeException t) {
            StageDiagnostics.fail("Command recording", t.getMessage(), t);
        }
    }

    /**
     * User-editable controlled quad (debug mode {@code SHADER_TEST}). Reloads
     * {@code run/vulkairis/debug_shader.vert/.frag} whenever their timestamp changes.
     */
    public static void executeShaderTestDraw() {
        executeShaderTestDraw(Renderer.getCommandBuffer());
    }

    public static void executeShaderTestDraw(VkCommandBuffer cmdBuffer) {
        if (!initTestPipeline()) return;
        if (!ensureDiskShaders()) return;
        if (diskDrawCount >= MAX_DISK_DRAWS) return;

        try {
            Renderer renderer = Renderer.getInstance();
            if (cmdBuffer == null) cmdBuffer = Renderer.getCommandBuffer();
            if (renderer == null || cmdBuffer == null) return;

            if (!isWorldPassActive(renderer)) {
                logSkipped(DebugRenderMode.SHADER_TEST);
                return;
            }

            RenderPass boundPass = renderer.getBoundRenderPass();
            if (boundPass == null || boundPass.getId() == 0L) return;

            long pipeline = SHADER_TEST_PIPELINES.computeIfAbsent(boundPass.getId(), passId -> {
                VulkanPipelineFactory.registerShaderPair(diskVert, diskFrag);
                VulkanPipelineFactory.PipelineParams params = new VulkanPipelineFactory.PipelineParams(
                        diskVert.getVkShaderModule(),
                        diskFrag.getVkShaderModule(),
                        testPipelineLayout,
                        passId,
                        DefaultVertexFormat.POSITION_COLOR,
                        false,
                        false,
                        false,
                        true,
                        PipelineManager.getOrCreatePipelineCache()
                );
                long p = VulkanPipelineFactory.createGraphicsPipeline(VulkanBackendAdapter.getDevice(), params);
                StageDiagnostics.pass("Graphics pipeline", "0x" + Long.toHexString(p)
                        + " for SHADER_TEST render pass 0x" + Long.toHexString(passId));
                return p;
            });
            if (pipeline == 0L) return;

            drawQuad(cmdBuffer, renderer, pipeline, testPipelineLayout, testDescriptorSet);

            diskDrawCount++;
            if (!diskPassed) {
                diskPassed = true;
                StageDiagnostics.pass("Command recording",
                        "SHADER_TEST vkCmdBindPipeline + viewport/scissor + descriptor set + vkCmdDraw(4)");
                StageDiagnostics.pass("Draw submitted", "SHADER_TEST quad via reloadable shaders "
                        + "(vert=0x" + Long.toHexString(diskVert.getVkShaderModule())
                        + ", frag=0x" + Long.toHexString(diskFrag.getVkShaderModule()) + ")");
            }
        } catch (NullPointerException ignored) {
            // Active render pass not bound yet; retry next frame.
        } catch (RuntimeException t) {
            StageDiagnostics.fail("Command recording", "SHADER_TEST: " + t.getMessage(), t);
        }
    }

    /**
     * Staged diagnostic visualizers (plan sections 6 and 9). Draws a full-screen-equivalent quad
     * through the shared controlled pipeline using a mode-specific built-in fragment shader, gated
     * by the exact same world-only check as the other controlled modes.
     *
     * <p><b>Attribute limitation:</b> the controlled path only supplies
     * {@code DefaultVertexFormat.POSITION_COLOR} (position + colour). There is no UV, normal,
     * lightmap or depth input to read. The visualizers therefore derive what they can from real
     * data instead of fabricating it:</p>
     * <ul>
     *     <li>{@code FLAT} — constant colour.</li>
     *     <li>{@code UV} / {@code LIGHTMAP} — {@code uv = Position.xy + 0.5}, a real function of
     *     the quad's geometry, not a real texture/lightmap coordinate.</li>
     *     <li>{@code NORMAL} — the test geometry has no normal attribute, so the shader uses the
     *     fixed surface normal {@code (0,0,1)}; this renders a flat {@code (0.5,0.5,1.0)} and only
     *     proves the normal-visualizer path executes, not per-vertex normals.</li>
     *     <li>{@code DEPTH} — real {@code gl_FragCoord.z} in grayscale (the quad lies at z=0, so
     *     this is expected to be black until the vertex depth is varied).</li>
     *     <li>{@code NO_POST} — passes the interpolated vertex colour through unchanged.</li>
     * </ul>
     */
    public static void executeVisualizerDraw(DebugRenderMode mode, VkCommandBuffer cmdBuffer) {
        if (!isVisualizerMode(mode)) return;
        if (!initTestPipeline()) return;

        try {
            Renderer renderer = Renderer.getInstance();
            if (cmdBuffer == null) cmdBuffer = Renderer.getCommandBuffer();
            if (renderer == null || cmdBuffer == null) return;

            if (!isWorldPassActive(renderer)) {
                logSkipped(mode);
                return;
            }

            RenderPass boundPass = renderer.getBoundRenderPass();
            if (boundPass == null || boundPass.getId() == 0L) return;

            long pipeline = getOrCreateVisualizerPipeline(mode, boundPass.getId());
            if (pipeline == 0L) return;

            drawQuad(cmdBuffer, renderer, pipeline, testPipelineLayout, testDescriptorSet);
        } catch (NullPointerException ignored) {
            // Active render pass not bound yet; retry next frame.
        } catch (RuntimeException t) {
            StageDiagnostics.fail("Visualizer " + mode, t.getMessage(), t);
        }
    }

    private static boolean isVisualizerMode(DebugRenderMode mode) {
        return mode == DebugRenderMode.FLAT
                || mode == DebugRenderMode.UV
                || mode == DebugRenderMode.LIGHTMAP
                || mode == DebugRenderMode.DEPTH
                || mode == DebugRenderMode.NO_POST;
    }

    private static synchronized boolean ensureVisualizerShader() {
        if (visualizerVert != null && visualizerVert.getVkShaderModule() != 0L) return true;

        visualizerVert = SpirvCompiler.compileShader("visualizer_vert", VISUALIZER_VERT, ShaderType.VERTEX);
        if (visualizerVert == null || visualizerVert.getVkShaderModule() == 0L) {
            StageDiagnostics.fail("Visualizer shader", "vertex stage produced no module", null);
            visualizerVert = null;
            return false;
        }
        return true;
    }

    private static VulkairisShader getVisualizerFragment(DebugRenderMode mode) {
        return VISUALIZER_FRAGS.computeIfAbsent(mode, m -> {
            VulkairisShader shader = SpirvCompiler.compileShader(
                    "visualizer_" + m.name().toLowerCase(Locale.ROOT), visualizerFragSource(m), ShaderType.FRAGMENT);
            if (shader == null || shader.getVkShaderModule() == 0L) {
                LOGGER.warn("[Vulkairis/Test] Visualizer fragment shader for {} produced no module.", m);
            }
            return shader;
        });
    }

    private static long getOrCreateVisualizerPipeline(DebugRenderMode mode, long renderPassId) {
        if (!ensureVisualizerShader()) return 0L;

        VulkairisShader frag = getVisualizerFragment(mode);
        if (frag == null || frag.getVkShaderModule() == 0L) return 0L;

        String key = mode.name() + "@" + Long.toHexString(renderPassId);
        return VISUALIZER_PIPELINES.computeIfAbsent(key, ignored -> {
            VulkanPipelineFactory.registerShaderPair(visualizerVert, frag);
            VulkanPipelineFactory.PipelineParams params = new VulkanPipelineFactory.PipelineParams(
                    visualizerVert.getVkShaderModule(),
                    frag.getVkShaderModule(),
                    testPipelineLayout,
                    renderPassId,
                    DefaultVertexFormat.POSITION_COLOR,
                    false,
                    false,
                    false,
                    true,
                    PipelineManager.getOrCreatePipelineCache()
            );
            long pipeline = VulkanPipelineFactory.createGraphicsPipeline(VulkanBackendAdapter.getDevice(), params);
            StageDiagnostics.pass("Visualizer pipeline", mode + " 0x" + Long.toHexString(pipeline)
                    + " for render pass 0x" + Long.toHexString(renderPassId));
            return pipeline;
        });
    }

    private static String visualizerFragSource(DebugRenderMode mode) {
        return switch (mode) {
            case FLAT -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = vec4(0.10, 0.85, 0.35, 1.0);
                    }
                    """;
            case UV -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = vec4(fract(vUv), 0.0, 1.0);
                    }
                    """;
            case LIGHTMAP -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = vec4(vUv, 0.0, 1.0);
                    }
                    """;
            case NORMAL -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        // POSITION_COLOR carries no normal attribute; use a fixed surface normal.
                        vec3 n = normalize(vec3(0.0, 0.0, 1.0));
                        fragColor = vec4(n * 0.5 + 0.5, 1.0);
                    }
                    """;
            case DEPTH -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        float d = gl_FragCoord.z;
                        fragColor = vec4(vec3(d), 1.0);
                    }
                    """;
            case NO_POST -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = vertexColor;
                    }
                    """;
            default -> """
                    #version 450 core
                    layout (location = 0) in vec4 vertexColor;
                    layout (location = 1) in vec2 vUv;
                    layout (location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = vec4(1.0, 0.0, 1.0, 1.0);
                    }
                    """;
        };
    }

    private static boolean ensureDiskShaders() {
        try {
            Files.createDirectories(SHADER_DIR);
            if (!Files.exists(DISK_VERT)) {
                Files.writeString(DISK_VERT, DISK_VERT_DEFAULT);
                LOGGER.info("[Vulkairis/Test] Wrote default debug vertex shader to {}", DISK_VERT);
            }
            if (!Files.exists(DISK_FRAG)) {
                Files.writeString(DISK_FRAG, DISK_FRAG_DEFAULT);
                LOGGER.info("[Vulkairis/Test] Wrote default debug fragment shader to {}", DISK_FRAG);
            }

            long modified = Math.max(Files.getLastModifiedTime(DISK_VERT).toMillis(),
                    Files.getLastModifiedTime(DISK_FRAG).toMillis());

            if (modified != diskLastModified || !diskReady) {
                return recompileDiskShaders(modified);
            }
            return diskReady;
        } catch (RuntimeException | java.io.IOException t) {
            StageDiagnostics.fail("SHADER_TEST file access", t.getMessage(), t);
            return false;
        }
    }

    private static synchronized boolean recompileDiskShaders(long modified) {
        try {
            String vertSource = Files.readString(DISK_VERT);
            String fragSource = Files.readString(DISK_FRAG);

            VulkairisShader newVert = SpirvCompiler.compileShader("debug_shader.vert", vertSource, ShaderType.VERTEX);
            VulkairisShader newFrag = SpirvCompiler.compileShader("debug_shader.frag", fragSource, ShaderType.FRAGMENT);

            if (newVert == null || newFrag == null
                    || newVert.getVkShaderModule() == 0L || newFrag.getVkShaderModule() == 0L) {
                StageDiagnostics.fail("SHADER_TEST compilation", "one or both stages produced no module", null);
                return false;
            }

            // Release the previous generation only after the new one is fully valid.
            for (long p : SHADER_TEST_PIPELINES.values()) {
                VulkanPipelineFactory.destroyPipeline(VulkanBackendAdapter.getDevice(), p);
            }
            SHADER_TEST_PIPELINES.clear();
            if (diskVert != null) diskVert.destroy();
            if (diskFrag != null) diskFrag.destroy();

            diskVert = newVert;
            diskFrag = newFrag;
            diskLastModified = modified;
            diskReady = true;
            diskDrawCount = 0;
            diskPassed = false;
            StageDiagnostics.pass("SHADER_TEST reload", "recompiled from " + SHADER_DIR);
            return true;
        } catch (RuntimeException | java.io.IOException t) {
            StageDiagnostics.fail("SHADER_TEST compilation", t.getMessage(), t);
            return false;
        }
    }

    /** Shared draw path used by both controlled modes. */
    private static void drawQuad(VkCommandBuffer cmdBuffer, Renderer renderer, long pipeline,
                                 long pipelineLayout, long descriptorSet) {
        VK10.vkCmdBindPipeline(cmdBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

        Framebuffer fb = renderer.getBoundFramebuffer();
        if (fb != null) {
            int w = fb.getWidth();
            int h = fb.getHeight();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkViewport.Buffer vp = VkViewport.calloc(1, stack)
                        .x(0.0f).y(0.0f).width((float) w).height((float) h)
                        .minDepth(0.0f).maxDepth(1.0f);
                VK10.vkCmdSetViewport(cmdBuffer, 0, vp);

                VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
                sc.offset().set(0, 0);
                sc.extent().set(w, h);
                VK10.vkCmdSetScissor(cmdBuffer, 0, sc);
            }
            // Record the narrowed extent so the GUI can be restored to a known full-window state.
            lastViewportWidth = w;
            lastViewportHeight = h;
        }

        if (descriptorSet != 0L && pipelineLayout != 0L) {
            VulkanDescriptorManager.bindDescriptorSets(cmdBuffer, pipelineLayout, descriptorSet);
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(4 * 16);
        buffer.putFloat(-0.5f).putFloat(-0.5f).putFloat(0.0f).put((byte) 0).put((byte) 255).put((byte) 255).put((byte) 255);
        buffer.putFloat(-0.5f).putFloat(0.5f).putFloat(0.0f).put((byte) 255).put((byte) 0).put((byte) 255).put((byte) 255);
        buffer.putFloat(0.5f).putFloat(0.5f).putFloat(0.0f).put((byte) 255).put((byte) 255).put((byte) 0).put((byte) 255);
        buffer.putFloat(0.5f).putFloat(-0.5f).putFloat(0.0f).put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);
        buffer.flip();

        Renderer.getDrawer().draw(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR, 4);
    }

    /**
     * Restores the dynamic viewport and scissor to a full-window extent.
     *
     * <p>The controlled path is the only place that narrows viewport/scissor, and Vulkan dynamic
     * state is sticky across command buffers and render passes. Without this reset the debug
     * quad's viewport can bleed into menu/GUI geometry recorded later. Best-effort: null command
     * buffers, non-positive extents and driver errors are swallowed rather than allowed to break
     * the frame.</p>
     *
     * @param cmdBuffer command buffer to record into, or {@code null} to use the current one
     * @param width     target framebuffer width in pixels
     * @param height    target framebuffer height in pixels
     */
    public static void resetViewportScissor(VkCommandBuffer cmdBuffer, int width, int height) {
        VkCommandBuffer cmd = cmdBuffer != null ? cmdBuffer : Renderer.getCommandBuffer();
        if (cmd == null || width <= 0 || height <= 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y(0.0f).width((float) width).height((float) height)
                    .minDepth(0.0f).maxDepth(1.0f);
            VK10.vkCmdSetViewport(cmd, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            VK10.vkCmdSetScissor(cmd, 0, scissor);
        } catch (Throwable ignored) {
            // Resetting dynamic state is best-effort; never let it crash the frame.
        }

        lastViewportWidth = width;
        lastViewportHeight = height;
    }

    /** Width recorded by the most recent controlled draw (0 if none yet). */
    public static int getLastViewportWidth() {
        return lastViewportWidth;
    }

    /** Height recorded by the most recent controlled draw (0 if none yet). */
    public static int getLastViewportHeight() {
        return lastViewportHeight;
    }

    public static void resetDrawCount() {
        drawCount = 0;
        diskDrawCount = 0;
    }

    public static boolean isTestPassed() {
        return testPassed;
    }

    /**
     * Phase 6: destroys every cached raw pipeline and drops the per-pass maps without touching
     * layouts or shaders, so resize/reload callers can never bind a pipeline created against a
     * destroyed render pass. Pipelines are recreated lazily against the live pass on the next
     * controlled draw. Safe to call repeatedly and with a dead device (destruction is skipped,
     * entries still dropped).
     */
    public static synchronized void invalidatePipelines() {
        // Lifecycle boundary (never per-frame): wait until in-flight command buffers no longer
        // reference the cached pipelines before destroying them.
        try {
            VulkanBackendAdapter.waitIdle();
        } catch (Throwable ignored) {
        }
        VkDevice device = null;
        try {
            device = VulkanBackendAdapter.getDevice();
        } catch (Throwable ignored) {
        }
        for (long pipe : PIPELINES_BY_RENDER_PASS.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        PIPELINES_BY_RENDER_PASS.clear();

        for (long pipe : SHADER_TEST_PIPELINES.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        SHADER_TEST_PIPELINES.clear();

        for (long pipe : VISUALIZER_PIPELINES.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        VISUALIZER_PIPELINES.clear();
        LOGGER.info("[Vulkairis/Test] Invalidated cached controlled pipelines.");
    }

    public static synchronized void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();

        for (long pipe : PIPELINES_BY_RENDER_PASS.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        PIPELINES_BY_RENDER_PASS.clear();

        for (long pipe : SHADER_TEST_PIPELINES.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        SHADER_TEST_PIPELINES.clear();

        for (long pipe : VISUALIZER_PIPELINES.values()) {
            VulkanPipelineFactory.destroyPipeline(device, pipe);
        }
        VISUALIZER_PIPELINES.clear();

        if (testPipelineLayout != 0L && VulkanBackendAdapter.isLive(device)) {
            try {
                VK10.vkDestroyPipelineLayout(device, testPipelineLayout, null);
            } catch (Throwable ignored) {
            }
            testPipelineLayout = 0L;
        }

        if (vertShader != null) {
            vertShader.destroy(device);
            vertShader = null;
        }
        if (fragShader != null) {
            fragShader.destroy(device);
            fragShader = null;
        }
        if (diskVert != null) {
            diskVert.destroy(device);
            diskVert = null;
        }
        if (diskFrag != null) {
            diskFrag.destroy(device);
            diskFrag = null;
        }
        if (visualizerVert != null) {
            visualizerVert.destroy(device);
            visualizerVert = null;
        }
        for (VulkairisShader shader : VISUALIZER_FRAGS.values()) {
            if (shader != null) {
                shader.destroy(device);
            }
        }
        VISUALIZER_FRAGS.clear();

        initialized = false;
        testPassed = false;
        drawCount = 0;
        diskReady = false;
        diskLastModified = -1L;
        diskDrawCount = 0;
        diskPassed = false;
        lastViewportWidth = 0;
        lastViewportHeight = 0;
    }
}
