package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Target: Flywheel 1.0.6-44 {@code dev.engine_room.flywheel.backend.NoiseTextures}
 * {@code public static void reload(ResourceManager)}, its call
 * {@code NativeImage.read(NativeImage.Format.LUMINANCE, InputStream)} (javap on the pinned jar).
 *
 * <p>Why: Flywheel loads its blue-noise texture as a 1-byte-per-pixel LUMINANCE image into a
 * DynamicTexture. Under VulkanMod 0.6.7 the DynamicTexture's Vulkan image is always RGBA
 * (TextureUtil.prepareImage without a format), and VulkanImage.uploadSubTextureAsync copies
 * width x height x 4 bytes from the image's 1-byte-per-pixel buffer: a read past the end of
 * native memory. On Will's PC (RX 9070 XT) that was an EXCEPTION_ACCESS_VIOLATION in
 * lwjgl.dll nmemcpy during the very first resource reload (hs_err_pid38564, 2026-11-16);
 * CI's lavapipe happened to survive it. Reading the PNG as RGBA (stb_image copies the grey
 * value into R, G and B, alpha 255) makes the buffer the size VulkanMod copies, and Flywheel's
 * shaders, which sample the red channel, see the same noise.
 *
 * <p>If it stops applying: the game crashes natively at startup with VulkanMod + Flywheel
 * (with or without origin:vulkan). The plugin applies it only with the pinned Flywheel and
 * VulkanMod; defaultRequire = 1 fails loudly at boot if the call moved.
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.NoiseTextures")
abstract class NoiseTexturesMixin {
	@ModifyArg(method = "reload", remap = false,
			at = @At(value = "INVOKE", remap = true,
					target = "Lcom/mojang/blaze3d/platform/NativeImage;read(Lcom/mojang/blaze3d/platform/NativeImage$Format;Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;"),
			index = 0)
	private static NativeImage.Format origin$readAsRgba(NativeImage.Format format) {
		return NativeImage.Format.RGBA;
	}
}
