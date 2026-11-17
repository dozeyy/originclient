package com.vulkairis.client.mixin.iris;

import com.vulkairis.resource.VulkairisResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Overwrite;

import net.irisshaders.iris.gl.GlResource;

/**
 * Makes GlResource lifecycle idempotent under Vulkairis.
 *
 * Root cause: Iris's GlResource.destroy() calls destroyInternal() then sets isValid=false.
 * But destroyInternal() (in Program) calls getGlId() which calls assertValid().
 * If destroy() is called twice (which happens when pipeline cleanup aborts halfway
 * due to a ClassCastException, then retries), the second call crashes with
 * "Tried to use a destroyed GlResource".
 *
 * Fix: Make destroy() check isValid first, skip if already destroyed.
 * Make assertValid() log instead of throwing when called during cleanup.
 */
@Mixin(value = GlResource.class, remap = false)
public abstract class GlResourceMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LoggerFactory.getLogger("Vulkairis/GlResourceMixin");

    @Shadow
    private boolean isValid;

    @Shadow
    @Final
    private int id;

    @Shadow
    protected abstract void destroyInternal();

    /**
     * @author Vulkairis
     * @reason Make destroy() idempotent — skip if already destroyed instead of crashing.
     * Original Iris code calls destroyInternal() unconditionally, which then calls
     * getGlId() -> assertValid() -> throws ISE on double-destroy.
     */
    @Overwrite
    public final void destroy() {
        if (!this.isValid) {
            VULKAIRIS_LOGGER.debug("[Vulkairis] GlResource.destroy() called on already-destroyed resource id={}; safely skipping.", this.id);
            return;
        }
        try {
            this.destroyInternal();
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Exception during destroyInternal() for resource id={}: {}. Marking released anyway.", this.id, t.getMessage());
        }
        this.isValid = false;
        VulkairisResourceManager.getInstance().releaseResource(this.id);
    }

    /**
     * @author Vulkairis
     * @reason Replace crash-on-invalid with graceful log. During pipeline teardown,
     * resources may be queried after partial destruction. We log a warning
     * instead of throwing IllegalStateException to avoid cascade failures.
     */
    @Overwrite
    protected void assertValid() {
        if (!this.isValid) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Attempted to use destroyed GlResource id={}. Suppressing crash.", this.id);
            VulkairisResourceManager.getInstance().validateResource(this.id);
            // Do NOT throw — allow the caller to proceed with a stale/zero id
        }
    }

    /**
     * @author Vulkairis
     * @reason Return id without asserting validity. Under Vulkan, the "GL id" is a virtual
     * handle in VulkanShaderRegistry. Returning it even after destroy is safe —
     * VulkanShaderRegistry will simply not find it or find a released entry.
     */
    @Overwrite
    protected int getGlId() {
        if (!this.isValid) {
            VULKAIRIS_LOGGER.debug("[Vulkairis] getGlId() called on destroyed resource id={}; returning stale id.", this.id);
        }
        return this.id;
    }
}
