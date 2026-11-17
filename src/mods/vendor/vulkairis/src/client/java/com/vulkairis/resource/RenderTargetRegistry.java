package com.vulkairis.resource;

import com.vulkairis.rendergraph.RenderResource;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named registry of render targets for the active shader pack.
 *
 * <p>Provides the standard target set a shader pack needs (scene color, depth, shadow depth, shadow
 * color, ping-pong intermediate, final output) and owns their resize lifecycle so a window resize or
 * dimension change recreates every dependent target.</p>
 */
public final class RenderTargetRegistry {

    /** Standard target names, aligned with the Iris sampler names shader packs reference. */
    public static final String SCENE_COLOR = "colortex0";
    public static final String SCENE_DEPTH = "depthtex0";
    public static final String SHADOW_DEPTH = "shadowtex0";
    public static final String SHADOW_COLOR = "shadowcolor0";
    public static final String PING = "composite_ping";
    public static final String PONG = "composite_pong";
    public static final String FINAL = "final_output";

    private final Map<String, TextureResource> targets = new LinkedHashMap<>();

    /** Registers (or replaces) a target by name. */
    public TextureResource register(TextureResource resource) {
        targets.put(resource.getSpec().resource().name(), resource);
        return resource;
    }

    /**
     * Returns the existing target for {@code spec}'s name, or creates and registers one.
     *
     * @param spec the desired target spec
     * @return the existing or newly registered target
     */
    public TextureResource getOrCreate(TextureSpec spec) {
        TextureResource existing = targets.get(spec.resource().name());
        if (existing != null) {
            return existing;
        }
        return register(new TextureResource(spec));
    }

    /** @return the target with the given name, if present */
    public Optional<TextureResource> get(String name) {
        return Optional.ofNullable(targets.get(name));
    }

    /** @return all registered targets, in registration order */
    public Collection<TextureResource> all() {
        return Collections.unmodifiableCollection(targets.values());
    }

    public int size() {
        return targets.size();
    }

    /**
     * Resizes every registered target to the given dimensions.
     *
     * @param width  new width
     * @param height new height
     */
    public void resizeAll(int width, int height) {
        resizeAllExcept(Set.of(), width, height);
    }

    /**
     * Resizes every target except those named in {@code fixed} (e.g. fixed-resolution shadow maps).
     *
     * @param fixed  target names to leave untouched
     * @param width  new width
     * @param height new height
     */
    public void resizeAllExcept(Set<String> fixed, int width, int height) {
        for (Map.Entry<String, TextureResource> entry : targets.entrySet()) {
            if (!fixed.contains(entry.getKey())) {
                entry.getValue().resize(width, height);
            }
        }
    }

    /** Destroys every target's backing Vulkan objects. */
    public void destroyAll() {
        for (TextureResource resource : targets.values()) {
            resource.destroy();
        }
    }

    /**
     * Builds the standard target set for a shader pack at the given screen resolution.
     *
     * @param width          screen width
     * @param height         screen height
     * @param shadowResolution shadow map resolution (square)
     * @return a registry populated with the standard targets
     */
    public static RenderTargetRegistry standard(int width, int height, int shadowResolution) {
        RenderTargetRegistry registry = new RenderTargetRegistry();

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.color(SCENE_COLOR), RenderTargetFormat.RGBA16F,
                width, height, SamplerPolicy.LINEAR_CLAMP)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.depth(SCENE_DEPTH), RenderTargetFormat.DEPTH32F,
                width, height, SamplerPolicy.NEAREST_CLAMP)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.depth(SHADOW_DEPTH), RenderTargetFormat.DEPTH32F,
                shadowResolution, shadowResolution, SamplerPolicy.SHADOW_COMPARE)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.color(SHADOW_COLOR), RenderTargetFormat.RGBA8,
                shadowResolution, shadowResolution, SamplerPolicy.NEAREST_CLAMP)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.transientColor(PING), RenderTargetFormat.RGBA16F,
                width, height, SamplerPolicy.LINEAR_CLAMP)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.transientColor(PONG), RenderTargetFormat.RGBA16F,
                width, height, SamplerPolicy.LINEAR_CLAMP)));

        registry.register(new TextureResource(TextureSpec.of(
                RenderResource.color(FINAL), RenderTargetFormat.RGBA8,
                width, height, SamplerPolicy.NEAREST_CLAMP)));

        return registry;
    }

    /** @return the names of every registered target, in registration order */
    public List<String> names() {
        return List.copyOf(targets.keySet());
    }

    /** @return the current width of the primary scene target, or 0 if empty */
    public int getWidth() {
        return get(SCENE_COLOR).map(r -> r.getSpec().width()).orElse(0);
    }

    /** @return the current height of the primary scene target, or 0 if empty */
    public int getHeight() {
        return get(SCENE_COLOR).map(r -> r.getSpec().height()).orElse(0);
    }
}
