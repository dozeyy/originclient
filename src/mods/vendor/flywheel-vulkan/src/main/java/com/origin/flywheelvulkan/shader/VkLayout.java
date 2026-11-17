package com.origin.flywheelvulkan.shader;

import java.util.Map;

import org.lwjgl.vulkan.VK10;

/**
 * The one resource layout every origin:vulkan pipeline shares (descriptor set 0 + one push-constant
 * range). Pure constants so the GLSL retarget, the descriptor set layout and the unit tests agree.
 *
 * <p>Uniform block bindings 0-4 follow Flywheel's own UBO indices (Uniforms.FRAME_INDEX..LEVEL_INDEX).
 */
public final class VkLayout {
	private VkLayout() {
	}

	public static final int FRAME = 0;
	public static final int FOG = 1;
	public static final int OPTIONS = 2;
	public static final int PLAYER = 3;
	public static final int LEVEL = 4;
	public static final int UNIFORM_BLOCKS = 5;

	public static final int INSTANCES = 5;
	public static final int LIGHT_LUT = 6;
	public static final int LIGHT_SECTIONS = 7;

	public static final int DIFFUSE_TEX = 8;
	public static final int OVERLAY_TEX = 9;
	public static final int LIGHT_TEX = 10;
	public static final int CRUMBLING_TEX = 11;

	/** Bindings in the real descriptor set layout (0..11). */
	public static final int BINDING_COUNT = 12;

	/**
	 * Flywheel's OIT samplers only appear inside {@code #ifdef _FLW_OIT}, which this backend never
	 * defines; they still need a binding so the retarget can rewrite the disabled lines.
	 * They are not in the descriptor set layout.
	 */
	private static final int OIT_DEPTH_RANGE = 12;
	private static final int OIT_COEFFICIENTS = 13;
	private static final int OIT_NOISE = 14;

	/** Push-constant block (std430): mat4 model @0, mat3 normal @64 (3 x vec4), uvec2 material @112, uint baseVertex @120. */
	public static final int PUSH_MODEL = 0;
	public static final int PUSH_NORMAL = 64;
	public static final int PUSH_MATERIAL = 112;
	public static final int PUSH_BASE_VERTEX = 120;
	public static final int PUSH_SIZE = 124;

	public static final GlslRetarget.Bindings BINDINGS = new GlslRetarget.Bindings(
			Map.of("_FlwFrameUniforms", FRAME,
					"_FlwFogUniforms", FOG,
					"_FlwOptionsUniforms", OPTIONS,
					"_FlwPlayerUniforms", PLAYER,
					"_FlwLevelUniforms", LEVEL,
					"InstanceBuffer", INSTANCES,
					"LightLut", LIGHT_LUT,
					"LightSections", LIGHT_SECTIONS),
			Map.of("flw_diffuseTex", DIFFUSE_TEX,
					"flw_overlayTex", OVERLAY_TEX,
					"flw_lightTex", LIGHT_TEX,
					"_flw_crumblingTex", CRUMBLING_TEX,
					"_flw_depthRange", OIT_DEPTH_RANGE,
					"_flw_coefficients", OIT_COEFFICIENTS,
					"_flw_blueNoise", OIT_NOISE),
			// Same locations PipelineCompiler binds with bindAttribLocation.
			Map.of("_flw_aPos", 0,
					"_flw_aColor", 1,
					"_flw_aTexCoord", 2,
					"_flw_aOverlay", 3,
					"_flw_aLight", 4,
					"_flw_aNormal", 5));

	/**
	 * Flywheel's InternalVertex: 36-byte stride (FullVertexView.STRIDE). Per attribute: location,
	 * byte offset, VkFormat. Normal is three signed bytes plus a pad byte, read as RGBA8_SNORM
	 * (three-byte formats are optional for vertex buffers; the shader only reads xyz).
	 */
	public static final int VERTEX_STRIDE = 36;
	public static final int[][] VERTEX_ATTRIBUTES = {
			{0, 0, VK10.VK_FORMAT_R32G32B32_SFLOAT},  // VK_FORMAT_R32G32B32_SFLOAT  position
			{1, 12, VK10.VK_FORMAT_R8G8B8A8_UNORM},  // VK_FORMAT_R8G8B8A8_UNORM     color
			{2, 16, VK10.VK_FORMAT_R32G32_SFLOAT}, // VK_FORMAT_R32G32_SFLOAT      tex
			{3, 24, VK10.VK_FORMAT_R16G16_SSCALED},  // VK_FORMAT_R16G16_SSCALED     overlay (shorts -> float, like GL non-normalized)
			{4, 28, VK10.VK_FORMAT_R16G16_USCALED},  // VK_FORMAT_R16G16_USCALED     light (unsigned shorts -> float)
			{5, 32, VK10.VK_FORMAT_R8G8B8A8_SNORM},  // VK_FORMAT_R8G8B8A8_SNORM     normal
	};
}
