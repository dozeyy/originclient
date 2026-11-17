package com.vulkairis.origin.composite;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Origin (step 2): runs the pack's deferred + composite + final chain once per frame, right after
 * the world render, through VulkanMod's own pipeline/render-pass machinery.
 *
 * <p>Order per frame: end the main pass, copy the world image into the scene buffer (VulkanMod's
 * swapchain images are SAMPLED but not TRANSFER_SRC, so the copy is a draw), run every pass into its
 * colortex MAIN/ALT image, then draw {@code final} (or copy colortex0) back into the main target and
 * leave the main pass bound for the GUI.</p>
 *
 * <p>VulkanMod pipelines have one colour attachment, so a pass with N draw buffers draws N times:
 * output 0 with the linked pipeline (extra outputs have no attachment and are dropped), output k&gt;0
 * with a one-output variant ({@link VulkanModProgramBridge#outputVariant}).</p>
 *
 * <p>Fail-soft: any error switches the runner off for the session and the vanilla world stays on
 * screen.</p>
 */
public final class CompositeRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Composite");

    private static final int FORMAT_RGBA16F = 97;              // VK_FORMAT_R16G16B16A16_SFLOAT
    private static final int USAGE = 0x1 | 0x2 | 0x4 | 0x10;    // TRANSFER_SRC|TRANSFER_DST|SAMPLED|COLOR_ATTACHMENT
    private static final int LAYOUT_COLOR_ATTACHMENT = 2;       // VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    private static final int LOAD_OP_LOAD = 0;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int MAX_SAMPLERS = 12;                 // VTextureSelector.SIZE

    private static final String COPY_VERT = """
            #version 450
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 0) out vec2 uv;
            void main() {
                gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
                uv = UV0;
            }
            """;
    private static final String COPY_FRAG = """
            #version 450
            layout(binding = 0) uniform sampler2D Sampler0;
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(Sampler0, uv);
            }
            """;

    private static boolean running;
    private static boolean disabled;
    private static boolean announced;
    private static final Set<String> WARNED = new HashSet<>();

    private static ProgramSet cachedSet;
    private static IrisPassSource.Chain chain;
    private static CompositeRouting.Plan plan;

    private static int width;
    private static int height;
    /** [buffer][side]: lazily created RGBA16F images. */
    private static final VulkanImage[][] IMAGES = new VulkanImage[CompositeRouting.MAX_BUFFERS][2];
    private static final Map<VulkanImage, Target> TARGETS = new IdentityHashMap<>();
    private static VulkanImage white;
    private static GraphicsPipeline copyPipeline;
    private static boolean copyFailed;
    private static ByteBuffer quad;

    private record Target(Framebuffer framebuffer, RenderPass renderPass) {
    }

    private CompositeRunner() {
    }

    /** Called once per frame after {@code GameRenderer.renderLevel}. Never throws. */
    public static void afterWorld() {
        if (running || disabled) {
            return;
        }
        running = true;
        try {
            run();
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error("[Vulkairis/Composite] post chain disabled for this session: {}", t.toString(), t);
        } finally {
            running = false;
        }
    }

    private static void run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        ProgramSet set = IrisPassSource.currentSet();
        if (set == null) {
            return;
        }
        if (set != cachedSet) {
            cachedSet = set;
            chain = IrisPassSource.chainOf(set);
            plan = chain == null ? null : CompositeRouting.plan(chain.passes());
            announced = false;
        }
        if (chain == null || plan == null) {
            return;
        }

        Renderer renderer = Renderer.getInstance();
        MainPass mainPass = renderer.getMainPass();
        Framebuffer mainFb = mainPass.getMainFramebuffer();
        VulkanImage mainColor = mainPass.getColorAttachment().getVulkanImage();
        if (mainFb == null || mainColor == null) {
            return;
        }
        ensureSize(mainFb.getWidth(), mainFb.getHeight());
        if (!ensureCopyPipeline()) {
            return;
        }

        VkCommandBuffer cmd = Renderer.getCommandBuffer();
        SavedState saved = SavedState.capture();
        int draws = 0;
        try {
            if (renderer.getBoundRenderPass() != null) {
                renderer.endRenderPass(cmd);
            }
            clearMainSides(cmd, renderer);

            // The world image stands in for the gbuffers' scene output (until step 5).
            VulkanImage scene = image(chain.sceneBuffer(), CompositeRouting.Side.MAIN);
            readOnly(cmd, mainColor);
            beginTarget(cmd, renderer, scene);
            drawCopy(renderer, mainColor);
            renderer.endRenderPass(cmd);
            draws++;

            for (CompositeRouting.PassRoute route : plan.passes()) {
                VulkairisProgram program = VulkanShaderRegistry.findProgramByName(route.name());
                GraphicsPipeline base = program == null ? null
                        : VulkanModProgramBridge.getLinkedPipeline(program.getVirtualProgramId());
                if (base == null) {
                    warnOnce(route.name(), "not linked; pass skipped");
                    continue;
                }
                int[] outs = route.drawBuffers();
                for (int i = 0; i < outs.length; i++) {
                    GraphicsPipeline gp = i == 0 ? base : VulkanModProgramBridge.outputVariant(program, i);
                    if (gp == null) {
                        warnOnce(route.name() + "#" + i, "output variant unavailable; output skipped");
                        continue;
                    }
                    VulkanImage target = image(outs[i], route.writeSides()[i]);
                    prepareReads(cmd, gp, route.readSides());
                    beginTarget(cmd, renderer, target);
                    drawPass(renderer, gp, route.readSides());
                    renderer.endRenderPass(cmd);
                    draws++;
                }
            }

            // Back to the main target for final (or a plain colortex0 copy) and then the GUI.
            CompositeRouting.Side[] finalSides = plan.finalReadSides();
            GraphicsPipeline finalGp = null;
            if (chain.finalName() != null) {
                VulkairisProgram program = VulkanShaderRegistry.findProgramByName(chain.finalName());
                finalGp = program == null ? null
                        : VulkanModProgramBridge.getLinkedPipeline(program.getVirtualProgramId());
                if (finalGp == null) {
                    warnOnce(chain.finalName(), "not linked; colortex0 copied instead");
                }
            }
            if (finalGp != null) {
                prepareReads(cmd, finalGp, finalSides);
            } else {
                readOnly(cmd, image(0, finalSides[0]));
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                mainColor.transitionImageLayout(stack, cmd, LAYOUT_COLOR_ATTACHMENT);
            }
            mainPass.rebindMainTarget();
            if (renderer.getBoundRenderPass() == null) {
                throw new IllegalStateException("main pass did not rebind");
            }
            fullViewport();
            if (finalGp != null) {
                drawPass(renderer, finalGp, finalSides);
            } else {
                drawCopy(renderer, image(0, finalSides[0]));
            }
            draws++;
        } finally {
            saved.restore();
        }

        if (!announced) {
            announced = true;
            LOGGER.info("[Vulkairis/Composite] post chain ran: {} pass(es), {} draw(s), scene buffer colortex{}, final={}, {}x{}",
                    plan.passes().size(), draws, chain.sceneBuffer(),
                    chain.finalName() == null ? "(colortex0 copy)" : chain.finalName(), width, height);
        }
    }

    // ---------------------------------------------------------------- targets

    private static void ensureSize(int w, int h) {
        if (w == width && h == height) {
            return;
        }
        freeImages();
        width = w;
        height = h;
    }

    private static VulkanImage image(int buffer, CompositeRouting.Side side) {
        int s = side.ordinal();
        VulkanImage img = IMAGES[buffer][s];
        if (img == null) {
            img = VulkanImage.createTextureImage(VulkanImage.builder(width, height)
                    .setName("vulkairis colortex" + buffer + (s == 0 ? "" : " alt"))
                    .setFormat(FORMAT_RGBA16F)
                    .setUsage(USAGE)
                    .setLinearFiltering(true)
                    .setClamp(true));
            IMAGES[buffer][s] = img;
        }
        return img;
    }

    private static Target target(VulkanImage image) {
        Target t = TARGETS.get(image);
        if (t == null) {
            Framebuffer fb = Framebuffer.builder(image, null).build();
            RenderPass rp = RenderPass.builder(fb).setLoadOp(LOAD_OP_LOAD).build();
            t = new Target(fb, rp);
            TARGETS.put(image, t);
        }
        return t;
    }

    private static void beginTarget(VkCommandBuffer cmd, Renderer renderer, VulkanImage image) {
        Target t = target(image);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionImageLayout(stack, cmd, LAYOUT_COLOR_ATTACHMENT);
        }
        if (!renderer.beginRenderPass(t.renderPass(), t.framebuffer())) {
            throw new IllegalStateException("could not begin a render pass on " + image);
        }
        fullViewport();
    }

    /** Iris clears every buffer at frame start: colortex1 (gdepth) to white, the rest to zero. */
    private static void clearMainSides(VkCommandBuffer cmd, Renderer renderer) {
        float[] old = new float[4];
        for (int i = 0; i < 4; i++) {
            old[i] = VRenderSystem.clearColor.get(i);
        }
        try {
            for (int b = 0; b < CompositeRouting.MAX_BUFFERS; b++) {
                VulkanImage img = IMAGES[b][0];
                if (img == null || b == chain.sceneBuffer()) {
                    continue;
                }
                float v = b == 1 ? 1f : 0f;
                VRenderSystem.setClearColor(v, v, v, v);
                beginTarget(cmd, renderer, img);
                Renderer.clearAttachments(cmd, GL_COLOR_BUFFER_BIT);
                renderer.endRenderPass(cmd);
            }
        } finally {
            VRenderSystem.setClearColor(old[0], old[1], old[2], old[3]);
        }
    }

    private static void fullViewport() {
        Renderer.setViewport(0, 0, width, height);
        Renderer.setScissor(0, 0, width, height);
    }

    private static void readOnly(VkCommandBuffer cmd, VulkanImage image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.readOnlyLayout(stack, cmd);
        }
    }

    // ---------------------------------------------------------------- draws

    /** Every image {@code gp} samples must be shader-readable before its render pass begins. */
    private static void prepareReads(VkCommandBuffer cmd, GraphicsPipeline gp, CompositeRouting.Side[] sides) {
        for (ImageDescriptor d : gp.getImageDescriptors()) {
            VulkanImage img = sampled(d.name, sides);
            if (img != null) {
                readOnly(cmd, img);
            }
        }
    }

    private static VulkanImage sampled(String name, CompositeRouting.Side[] sides) {
        SamplerNames.Target t = SamplerNames.resolve(name);
        if (t.kind() == SamplerNames.Kind.COLORTEX) {
            return image(t.index(), sides[t.index()]);
        }
        // Depth copies and custom textures are step 4; white keeps "everything is far/sky" sane.
        return whiteTexture();
    }

    private static void drawPass(Renderer renderer, GraphicsPipeline gp, CompositeRouting.Side[] sides) {
        applyPostState();
        renderer.bindGraphicsPipeline(gp);
        for (ImageDescriptor d : gp.getImageDescriptors()) {
            int idx = d.getImageIdx();
            if (idx < 0 || idx >= MAX_SAMPLERS) {
                warnOnce("sampler:" + d.name, "image index " + idx + " beyond VulkanMod's " + MAX_SAMPLERS + " slots");
                continue;
            }
            VTextureSelector.bindTexture(idx, sampled(d.name, sides));
        }
        VulkanModProgramBridge.uploadUniforms(gp);
        renderer.uploadAndBindUBOs(gp);
        drawQuad(VulkanModProgramBridge.formatOf(gp));
    }

    private static void drawCopy(Renderer renderer, VulkanImage source) {
        applyPostState();
        renderer.bindGraphicsPipeline(copyPipeline);
        VTextureSelector.bindTexture(0, source);
        renderer.uploadAndBindUBOs(copyPipeline);
        drawQuad(DefaultVertexFormat.POSITION_TEX);
    }

    private static void applyPostState() {
        VRenderSystem.disableBlend();
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.disableCull();
        VRenderSystem.colorMask(true, true, true, true);
    }

    /**
     * A full-screen quad in {@code format}: positions 0..1 (Iris's composite ortho maps them to clip
     * space), UV0 with v flipped. VulkanMod's viewport is GL-style (negative height), so row 0 of every
     * image is the top of the screen; v = 1 - y keeps texcoord and gl_FragCoord on the same rows and
     * stops each pass flipping the image.
     */
    private static void drawQuad(VertexFormat format) {
        if (format == null) {
            format = DefaultVertexFormat.POSITION_TEX;
        }
        int stride = format.getVertexSize();
        int bytes = stride * 4;
        if (quad == null || quad.capacity() < bytes) {
            if (quad != null) {
                MemoryUtil.memFree(quad);
            }
            quad = MemoryUtil.memAlloc(Math.max(bytes, 256));
        }
        ByteBuffer buf = quad;
        MemoryUtil.memSet(MemoryUtil.memAddress(buf), 0, bytes);
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        List<VertexFormatElement> elements = format.getElements();
        for (int v = 0; v < 4; v++) {
            int base = v * stride;
            float x = corners[v][0];
            float y = corners[v][1];
            for (VertexFormatElement e : elements) {
                int at = base + format.getOffset(e);
                switch (e.usage()) {
                    case POSITION -> buf.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, 0f);
                    case UV -> {
                        if (e == VertexFormatElement.UV0 && e.type() == VertexFormatElement.Type.FLOAT) {
                            buf.putFloat(at, x).putFloat(at + 4, 1f - y);
                        }
                    }
                    case COLOR -> {
                        for (int c = 0; c < e.count(); c++) {
                            buf.put(at + c, (byte) 0xFF);
                        }
                    }
                    case NORMAL -> buf.put(at + 2, (byte) 127);
                    default -> {
                    }
                }
            }
        }
        buf.position(0).limit(bytes);
        Renderer.getDrawer().draw(buf, VertexFormat.Mode.QUADS, format, 4);
        buf.clear();
    }

    // ---------------------------------------------------------------- resources

    private static boolean ensureCopyPipeline() {
        if (copyPipeline != null) {
            return true;
        }
        if (copyFailed) {
            return false;
        }
        try {
            Pipeline.Builder builder = Pipeline.builder(DefaultVertexFormat.POSITION_TEX, "vulkairis_copy");
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, COPY_VERT);
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, COPY_FRAG);
            PipelineConfig.Builder config = PipelineConfig.builder();
            config.addImageDescriptor(0, "sampler2D", "Sampler0", 0);
            builder.applyConfig(config.build());
            copyPipeline = builder.createGraphicsPipeline();
            return true;
        } catch (Throwable t) {
            copyFailed = true;
            disabled = true;
            LOGGER.error("[Vulkairis/Composite] copy pipeline failed; post chain off: {}", t.toString(), t);
            return false;
        }
    }

    private static VulkanImage whiteTexture() {
        if (white == null) {
            white = VulkanImage.createWhiteTexture();
        }
        return white;
    }

    private static void freeImages() {
        for (Target t : TARGETS.values()) {
            try {
                t.renderPass().cleanUp();
                t.framebuffer().cleanUp(false);
            } catch (Throwable ignored) {
            }
        }
        TARGETS.clear();
        for (VulkanImage[] pair : IMAGES) {
            for (int s = 0; s < 2; s++) {
                if (pair[s] != null) {
                    try {
                        pair[s].free();
                    } catch (Throwable ignored) {
                    }
                    pair[s] = null;
                }
            }
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            LOGGER.warn("[Vulkairis/Composite] {}: {}", key, message);
        }
    }

    /** VulkanMod state the chain changes; the GUI that follows expects it untouched. */
    private record SavedState(boolean blend, boolean depthTest, boolean depthMask, boolean cull, int colorMask) {
        static SavedState capture() {
            return new SavedState(PipelineState.blendInfo.enabled, VRenderSystem.depthTest,
                    VRenderSystem.depthMask, VRenderSystem.cull, VRenderSystem.colorMask);
        }

        void restore() {
            PipelineState.blendInfo.enabled = blend;
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.cull = cull;
            VRenderSystem.colorMask = colorMask;
        }
    }
}
