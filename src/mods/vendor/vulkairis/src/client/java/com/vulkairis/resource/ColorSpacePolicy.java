package com.vulkairis.resource;

import java.util.Set;

/**
 * Part IX: explicit color/HDR/gamma policy.
 *
 * <p>Color-space mistakes make a correct renderer look broken: bloom becomes overly bright from
 * double gamma, tone mapping is applied twice, or lighting operates in display space. This class is
 * the single place that decides whether a target is linear/HDR/display-encoded and whether tone
 * mapping or gamma should be applied at a given stage.</p>
 *
 * <p>The guiding rule: if the shader pack implements tone mapping / gamma in its final pass,
 * Vulkairis must <em>not</em> apply it again. Only when the pack does not handle it does the
 * presentation stage own that conversion.</p>
 */
public final class ColorSpacePolicy {

    /** How the values stored in a resource are encoded. */
    public enum Encoding {
        /** Linear, LDR (0..1) — e.g. normalized color attachments. */
        LINEAR,
        /** Linear, HDR (values may exceed 1) — e.g. RGBA16F scene color. */
        HDR_LINEAR,
        /** sRGB-encoded display values. */
        SRGB
    }

    private static final Set<RenderTargetFormat> HDR_FORMATS = Set.of(
            RenderTargetFormat.RGBA16F,
            RenderTargetFormat.RGBA32F,
            RenderTargetFormat.R11G11B10F);

    private ColorSpacePolicy() {
    }

    /**
     * @param format a render-target format
     * @return whether the format stores HDR (values &gt; 1) linear data
     */
    public static boolean isHdrFormat(RenderTargetFormat format) {
        return HDR_FORMATS.contains(format);
    }

    /**
     * @param format a render-target format
     * @return the encoding Vulkairis assigns to scene/intermediate color targets of this format
     */
    public static Encoding sceneEncoding(RenderTargetFormat format) {
        return isHdrFormat(format) ? Encoding.HDR_LINEAR : Encoding.LINEAR;
    }

    /** @return the encoding of the final presentation target (the swapchain is display-encoded) */
    public static Encoding presentationEncoding() {
        return Encoding.SRGB;
    }

    /**
     * Whether Vulkairis should apply tone mapping at presentation.
     *
     * @param shaderPackHandlesToneMapping whether the pack's final pass implements tone mapping
     * @param hdrScene                     whether the scene color target is HDR
     * @return {@code true} only when the pack does not handle it and the scene is HDR
     */
    public static boolean shouldApplyToneMapping(boolean shaderPackHandlesToneMapping, boolean hdrScene) {
        return hdrScene && !shaderPackHandlesToneMapping;
    }

    /**
     * Whether Vulkairis should apply the linear&rarr;display gamma conversion at presentation.
     *
     * @param shaderPackHandlesGamma whether the pack's final pass already encodes to display space
     * @return {@code true} only when the pack does not handle it
     */
    public static boolean shouldApplyPresentationGamma(boolean shaderPackHandlesGamma) {
        return !shaderPackHandlesGamma;
    }

    /** Standard sRGB EOTF (encoded &rarr; linear). */
    public static float sRgbToLinear(float encoded) {
        if (encoded <= 0.04045f) {
            return encoded / 12.92f;
        }
        return (float) Math.pow((encoded + 0.055f) / 1.055f, 2.4);
    }

    /** Standard sRGB OETF (linear &rarr; encoded). */
    public static float linearToSRgb(float linear) {
        if (linear <= 0.0031308f) {
            return linear * 12.92f;
        }
        return (float) (1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055);
    }

    /** @return a short policy summary for diagnostics */
    public static String describe() {
        return "scene=HDR_LINEAR for floating-point targets, presentation=SRGB, "
                + "tone-map/gamma delegated to the shader pack when it handles them";
    }
}
