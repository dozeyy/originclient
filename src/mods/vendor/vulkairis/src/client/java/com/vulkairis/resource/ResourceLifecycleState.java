package com.vulkairis.resource;

/**
 * Lifecycle states for translated graphics resources in Vulkairis.
 * Strict linear flow: CREATED -> ACTIVE -> IN_USE -> RELEASED.
 * Invalid transition: RELEASED -> IN_USE (or any use after release).
 */
public enum ResourceLifecycleState {
    CREATED,
    ACTIVE,
    IN_USE,
    RELEASED;

    /**
     * Checks if transitioning from current state to target state is valid.
     */
    public boolean canTransitionTo(ResourceLifecycleState next) {
        if (this == RELEASED) {
            // A released resource cannot be used or transitioned unless recreated
            return next == CREATED;
        }
        return switch (this) {
            case CREATED -> next == ACTIVE || next == RELEASED;
            case ACTIVE -> next == IN_USE || next == ACTIVE || next == RELEASED;
            case IN_USE -> next == ACTIVE || next == IN_USE || next == RELEASED;
            default -> false;
        };
    }
}
