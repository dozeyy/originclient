package com.vulkairis.iris;

import net.irisshaders.iris.gl.state.StateUpdateNotifiers;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures all Iris StateUpdateNotifiers are initialized with non-null handlers.
 * Under VulkanMod, Minecraft's GlStateManager and RenderSystem are initialized
 * before Iris mixins can inject their static initializers, leaving these fields null.
 */
public class StateUpdateNotifierManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/StateNotifiers");

    public static void ensureInitialized() {
        ValueUpdateNotifier noop = listener -> {};

        if (StateUpdateNotifiers.fogStartNotifier == null) {
            StateUpdateNotifiers.fogStartNotifier = noop;
        }
        if (StateUpdateNotifiers.fogEndNotifier == null) {
            StateUpdateNotifiers.fogEndNotifier = noop;
        }
        if (StateUpdateNotifiers.blendFuncNotifier == null) {
            StateUpdateNotifiers.blendFuncNotifier = noop;
        }
        if (StateUpdateNotifiers.bindTextureNotifier == null) {
            StateUpdateNotifiers.bindTextureNotifier = noop;
        }
        if (StateUpdateNotifiers.normalTextureChangeNotifier == null) {
            StateUpdateNotifiers.normalTextureChangeNotifier = noop;
        }
        if (StateUpdateNotifiers.specularTextureChangeNotifier == null) {
            StateUpdateNotifiers.specularTextureChangeNotifier = noop;
        }
        if (StateUpdateNotifiers.phaseChangeNotifier == null) {
            StateUpdateNotifiers.phaseChangeNotifier = noop;
        }
        if (StateUpdateNotifiers.fallbackEntityNotifier == null) {
            StateUpdateNotifiers.fallbackEntityNotifier = noop;
        }

        LOGGER.debug("[Vulkairis] Verified and initialized all Iris StateUpdateNotifiers.");
    }
}
