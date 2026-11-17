package com.vulkairis.rendergraph;

/**
 * Thrown when a render graph cannot be resolved, for example because it contains a dependency
 * cycle or references a resource that is neither produced by a pass nor explicitly imported.
 */
public class RenderGraphException extends RuntimeException {

    public RenderGraphException(String message) {
        super(message);
    }

    public RenderGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
