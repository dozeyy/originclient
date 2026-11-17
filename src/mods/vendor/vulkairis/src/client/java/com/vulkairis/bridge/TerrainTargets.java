package com.vulkairis.bridge;

import com.vulkairis.rendering.FramebufferValidator;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.resource.TargetDescriptor;
import com.vulkairis.resource.TextureResource;
import com.vulkairis.resource.TextureSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D pack targets: colortex allocation/mip/resize plus shadow depth/shadowcolor.
 *
 * <p>Semantics reimplemented (not copied): pack {@code colortex0..N-1} are persistent
 * sampled color targets at screen resolution with mip chains; shadow depth
 * ({@code shadowtex0}) is a fixed-resolution depth target sampled with comparison, and
 * {@code shadowcolor0} is its fixed-resolution color companion. Depth clears use forward-Z
 * {@code 1.0} (see {@link #SHADOW_CLEAR_DEPTH}). Shadow draw is one frame late by design
 * (see {@link ShadowFrameLag}): this class owns allocation and validation only, never
 * same-framing.</p>
 */
public final class TerrainTargets {

    /** Forward-Z clear depth: the far plane in Vulkan {@code [0, 1]} NDC. */
    public static final float SHADOW_CLEAR_DEPTH = 1.0f;

    /** Maximum pack colortex index supported (colortex0..colortex15). */
    public static final int MAX_COLORTEX = 16;

    private TerrainTargets() {
    }

    /**
     * Creates a pack target registry: {@code colortexCount} screen-space color targets,
     * scene depth, fixed-resolution shadow depth + shadow color, and ping/pong + final.
     *
     * @param width screen width (&gt; 0)
     * @param height screen height (&gt; 0)
     * @param colortexCount pack colortex targets (1..{@value #MAX_COLORTEX})
     * @param shadowResolution square shadow map resolution (power of two, &gt; 0)
     * @return populated registry
     * @throws IllegalArgumentException for out-of-range inputs
     */
    public static RenderTargetRegistry create(int width, int height, int colortexCount,
                                              int shadowResolution) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "pack targets need positive screen size, got " + width + "x" + height);
        }
        if (colortexCount < 1 || colortexCount > MAX_COLORTEX) {
            throw new IllegalArgumentException(
                    "colortexCount must be 1.." + MAX_COLORTEX + ", got " + colortexCount);
        }
        if (shadowResolution < 1 || (shadowResolution & (shadowResolution - 1)) != 0) {
            throw new IllegalArgumentException(
                    "shadow resolution must be a positive power of two, got " + shadowResolution);
        }
        RenderTargetRegistry registry = new RenderTargetRegistry();
        for (int i = 0; i < colortexCount; i++) {
            String name = "colortex" + i;
            registry.register(new TextureResource(TextureSpec.of(
                    RenderResource.color(name), RenderTargetFormat.RGBA16F,
                    width, height, SamplerPolicy.LINEAR_CLAMP)));
        }
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.depth("depthtex0"), RenderTargetFormat.DEPTH32F,
                width, height, SamplerPolicy.NEAREST_CLAMP)));
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.depth("shadowtex0"), RenderTargetFormat.DEPTH32F,
                shadowResolution, shadowResolution, SamplerPolicy.SHADOW_COMPARE)));
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.color("shadowcolor0"), RenderTargetFormat.RGBA8,
                shadowResolution, shadowResolution, SamplerPolicy.NEAREST_CLAMP)));
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.transientColor("composite_ping"), RenderTargetFormat.RGBA16F,
                width, height, SamplerPolicy.LINEAR_CLAMP)));
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.transientColor("composite_pong"), RenderTargetFormat.RGBA16F,
                width, height, SamplerPolicy.LINEAR_CLAMP)));
        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.color("final_output"), RenderTargetFormat.RGBA8,
                width, height, SamplerPolicy.NEAREST_CLAMP)));
        return registry;
    }

    /** @return names of fixed-resolution targets that must not follow window resizes */
    public static Set<String> fixedTargets() {
        return Set.of("shadowtex0", "shadowcolor0");
    }

    /**
     * Resizes screen-space targets, leaving shadow maps at their fixed resolution.
     *
     * @param registry pack registry
     * @param width new width (&gt; 0)
     * @param height new height (&gt; 0)
     * @return whether any target changed size
     */
    public static boolean resize(RenderTargetRegistry registry, int width, int height) {
        if (registry == null || width <= 0 || height <= 0) {
            return false;
        }
        TextureResource anchor = registry.get("colortex0").orElse(null);
        if (anchor != null && anchor.getWidth() == width && anchor.getHeight() == height) {
            return false;
        }
        registry.resizeAllExcept(fixedTargets(), width, height);
        return true;
    }

    /**
     * Mip level count for a target of the given size (full chain down to 1x1).
     *
     * @param width target width (&gt; 0)
     * @param height target height (&gt; 0)
     * @return mip levels (&gt;= 1)
     */
    public static int mipLevelsFor(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "mip chain needs positive size, got " + width + "x" + height);
        }
        int levels = 1;
        int extent = Math.min(width, height);
        while (extent > 1) {
            extent >>= 1;
            levels++;
        }
        return levels;
    }

    /**
     * Target descriptors for the pack set at a base resolution: colortex color targets plus
     * shadow depth (forward-Z clear) and shadow color.
     *
     * @param colortexCount pack colortex targets (1..{@value #MAX_COLORTEX})
     * @return descriptors in allocation order
     */
    public static List<TargetDescriptor> descriptors(int colortexCount) {
        if (colortexCount < 1 || colortexCount > MAX_COLORTEX) {
            throw new IllegalArgumentException(
                    "colortexCount must be 1.." + MAX_COLORTEX + ", got " + colortexCount);
        }
        List<TargetDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < colortexCount; i++) {
            descriptors.add(TargetDescriptor.color("colortex" + i, RenderTargetFormat.RGBA16F));
        }
        descriptors.add(new TargetDescriptor("shadowtex0", 1.0f, 1.0f,
                RenderTargetFormat.DEPTH32F, 1, TargetDescriptor.Role.DEPTH,
                TargetDescriptor.LoadOp.CLEAR, TargetDescriptor.StoreOp.STORE,
                true, false, "shadow", null, SHADOW_CLEAR_DEPTH));
        descriptors.add(TargetDescriptor.color("shadowcolor0", RenderTargetFormat.RGBA8));
        return List.copyOf(descriptors);
    }

    /**
     * Device-free validation of the pack target set: format/dimension/binding/lifetime.
     *
     * <ul>
     *   <li>format: color targets use color formats, depth targets use depth formats;</li>
     *   <li>dimension: every spec is positive, shadow maps are square;</li>
     *   <li>binding: shadow depth uses comparison sampling;</li>
     *   <li>lifetime: freshly (re)created backings still need their first clear.</li>
     * </ul>
     *
     * @param registry pack registry (may be {@code null})
     * @param expectedWidth expected screen width for colortex targets
     * @param expectedHeight expected screen height for colortex targets
     * @param shadowResolution expected square shadow resolution
     * @return problems found; empty when the set is usable
     */
    public static List<String> validate(RenderTargetRegistry registry, int expectedWidth,
                                        int expectedHeight, int shadowResolution) {
        List<String> problems = new ArrayList<>();
        if (registry == null) {
            return List.of("pack targets missing: registry is null");
        }
        for (Map.Entry<String, TextureResource> entry : registryEntries(registry)) {
            String name = entry.getKey();
            TextureResource resource = entry.getValue();
            if (resource == null) {
                problems.add("target '" + name + "' has no resource");
                continue;
            }
            TextureSpec spec = resource.getSpec();
            if (spec.width() <= 0 || spec.height() <= 0) {
                problems.add("target '" + name + "' has non-positive dimensions "
                        + spec.width() + "x" + spec.height());
            }
            boolean shadow = name.equals("shadowtex0") || name.equals("shadowcolor0");
            if (!shadow && name.startsWith("colortex")) {
                if (spec.format().isDepth()) {
                    problems.add("target '" + name + "' is color but format "
                            + spec.format() + " is depth");
                }
                if (spec.width() != expectedWidth || spec.height() != expectedHeight) {
                    problems.add("target '" + name + "' is " + spec.width() + "x" + spec.height()
                            + ", expected " + expectedWidth + "x" + expectedHeight);
                }
            }
            if (name.equals("shadowtex0")) {
                if (!spec.format().isDepth()) {
                    problems.add("target 'shadowtex0' must use a depth format, got " + spec.format());
                }
                if (!spec.format().supportsCompareSampling()) {
                    problems.add("target 'shadowtex0' format " + spec.format()
                            + " cannot be comparison-sampled");
                }
                if (spec.sampler() != SamplerPolicy.SHADOW_COMPARE) {
                    problems.add("target 'shadowtex0' must use SHADOW_COMPARE sampling, got "
                            + spec.sampler());
                }
                if (spec.width() != shadowResolution || spec.height() != shadowResolution) {
                    problems.add("target 'shadowtex0' is " + spec.width() + "x" + spec.height()
                            + ", expected square " + shadowResolution);
                }
            }
            if (name.equals("shadowcolor0") && spec.format().isDepth()) {
                problems.add("target 'shadowcolor0' is color but format " + spec.format() + " is depth");
            }
            if (resource.needsClear()) {
                problems.add("target '" + name + "' needs its first clear before sampling"
                        + " (forward-Z depth clears to " + SHADOW_CLEAR_DEPTH + ")");
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Framebuffer-level validation for one pack target binding.
     *
     * @param framebuffer framebuffer id
     * @param attachments tracked attachments
     * @return the validator verdict
     */
    public static FramebufferValidator.Verdict validateFramebuffer(
            int framebuffer, Map<Integer, com.vulkairis.rendering.state.ResourceBindings.FramebufferAttachment> attachments) {
        return FramebufferValidator.validate(framebuffer, attachments);
    }

    private static Iterable<Map.Entry<String, TextureResource>> registryEntries(
            RenderTargetRegistry registry) {
        Map<String, TextureResource> ordered = new LinkedHashMap<>();
        for (TextureResource resource : registry.all()) {
            ordered.put(resource.getSpec().resource().name(), resource);
        }
        return ordered.entrySet();
    }
}
