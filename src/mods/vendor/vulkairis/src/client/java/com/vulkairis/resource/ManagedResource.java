package com.vulkairis.resource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulates a graphics resource whose lifecycle is managed by Vulkairis.
 * Retains creation/destruction timestamps, owner, state, and usage statistics.
 */
public class ManagedResource {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public enum ResourceType {
        SHADER,
        PROGRAM,
        FRAMEBUFFER,
        TEXTURE,
        BUFFER,
        SAMPLER,
        OTHER
    }

    private final int id;
    private final ResourceType type;
    private final String name;
    private final String owner;
    private volatile ResourceLifecycleState state;
    private final long createdAt;
    private volatile long destroyedAt;
    private final AtomicInteger attemptedReuseCount = new AtomicInteger(0);
    private volatile long nativeHandle = 0L;

    public ManagedResource(int id, ResourceType type, String name, String owner, long nativeHandle) {
        this.id = id;
        this.type = (type != null) ? type : ResourceType.OTHER;
        this.name = (name != null) ? name : "resource_" + id;
        this.owner = (owner != null) ? owner : "Iris";
        this.nativeHandle = nativeHandle;
        this.state = ResourceLifecycleState.CREATED;
        this.createdAt = System.currentTimeMillis();
        this.destroyedAt = 0L;
    }

    public int getId() {
        return id;
    }

    public ResourceType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getOwner() {
        return owner;
    }

    public ResourceLifecycleState getState() {
        return state;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getDestroyedAt() {
        return destroyedAt;
    }

    public int getAttemptedReuseCount() {
        return attemptedReuseCount.get();
    }

    public long getNativeHandle() {
        return nativeHandle;
    }

    public void setNativeHandle(long handle) {
        this.nativeHandle = handle;
    }

    public synchronized boolean transitionTo(ResourceLifecycleState nextState) {
        if (state == ResourceLifecycleState.RELEASED && nextState != ResourceLifecycleState.CREATED) {
            attemptedReuseCount.incrementAndGet();
            return false;
        }
        if (state.canTransitionTo(nextState)) {
            this.state = nextState;
            if (nextState == ResourceLifecycleState.RELEASED) {
                this.destroyedAt = System.currentTimeMillis();
            }
            return true;
        }
        return false;
    }

    public synchronized void markReleased() {
        this.state = ResourceLifecycleState.RELEASED;
        this.destroyedAt = System.currentTimeMillis();
    }

    public synchronized void recordAttemptedReuse() {
        attemptedReuseCount.incrementAndGet();
    }

    public boolean isDestroyed() {
        return state == ResourceLifecycleState.RELEASED;
    }

    public String formatCreatedAt() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(createdAt));
    }

    public String formatDestroyedAt() {
        return (destroyedAt > 0) ? TIME_FORMATTER.format(Instant.ofEpochMilli(destroyedAt)) : "N/A";
    }

    @Override
    public String toString() {
        return "ManagedResource{" +
                "id=" + id +
                ", type=" + type +
                ", name='" + name + '\'' +
                ", owner='" + owner + '\'' +
                ", state=" + state +
                ", createdAt=" + formatCreatedAt() +
                ", destroyedAt=" + formatDestroyedAt() +
                ", reuseAttempts=" + attemptedReuseCount.get() +
                ", nativeHandle=0x" + Long.toHexString(nativeHandle) +
                '}';
    }
}
