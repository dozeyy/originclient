package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.gl.GLDebug;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.io.PrintStream;

@Mixin(value = GLDebug.class, remap = false)
public class GLDebugMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/GLDebugMixin");

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; OpenGL debug groups do not exist.
     */
    @Overwrite
    public static void pushGroup(int source, String name) {
        // Safe no-op under Vulkan
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; OpenGL debug groups do not exist.
     */
    @Overwrite
    public static void popGroup() {
        // Safe no-op under Vulkan
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; OpenGL debug naming is not supported.
     */
    @Overwrite
    public static void nameObject(int type, int object, String name) {
        // Safe no-op under Vulkan
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; avoid querying raw OpenGL capabilities.
     */
    @Overwrite
    public static void reloadDebugState() {
        // Safe no-op under Vulkan
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; avoid attaching OpenGL debug message callbacks.
     */
    @Overwrite
    public static int setupDebugMessageCallback() {
        return 0;
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; avoid attaching OpenGL debug message callbacks.
     */
    @Overwrite
    public static int setupDebugMessageCallback(PrintStream stream) {
        return 0;
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan.
     */
    @Overwrite
    public static int disableDebugMessages() {
        return 0;
    }
}
