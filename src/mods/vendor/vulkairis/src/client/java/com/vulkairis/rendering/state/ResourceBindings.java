package com.vulkairis.rendering.state;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the OpenGL-style resource bindings a shader pack relies on: texture units, image units,
 * sampler objects, framebuffer attachments, and MRT draw/read buffer selection.
 *
 * <p>Previously all of these were silently discarded, so a pack could "load" while sampling the
 * wrong (or no) resource. Recording them makes the bindings inspectable and lets the descriptor
 * layer (Phase 10) turn them into real descriptor writes.</p>
 */
public final class ResourceBindings {

    /** A texture bound to a texture unit. */
    public record TextureUnitBinding(int textureId, int target) {
    }

    /** A storage/sampled image bound to an image unit. */
    public record ImageUnitBinding(
            int textureId, int level, boolean layered, int layer, int access, int format) {
    }

    /** Parameters of a sampler object. */
    public record SamplerObject(
            int samplerId, int minFilter, int magFilter, int wrapS, int wrapT) {
        SamplerObject withMinFilter(int value) {
            return new SamplerObject(samplerId, value, magFilter, wrapS, wrapT);
        }

        SamplerObject withMagFilter(int value) {
            return new SamplerObject(samplerId, minFilter, value, wrapS, wrapT);
        }

        SamplerObject withWrapS(int value) {
            return new SamplerObject(samplerId, minFilter, magFilter, value, wrapT);
        }

        SamplerObject withWrapT(int value) {
            return new SamplerObject(samplerId, minFilter, magFilter, wrapS, value);
        }
    }

    /** A texture attached to a framebuffer attachment point. */
    public record FramebufferAttachment(int attachment, int textureId, int level) {
    }

    private final Map<Integer, TextureUnitBinding> textureUnits = new ConcurrentHashMap<>();
    private final Map<Integer, ImageUnitBinding> imageUnits = new ConcurrentHashMap<>();
    private final Map<Integer, SamplerObject> samplers = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> unitToSampler = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, FramebufferAttachment>> framebufferAttachments =
            new ConcurrentHashMap<>();
    private final Map<Integer, int[]> drawBuffers = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> readBuffers = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> bufferBaseIndex = new ConcurrentHashMap<>();
    private final Map<String, Integer> uniformBlockBindings = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> textureParameters = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Texture units
    // ------------------------------------------------------------------

    public void bindTextureToUnit(int unit, int textureId, int target) {
        textureUnits.put(unit, new TextureUnitBinding(textureId, target));
    }

    public void unbindTextureFromUnit(int unit) {
        textureUnits.remove(unit);
    }

    public Optional<TextureUnitBinding> getTextureUnit(int unit) {
        return Optional.ofNullable(textureUnits.get(unit));
    }

    // ------------------------------------------------------------------
    // Image units
    // ------------------------------------------------------------------

    public void bindImage(int unit, int textureId, int level, boolean layered, int layer,
                          int access, int format) {
        imageUnits.put(unit, new ImageUnitBinding(textureId, level, layered, layer, access, format));
    }

    public Optional<ImageUnitBinding> getImageUnit(int unit) {
        return Optional.ofNullable(imageUnits.get(unit));
    }

    // ------------------------------------------------------------------
    // Sampler objects
    // ------------------------------------------------------------------

    public void bindSamplerToUnit(int unit, int samplerId) {
        if (samplerId == 0) {
            unitToSampler.remove(unit);
        } else {
            unitToSampler.put(unit, samplerId);
        }
    }

    public void setSamplerParameters(int samplerId, int minFilter, int magFilter, int wrapS, int wrapT) {
        samplers.compute(samplerId, (id, existing) -> {
            int resolvedMin = minFilter != 0 ? minFilter : (existing != null ? existing.minFilter() : 0);
            int resolvedMag = magFilter != 0 ? magFilter : (existing != null ? existing.magFilter() : 0);
            int resolvedWrapS = wrapS != 0 ? wrapS : (existing != null ? existing.wrapS() : 0);
            int resolvedWrapT = wrapT != 0 ? wrapT : (existing != null ? existing.wrapT() : 0);
            return new SamplerObject(id, resolvedMin, resolvedMag, resolvedWrapS, resolvedWrapT);
        });
    }

