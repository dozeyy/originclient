package com.vulkairis.client.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Disables Iris's internal-prefix validation in {@code TransformPatcher}.
 *
 * <p>Iris checks every post-transform AST for identifiers prefixed with {@code iris_},
 * {@code irisMain}, or {@code moj_import} and throws {@code IllegalArgumentException} when
 * any are found. This validation assumes an OpenGL pipeline where those prefixes are fully
 * rewritten by Iris's own transformer chain. Under Vulkairis, the identifiers are instead
 * normalised later by {@link com.vulkairis.translation.ShaderTranslator#normalizeIrisAttributeNames},
 * which runs after the GLSL source leaves Iris and enters our Vulkan compilation path.</p>
 *
 * <p>The mixin replaces the static {@code internalPrefixes} list with an empty list via
 * {@code @Shadow @Mutable}, so the validation loop finds nothing and never throws.</p>
 */
@Mixin(value = net.irisshaders.iris.pipeline.transform.TransformPatcher.class, remap = false)
public class TransformPatcherMixin {

    @Shadow
    @Mutable
    private static List<String> internalPrefixes;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void vulkairis$onClinit(CallbackInfo ci) {
        internalPrefixes = Collections.emptyList();
    }

    /**
     * Intercept the public {@code patchComposite} entry point to suppress the internal-prefix
     * validation. The field is read by the transformer's per-type phase callback via
     * {@code getstatic}, so replacing it before the method body begins is sufficient.
     *
     * <p>We target {@code patchComposite} because that is the entry point for composite/deferred
     * shaders, which are the ones most commonly tripped by this validation (they do not go
     * through the full Iris attribute renaming path). The same field is also read by the
     * gbuffers/vanilla/sodium paths, so we also inject into those entry points below.</p>
     */
    @Inject(method = "patchComposite", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesComposite(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchComposite");
    }

    @Inject(method = "patchVanilla", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesVanilla(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchVanilla");
    }

    @Inject(method = "patchSodium", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesSodium(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchSodium");
    }

    @Inject(method = "patchDHTerrain", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesDHTerrain(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchDHTerrain");
    }

    @Inject(method = "patchDHGeneric", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesDHGeneric(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchDHGeneric");
    }

    @Inject(method = "patchCompute", at = @At("HEAD"))
    private static void vulkairis$clearPrefixesCompute(CallbackInfoReturnable<?> cir) {
        internalPrefixes = Collections.emptyList();
        vulkairis$traceTransform("patchCompute");
    }

    /**
     * Phase 2: opt-in trace proving Iris source transformations run before Vulkairis
     * interception. With {@code traceLogging} enabled the log shows
     * {@code iris-transform-start} lines followed by the {@code post-iris-transform intercept}
     * lines from {@code GlShaderMixin}, evidencing the interception order.
     */
    private static void vulkairis$traceTransform(String entryPoint) {
        try {
            if (com.vulkairis.config.ConfigManager.getConfig().isTraceLogging()) {
                org.apache.logging.log4j.LogManager.getLogger("Vulkairis/Trace")
                        .info("[Vulkairis/Trace] iris-transform-start: {}", entryPoint);
            }
        } catch (Throwable ignored) {
        }
    }
}
