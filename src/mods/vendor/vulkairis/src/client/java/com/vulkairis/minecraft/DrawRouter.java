package com.vulkairis.minecraft;

import java.util.*;
import java.util.logging.Logger;

/**
 * Routes Minecraft's rendering calls into the Vulkairis render graph.
 */
public final class DrawRouter {
    private static final Logger LOGGER = Logger.getLogger("Vulkairis/DrawRouter");

    public enum DrawCategory {
        TERRAIN,
        WATER,
        ENTITIES,
        HAND,
        GUI,
        POST_PROCESS
    }

    private final Map<DrawCategory, Object> categoryMatrix = new EnumMap<>(DrawCategory.class);

    /**
     * Intercepts a render boundary and binds the corresponding program.
     */
    public void routeDraw(DrawCategory category, Runnable drawCall) {
        // ponytail: bind descriptors for the category, then run the call.
        bindCategoryResources(category);
        drawCall.run();
    }

    private void bindCategoryResources(DrawCategory category) {
        // ponytail: resolve current pipeline from render graph for this category.
    }

    public void setCategoryState(DrawCategory category, Object state) {
        categoryMatrix.put(category, state);
    }
}
