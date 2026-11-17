package com.vulkairis.resource;

import com.vulkairis.rendergraph.RenderResource;

import java.util.Objects;

/**
 * The immutable description of a render target before it is allocated: graph identity, format,
 * dimensions, mip/array layout, and sampler policy.
 *
 * <p>A spec is what the resource layer allocates from and what the render graph refers to. Keeping
 * the description separate from the (device-dependent) allocation is what lets resource planning be
 * tested without a GPU.</p>
 *
 * @param resource    the graph resource identity this target backs
 * @param format      the concrete Vulkan format
 * @param width       width in pixels, &gt; 0
 * @param height      height in pixels, &gt; 0
 * @param mipLevels   number of mip levels, &gt;= 1
 * @param arrayLayers number of array layers, &gt;= 1
 * @param sampler     sampling policy
 */
public record TextureSpec(
        RenderResource resource,
        RenderTargetFormat format,
        int width,
        int height,
        int mipLevels,
        int arrayLayers,
        SamplerPolicy sampler) {

    public TextureSpec {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(sampler, "sampler");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("target '" + resource.name() + "' has non-positive size "
                    + width + "x" + height);
        }
        if (mipLevels < 1) {
            throw new IllegalArgumentException("target '" + resource.name() + "' must have >= 1 mip level");
        }
        if (arrayLayers < 1) {
            throw new IllegalArgumentException("target '" + resource.name() + "' must have >= 1 array layer");
        }
        if (format.kind() != resource.kind()) {
            throw new IllegalArgumentException("target '" + resource.name() + "' is kind " + resource.kind()
                    + " but format " + format + " is kind " + format.kind());
        }
    }

    /** @return a single-layer, single-mip spec */
    public static TextureSpec of(RenderResource resource, RenderTargetFormat format,
                                 int width, int height, SamplerPolicy sampler) {
        return new TextureSpec(resource, format, width, height, 1, 1, sampler);
    }

    /** @return a copy of this spec at a new size, preserving format and sampler policy */
    public TextureSpec withSize(int newWidth, int newHeight) {
        return new TextureSpec(resource, format, newWidth, newHeight, mipLevels, arrayLayers, sampler);
    }

    /** @return whether this target is a depth target */
    public boolean isDepth() {
        return format.isDepth();
    }

    /** @return approximate backing size in bytes, ignoring mip levels */
    public long sizeInBytes() {
        return (long) width * height * arrayLayers * format.bytesPerPixel();
    }

    @Override
    public String toString() {
        return resource.name() + "[" + format + " " + width + "x" + height
                + " mip=" + mipLevels + " layers=" + arrayLayers + " " + sampler + "]";
    }
}