    public Optional<SamplerObject> getSampler(int samplerId) {
        return Optional.ofNullable(samplers.get(samplerId));
    }

    public Optional<Integer> getSamplerForUnit(int unit) {
        return Optional.ofNullable(unitToSampler.get(unit));
    }

    // ------------------------------------------------------------------
    // Framebuffer attachments / MRT
    // ------------------------------------------------------------------

    public void attachFramebufferTexture(int framebuffer, int attachment, int textureId, int level) {
        framebufferAttachments
                .computeIfAbsent(framebuffer, ignored -> new ConcurrentHashMap<>())
                .put(attachment, new FramebufferAttachment(attachment, textureId, level));
    }

    public Optional<FramebufferAttachment> getAttachment(int framebuffer, int attachment) {
        Map<Integer, FramebufferAttachment> attachments = framebufferAttachments.get(framebuffer);
        return attachments == null ? Optional.empty() : Optional.ofNullable(attachments.get(attachment));
    }

    public Map<Integer, FramebufferAttachment> getAttachments(int framebuffer) {
        Map<Integer, FramebufferAttachment> attachments = framebufferAttachments.get(framebuffer);
        return attachments == null ? Collections.emptyMap() : Collections.unmodifiableMap(attachments);
    }

    /**
     * Whether the framebuffer has at least one attachment. Replaces an unconditional "complete"
     * answer with a real check so a framebuffer with no attachments is not reported usable.
     *
     * @param framebuffer the framebuffer id
     * @return whether any attachment exists
     */
    public boolean isFramebufferComplete(int framebuffer) {
        Map<Integer, FramebufferAttachment> attachments = framebufferAttachments.get(framebuffer);
        return attachments != null && !attachments.isEmpty();
    }

    public void setDrawBuffers(int framebuffer, int[] buffers) {
        drawBuffers.put(framebuffer, buffers == null ? new int[0] : buffers.clone());
    }

    public int[] getDrawBuffers(int framebuffer) {
        int[] buffers = drawBuffers.get(framebuffer);
        return buffers == null ? new int[0] : Arrays.copyOf(buffers, buffers.length);
    }

    public void setReadBuffer(int framebuffer, int mode) {
        readBuffers.put(framebuffer, mode);
    }

    public Optional<Integer> getReadBuffer(int framebuffer) {
        return Optional.ofNullable(readBuffers.get(framebuffer));
    }

    // ------------------------------------------------------------------
    // Buffers / uniform blocks
    // ------------------------------------------------------------------

    public void bindBufferBase(int index, int buffer) {
        bufferBaseIndex.put(index, buffer);
    }

    public Optional<Integer> getBufferBase(int index) {
        return Optional.ofNullable(bufferBaseIndex.get(index));
    }

    public void setUniformBlockBinding(int program, int blockIndex, int bufferIndex) {
        uniformBlockBindings.put(program + ":" + blockIndex, bufferIndex);
    }

    public Optional<Integer> getUniformBlockBinding(int program, int blockIndex) {
        return Optional.ofNullable(uniformBlockBindings.get(program + ":" + blockIndex));
    }

    // ------------------------------------------------------------------
    // Texture parameters
    // ------------------------------------------------------------------

    /**
     * Records a texture parameter (filter/wrap) for the subset shader packs actually use. Texture
     * objects are not created here; this is the tracking representation the resource layer consumes.
     */
    public void setTextureParameter(int texture, int pname, int param) {
        textureParameters
                .computeIfAbsent(texture, ignored -> new ConcurrentHashMap<>())
                .put(pname, param);
    }

    public Optional<Integer> getTextureParameter(int texture, int pname) {
        Map<Integer, Integer> parameters = textureParameters.get(texture);
        return parameters == null ? Optional.empty() : Optional.ofNullable(parameters.get(pname));
    }

    /** Clears per-frame binding state (attachments drawn this frame). */
    public void clear() {
        textureUnits.clear();
        imageUnits.clear();
        samplers.clear();
        unitToSampler.clear();
        framebufferAttachments.clear();
        drawBuffers.clear();
        readBuffers.clear();
        bufferBaseIndex.clear();
        uniformBlockBindings.clear();
    }
}
