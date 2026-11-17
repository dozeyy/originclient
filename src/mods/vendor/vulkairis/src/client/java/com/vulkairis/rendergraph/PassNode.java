package com.vulkairis.rendergraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One declarative node in the shader-pack render graph: what it reads, what it writes, its depth
 * attachment, and which shader stages it runs.
 *
 * <p>A node describes <em>contracts</em>, not Vulkan objects. The graph compiler turns the
 * contracts into a dependency order, and later stages turn that order plus the resource layer into
 * command buffers. MRT is expressed as multiple color-attachment writes; a depth-only pass is a
 * graphics pass with a depth write and no color writes; a compute pass declares a compute stage and
 * storage accesses.</p>
 */
public final class PassNode {

    /** Broad pass category. */
    public enum Kind {
        GRAPHICS,
        COMPUTE,
        TRANSFER
    }

    private final String name;
    private final Kind kind;
    private final Set<ShaderStage> stages;
    private final List<ResourceAccess> reads;
    private final List<ResourceAccess> writes;
    /** Viewport size for this pass; -1 means unspecified (inherited from the target). */
    private final int viewportWidth;
    private final int viewportHeight;
    /** Whether color/depth attachments are cleared on load (vs preserved). */
    private final boolean clearColor;
    private final boolean clearDepth;
    /** Depth test/write behavior for this pass. */
    private final boolean depthTest;
    private final boolean depthWrite;
    /**
     * Vulkan compare op for depth testing, as a plain int (2 = LESS, 3 = LESS_OR_EQUAL).
     * Kept dependency-free; see {@code VkCompareOp}.
     */
    private final int depthCompareOp;
    /** Whether alpha blending is enabled for this pass. */
    private final boolean blendEnabled;
    /** Stage program names this pass executes (Iris program identities). */
    private final List<String> programNames;
    /** Explicit ordering constraints: this pass must run after each named pass. */
    private final List<String> runAfter;

    private PassNode(Builder builder) {
        this.name = builder.name;
        this.kind = builder.kind;
        this.stages = Collections.unmodifiableSet(EnumSet.copyOf(builder.stages));
        this.reads = Collections.unmodifiableList(new ArrayList<>(builder.reads));
        this.writes = Collections.unmodifiableList(new ArrayList<>(builder.writes));
        this.viewportWidth = builder.viewportWidth;
        this.viewportHeight = builder.viewportHeight;
        this.clearColor = builder.clearColor;
        this.clearDepth = builder.clearDepth;
        this.depthTest = builder.depthTest;
        this.depthWrite = builder.depthWrite;
        this.depthCompareOp = builder.depthCompareOp;
        this.blendEnabled = builder.blendEnabled;
        this.programNames = Collections.unmodifiableList(new ArrayList<>(builder.programNames));
        this.runAfter = Collections.unmodifiableList(new ArrayList<>(builder.runAfter));
        validate();
    }

