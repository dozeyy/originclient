package com.vulkairis.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager tracking the lifecycle and validity of translated shader resources.
 * Prevents invalid states, detects illegal resource re-use after release,
 * and outputs structured diagnostic traces.
 */
public class VulkairisResourceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ResourceManager");
    private static final VulkairisResourceManager INSTANCE = new VulkairisResourceManager();

    private final Map<Integer, ManagedResource> resources = new ConcurrentHashMap<>();

    public static VulkairisResourceManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a new graphics resource into the lifecycle manager.
     */
    public ManagedResource createResource(ManagedResource.ResourceType type, int id, String name, String owner, long nativeHandle) {
        ManagedResource resource = new ManagedResource(id, type, name, owner, nativeHandle);
        resources.put(id, resource);
        LOGGER.debug("[VULKAIRIS] Resource registered: id={}, type={}, name='{}', owner='{}'",
                id, type, name, owner);
        return resource;
    }

    public ManagedResource createResource(ManagedResource.ResourceType type, int id, String name, String owner) {
        return createResource(type, id, name, owner, 0L);
    }

    /**
     * Retrieves the resource by ID.
     */
    public ManagedResource getResource(int id) {
        return resources.get(id);
    }

    /**
     * Validates if a resource exists and is currently valid (not destroyed/released).
     * If destroyed, records attempted reuse and logs structured diagnostic.
     */
    public boolean validateResource(int id) {
        ManagedResource res = resources.get(id);
        if (res == null) {
            LOGGER.warn("[VULKAIRIS] Shader resource request: Resource {} not found in registry!", id);
            return false;
        }

        if (res.isDestroyed()) {
            res.recordAttemptedReuse();
            logDiagnostic(res, "VALIDATE_FAIL_DESTROYED");
            return false;
        }

        return true;
    }

    /**
     * Marks a resource as ACTIVE (bound / assigned).
     */
    public boolean markActive(int id) {
        ManagedResource res = resources.get(id);
        if (res == null) return false;

        if (res.isDestroyed()) {
            res.recordAttemptedReuse();
            logDiagnostic(res, "MARK_ACTIVE_AFTER_RELEASE");
            return false;
        }

        return res.transitionTo(ResourceLifecycleState.ACTIVE);
    }

    /**
     * Marks a resource as IN_USE (currently referenced in active render pass / command buffer).
     */
    public boolean markInUse(int id) {
        ManagedResource res = resources.get(id);
        if (res == null) return false;

        if (res.isDestroyed()) {
            res.recordAttemptedReuse();
            logDiagnostic(res, "MARK_IN_USE_AFTER_RELEASE");
            return false;
        }

        return res.transitionTo(ResourceLifecycleState.IN_USE);
    }

    /**
     * Releases a resource, transitioning it to RELEASED.
     * Idempotent: safe to call multiple times without throwing exceptions.
     */
    public boolean releaseResource(int id) {
        ManagedResource res = resources.get(id);
        if (res == null) {
            return false;
        }

        if (res.isDestroyed()) {
            LOGGER.debug("[VULKAIRIS] Resource {} already released; ignoring redundant release.", id);
            return false;
        }

        res.markReleased();
        LOGGER.debug("[VULKAIRIS] Resource {} ({}) released.", id, res.getName());
        return true;
    }

    /**
     * Checks if a resource is destroyed. Returns true if unknown or in RELEASED state.
     */
    public boolean isDestroyed(int id) {
        ManagedResource res = resources.get(id);
        return res == null || res.isDestroyed();
    }

    /**
     * Recreates or resets a previously destroyed resource.
     */
    public ManagedResource recreateResource(int id, ManagedResource.ResourceType type, String name, String owner, long nativeHandle) {
        ManagedResource res = new ManagedResource(id, type, name, owner, nativeHandle);
        resources.put(id, res);
        LOGGER.info("[VULKAIRIS] Recreated resource: id={}, type={}, name='{}'", id, type, name);
        return res;
    }

    /**
     * Emits structured diagnostic log in the exact requested format.
     */
    public void logDiagnostic(ManagedResource res, String context) {
        LOGGER.warn("""
                [VULKAIRIS] Shader resource request
                [VULKAIRIS] Context: {}
                [VULKAIRIS] Resource: {} ({})
                [VULKAIRIS] State: {}
                [VULKAIRIS] Owner: {}
                [VULKAIRIS] Last created: {}
                [VULKAIRIS] Destroyed at: {}
                [VULKAIRIS] Attempted reuse: {}
                """,
                context,
                res.getId(), res.getName(),
                res.getState(),
                res.getOwner(),
                res.formatCreatedAt(),
                res.formatDestroyedAt(),
                res.getAttemptedReuseCount());
    }

    /**
     * Clears all resources (e.g. on game shutdown).
     */
    public void clear() {
        for (ManagedResource res : resources.values()) {
            res.markReleased();
        }
        resources.clear();
        LOGGER.info("[VULKAIRIS] Cleaned up VulkairisResourceManager.");
    }
}