    private void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must be non-blank");
        }
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("pass '" + name + "' declares no shader stages");
        }
        if (kind == Kind.COMPUTE && !stages.contains(ShaderStage.COMPUTE)) {
            throw new IllegalArgumentException("compute pass '" + name + "' must declare the COMPUTE stage");
        }
        if ((kind == Kind.GRAPHICS || kind == Kind.TRANSFER) && stages.contains(ShaderStage.COMPUTE)) {
            throw new IllegalArgumentException(kind + " pass '" + name + "' must not declare the COMPUTE stage");
        }
        for (ResourceAccess access : reads) {
            if (!access.resource().kind().supports(access.usage())) {
                throw new IllegalArgumentException("pass '" + name + "' reads " + access.resource()
                        + " as " + access.usage() + " but " + access.resource().kind() + " cannot serve it");
            }
        }
        for (ResourceAccess access : writes) {
            if (!access.resource().kind().supports(access.usage())) {
                throw new IllegalArgumentException("pass '" + name + "' writes " + access.resource()
                        + " as " + access.usage() + " but " + access.resource().kind() + " cannot serve it");
            }
        }
        if ((viewportWidth != -1 && viewportWidth <= 0)
                || (viewportHeight != -1 && viewportHeight <= 0)) {
            throw new IllegalArgumentException("pass '" + name
                    + "' has a non-positive viewport; leave it unspecified (-1) instead");
        }
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public Set<ShaderStage> stages() {
        return stages;
    }

    public List<ResourceAccess> reads() {
        return reads;
    }

    public List<ResourceAccess> writes() {
        return writes;
    }

    /** @return viewport width, or -1 when inherited from the target */
    public int viewportWidth() {
        return viewportWidth;
    }

    /** @return viewport height, or -1 when inherited from the target */
    public int viewportHeight() {
        return viewportHeight;
    }

    /** @return whether color attachments clear on load */
    public boolean clearColor() {
        return clearColor;
    }

    /** @return whether the depth attachment clears on load */
    public boolean clearDepth() {
        return clearDepth;
    }

    /** @return whether depth testing is enabled */
    public boolean depthTest() {
        return depthTest;
    }

    /** @return whether depth writes are enabled */
    public boolean depthWrite() {
        return depthWrite;
    }

    /** @return Vulkan compare op as a plain int (2 = LESS, 3 = LESS_OR_EQUAL) */
    public int depthCompareOp() {
        return depthCompareOp;
    }

    /** @return whether alpha blending is enabled */
    public boolean blendEnabled() {
        return blendEnabled;
    }

    /** @return stage program names this pass executes */
    public List<String> programNames() {
        return programNames;
    }

    /** @return passes this pass must run after */
    public List<String> runAfter() {
        return runAfter;
    }

    /** @return the color-attachment writes (more than one means MRT) */
    public List<ResourceAccess> colorOutputs() {
        List<ResourceAccess> colors = new ArrayList<>();
        for (ResourceAccess access : writes) {
            if (access.usage() == ResourceUsage.COLOR_ATTACHMENT) {
                colors.add(access);
            }
        }
        return Collections.unmodifiableList(colors);
    }

    /** @return the depth-attachment write, if this pass has one */
    public Optional<ResourceAccess> depthOutput() {
        for (ResourceAccess access : writes) {
            if (access.usage() == ResourceUsage.DEPTH_ATTACHMENT) {
                return Optional.of(access);
            }
        }
        return Optional.empty();
    }

    /** @return whether this is a graphics pass with a depth attachment and no color attachments */
    public boolean isDepthOnly() {
        return kind == Kind.GRAPHICS && depthOutput().isPresent() && colorOutputs().isEmpty();
    }

    /** @return every storage access (read and/or write) declared by this pass */
    public List<ResourceAccess> storageAccesses() {
        List<ResourceAccess> storage = new ArrayList<>();
        for (ResourceAccess access : reads) {
            if (access.usage() == ResourceUsage.STORAGE) {
                storage.add(access);
            }
        }
        for (ResourceAccess access : writes) {
            if (access.usage() == ResourceUsage.STORAGE) {
                storage.add(access);
            }
        }
        return Collections.unmodifiableList(storage);
    }

    /** @return whether this pass declares any color, depth, storage or transfer write */
    public boolean hasWrites() {
        return !writes.isEmpty();
    }

    /** @return a single-line description for frame-graph dumps */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" [").append(kind).append(']');
        if (!reads.isEmpty()) {
            builder.append(" reads=").append(reads);
        }
        if (!writes.isEmpty()) {
            builder.append(" writes=").append(writes);
        }
        if (viewportWidth != -1 && viewportHeight != -1) {
            builder.append(" viewport=").append(viewportWidth).append('x').append(viewportHeight);
        }
        if (!programNames.isEmpty()) {
            builder.append(" programs=").append(programNames);
        }
        if (!runAfter.isEmpty()) {
            builder.append(" after=").append(runAfter);
        }
        builder.append(depthTest ? " depth" : " nodepth");
        if (blendEnabled) {
            builder.append(" blend");
        }
        return builder.toString();
    }

    /** @return a builder for a graphics pass */
    public static Builder graphics(String name) {
        return new Builder(name, Kind.GRAPHICS);
    }

    /** @return a builder for a compute pass */
    public static Builder compute(String name) {
        return new Builder(name, Kind.COMPUTE);
    }

    /** @return a builder for a transfer pass */
    public static Builder transfer(String name) {
        return new Builder(name, Kind.TRANSFER);
    }

    /** Fluent builder for {@link PassNode}. */
    public static final class Builder {
        private final String name;
        private final Kind kind;
        private final Set<ShaderStage> stages = EnumSet.noneOf(ShaderStage.class);
        private final List<ResourceAccess> reads = new ArrayList<>();
        private final List<ResourceAccess> writes = new ArrayList<>();
        private int viewportWidth = -1;
        private int viewportHeight = -1;
        private boolean clearColor = true;
        private boolean clearDepth = true;
        private boolean depthTest = true;
        private boolean depthWrite = true;
        private int depthCompareOp = 3;
        private boolean blendEnabled = false;
        private final List<String> programNames = new ArrayList<>();
        private final List<String> runAfter = new ArrayList<>();

        private Builder(String name, Kind kind) {
            this.name = Objects.requireNonNull(name, "name");
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public Builder stage(ShaderStage stage) {
            stages.add(Objects.requireNonNull(stage, "stage"));
            return this;
        }

        public Builder stages(ShaderStage... stages) {
            for (ShaderStage stage : stages) {
                stage(stage);
            }
            return this;
        }

        /** Declares a read access with an explicit usage. */
        public Builder read(RenderResource resource, ResourceUsage usage) {
            reads.add(new ResourceAccess(resource, usage));
            return this;
        }

        /** Declares a write access with an explicit usage. */
        public Builder write(RenderResource resource, ResourceUsage usage) {
            writes.add(new ResourceAccess(resource, usage));
            return this;
        }

        /** Convenience: samples {@code resource}. */
        public Builder sampled(RenderResource resource) {
            return read(resource, ResourceUsage.SAMPLED);
        }

        /** Convenience: writes {@code resource} as a color attachment (repeat for MRT). */
        public Builder colorOutput(RenderResource resource) {
            return write(resource, ResourceUsage.COLOR_ATTACHMENT);
        }

        /** Convenience: writes {@code resource} as the depth attachment. */
        public Builder depthOutput(RenderResource resource) {
            return write(resource, ResourceUsage.DEPTH_ATTACHMENT);
        }

        /** Convenience: declares a read/write storage access to {@code resource}. */
        public Builder storage(RenderResource resource) {
            return read(resource, ResourceUsage.STORAGE);
        }

        /** Viewport size for this pass; omit to inherit the target size. */
        public Builder viewport(int width, int height) {
            this.viewportWidth = width;
            this.viewportHeight = height;
            return this;
        }

        /** Clear behavior for color/depth attachments on load. */
        public Builder clear(boolean color, boolean depth) {
            this.clearColor = color;
            this.clearDepth = depth;
            return this;
        }

        /** Depth test/write/compare behavior (compare op as a plain int, 3 = LESS_OR_EQUAL). */
        public Builder depth(boolean test, boolean write, int compareOp) {
            this.depthTest = test;
            this.depthWrite = write;
            this.depthCompareOp = compareOp;
            return this;
        }

        /** Alpha blending for this pass. */
        public Builder blend(boolean enabled) {
            this.blendEnabled = enabled;
            return this;
        }

        /** Stage program names this pass executes. */
        public Builder programs(String... names) {
            for (String name : names) {
                this.programNames.add(Objects.requireNonNull(name, "program name"));
            }
            return this;
        }

        /** Ordering constraint: this pass must run after each named pass. */
        public Builder after(String... names) {
            for (String name : names) {
                this.runAfter.add(Objects.requireNonNull(name, "run-after name"));
            }
            return this;
        }

        public PassNode build() {
            return new PassNode(this);
        }
    }
}
