package com.origin.flywheelvulkan.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

class GlslRetargetTest {
	private static final GlslRetarget.Bindings B = VkLayout.BINDINGS;

	@Test
	void versionLineAndExtensionsAreReplaced() throws Exception {
		var r = GlslRetarget.retarget("#version 330 core\n#extension GL_ARB_x : require\n#line 0 1 // a\nvoid main(){}\n",
				"#version 330\nvoid main(){}\n", B);
		assertTrue(r.vertex().startsWith("#version 450\n"));
		assertFalse(r.vertex().contains("#version 330"));
		assertFalse(r.vertex().contains("#extension"));
		assertFalse(r.vertex().contains("#line"));
	}

	@Test
	void builtinIdsBecomeVulkanIndices() throws Exception {
		var r = GlslRetarget.retarget("void main(){ int a = gl_VertexID; int b = gl_InstanceID; int c = my_gl_VertexIDx; }",
				"void main(){}", B);
		assertTrue(r.vertex().contains("int a = gl_VertexIndex;"));
		assertTrue(r.vertex().contains("int b = gl_InstanceIndex;"));
		assertTrue(r.vertex().contains("my_gl_VertexIDx"), "only whole words are renamed");
	}

	@Test
	void blocksGetSetAndBindingAndLoseTheGlBinding() throws Exception {
		var r = GlslRetarget.retarget("""
				layout(std140) uniform _FlwFrameUniforms {
				    mat4 flw_view;
				};
				layout(std430, binding = _FLW_LIGHT_LUT_BUFFER_BINDING) restrict readonly buffer LightLut {
				    uint _flw_lightLut[];
				};
				layout (std140) uniform _FlwPlayerUniforms {
				    vec4 x;
				};
				""", "void main(){}", B);
		assertTrue(r.vertex().contains("layout(std140, set = 0, binding = 0) uniform _FlwFrameUniforms {"), r.vertex());
		assertTrue(r.vertex().contains("layout(std430, set = 0, binding = 6) restrict readonly buffer LightLut {"), r.vertex());
		assertTrue(r.vertex().contains("layout(std140, set = 0, binding = 3) uniform _FlwPlayerUniforms {"), r.vertex());
		assertFalse(r.vertex().contains("_FLW_LIGHT_LUT_BUFFER_BINDING)"));
	}

	@Test
	void pushConstantBlocksAreLeftAlone() throws Exception {
		String pc = "layout(push_constant) uniform _FlwVulkanPush {\n    uint baseVertex;\n} _flw_push;";
		var r = GlslRetarget.retarget(pc, "void main(){}", B);
		assertTrue(r.vertex().contains("layout(push_constant) uniform _FlwVulkanPush {"));
	}

	@Test
	void samplersGetBindings() throws Exception {
		var r = GlslRetarget.retarget("void main(){}", "uniform sampler2D flw_diffuseTex;\nuniform sampler2D _flw_crumblingTex;\n", B);
		assertTrue(r.fragment().contains("layout(set = 0, binding = 8) uniform sampler2D flw_diffuseTex;"));
		assertTrue(r.fragment().contains("layout(set = 0, binding = 11) uniform sampler2D _flw_crumblingTex;"));
	}

	@Test
	void varyingsMatchByNameAcrossStages() throws Exception {
		var r = GlslRetarget.retarget("""
				in vec3 _flw_aPos;
				in vec3 _flw_aNormal;
				out vec4 flw_vertexPos;
				flat out ivec2 flw_vertexOverlay;
				out mat3 someMatrix;
				out float flw_distance;
				void main() {}
				""", """
				in float flw_distance;
				flat in ivec2 flw_vertexOverlay;
				in vec4 flw_vertexPos;
				out vec4 _flw_outputColor;
				void main() {}
				""", B);
		assertTrue(r.vertex().contains("layout(location = 0) in vec3 _flw_aPos;"));
		assertTrue(r.vertex().contains("layout(location = 5) in vec3 _flw_aNormal;"));
		assertTrue(r.vertex().contains("layout(location = 0) out vec4 flw_vertexPos;"));
		assertTrue(r.vertex().contains("layout(location = 1) flat out ivec2 flw_vertexOverlay;"));
		assertTrue(r.vertex().contains("layout(location = 2) out mat3 someMatrix;"));
		// mat3 takes three locations
		assertTrue(r.vertex().contains("layout(location = 5) out float flw_distance;"));
		assertTrue(r.fragment().contains("layout(location = 5) in float flw_distance;"));
		assertTrue(r.fragment().contains("layout(location = 1) flat in ivec2 flw_vertexOverlay;"));
		assertTrue(r.fragment().contains("layout(location = 0) out vec4 _flw_outputColor;"));
	}

	@Test
	void functionParametersAndLocalsAreNotInterfaceVariables() throws Exception {
		var r = GlslRetarget.retarget("""
				void f(in vec3 a, out float b) {
				    b = a.x;
				}
				void g() {
				    float x;
				}
				void main() {}
				""", "void main(){}", B);
		assertFalse(r.vertex().contains("layout(location"), r.vertex());
	}

	@Test
	void unknownResourcesFailInsteadOfGuessing() {
		assertThrows(GlslRetarget.RetargetException.class,
				() -> GlslRetarget.retarget("uniform uint _flw_baseVertex;\nvoid main(){}", "void main(){}", B));
		assertThrows(GlslRetarget.RetargetException.class,
				() -> GlslRetarget.retarget("void main(){}", "uniform sampler2D mystery;\nvoid main(){}", B));
		assertThrows(GlslRetarget.RetargetException.class,
				() -> GlslRetarget.retarget("layout(std140) uniform Mystery {\n float a;\n};\nvoid main(){}", "void main(){}", B));
		assertThrows(GlslRetarget.RetargetException.class,
				() -> GlslRetarget.retarget("void main(){}", "in vec4 neverWritten;\nvoid main(){}", B));
		assertThrows(GlslRetarget.RetargetException.class,
				() -> GlslRetarget.retarget("in vec4 _flw_aMystery;\nvoid main(){}", "void main(){}", B));
	}

	@Test
	void conservativeDepthRedeclarationIsDropped() throws Exception {
		var r = GlslRetarget.retarget("void main(){}", "layout (depth_greater) out float gl_FragDepth;\nvoid main(){}", B);
		assertFalse(r.fragment().contains("gl_FragDepth"));
	}

	// ---- The real thing: Flywheel 1.0.6's own GLSL, assembled the way VkShaders does, retargeted, compiled by shaderc. ----

	/** Every context the backend compiles (EMBEDDED = contraptions/trains, CRUMBLING = block breaking). */
	@Test
	void realFlywheelSourcesCompileToSpirvInEveryContext() throws Exception {
		for (String context : new String[] {null, "FLW_EMBEDDED", "_FLW_CRUMBLING"}) {
			for (boolean cutout : new boolean[] {false, true}) {
				var vert = assemble("VERTEX_SHADER", context, cutout, List.of(
						"flywheel:internal/api_impl.vert", "@struct", "flywheel:instance/transformed.vert",
						"flywheel:material/default.vert", "flywheel:internal/vertex_input.vert", "@unpack",
						"origin_flywheel_vulkan:vulkan/main.vert"));
				var frag = assemble("FRAGMENT_SHADER", context, cutout, List.of(
						"flywheel:internal/api_impl.frag", "flywheel:material/default.frag",
						"flywheel:internal/components_header.frag", "flywheel:fog/linear.glsl",
						"flywheel:light/smooth_when_embedded.glsl",
						cutout ? "flywheel:cutout/epsilon.glsl" : "flywheel:cutout/off.glsl",
						"origin_flywheel_vulkan:vulkan/main.frag"));
				var r = GlslRetarget.retarget(vert, frag, B);
				String tag = context + (cutout ? "+cutout" : "");
				compile(r.vertex(), Shaderc.shaderc_glsl_vertex_shader, "vert " + tag);
				compile(r.fragment(), Shaderc.shaderc_glsl_fragment_shader, "frag " + tag);
			}
		}
	}

	private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

	/** Same order and de-duplication as Flywheel's ShaderCache.expand (includes depth first, once each). */
	static String assemble(String stageDefine, String context, boolean cutout, List<String> roots) throws IOException {
		StringBuilder sb = new StringBuilder("#version 450\n#define ").append(stageDefine).append('\n');
		if (context != null) {
			sb.append("#define ").append(context).append('\n');
		}
		sb.append("#define _FLW_LIGHT_SMOOTHNESS 1\n");
		if (cutout && stageDefine.startsWith("FRAG")) {
			sb.append("#define _FLW_USE_DISCARD\n");
		}
		Set<String> done = new LinkedHashSet<>();
		for (String root : roots) {
			if (root.equals("@struct")) {
				sb.append(TRANSFORMED_STRUCT);
			} else if (root.equals("@unpack")) {
				sb.append(TRANSFORMED_SSBO_UNPACK);
			} else {
				expand(root, done, sb);
			}
		}
		return sb.toString();
	}

	private static void expand(String loc, Set<String> done, StringBuilder sb) throws IOException {
		if (done.contains(loc)) {
			return;
		}
		String src = read(loc);
		Matcher m = INCLUDE.matcher(src);
		while (m.find()) {
			String inc = m.group(1).contains(":") ? m.group(1) : "flywheel:" + m.group(1);
			expand(inc, done, sb);
		}
		if (done.add(loc)) {
			sb.append('\n').append(INCLUDE.matcher(src).replaceAll("")).append('\n');
		}
	}

	private static String read(String loc) throws IOException {
		String ns = loc.substring(0, loc.indexOf(':'));
		String path = loc.substring(loc.indexOf(':') + 1);
		String entry = "assets/" + ns + "/flywheel/" + path;
		if (ns.equals("origin_flywheel_vulkan")) {
			return Files.readString(Path.of(System.getProperty("flywheelvulkan.resources")).resolve(entry));
		}
		try (var zip = new ZipFile(System.getProperty("flywheel.jar"))) {
			var e = zip.getEntry(entry);
			if (e == null) {
				throw new IOException("not in the Flywheel jar: " + entry);
			}
			return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** What InstanceStructComponent generates for InstanceTypes.TRANSFORMED (color, overlay, light, pose). */
	private static final String TRANSFORMED_STRUCT = """
			struct FlwInstance {
			    vec4 color;
			    ivec2 overlay;
			    vec2 light;
			    mat4 pose;
			};
			""";

	/** Shape of SsboInstanceComponent's output for TRANSFORMED (21 uints per instance). */
	private static final String TRANSFORMED_SSBO_UNPACK = """
			layout(std430, binding = 1) restrict readonly buffer InstanceBuffer {
			    uint _flw_instances[];
			};
			FlwInstance _flw_unpackInstance(uint index) {
			    uint base = index * 21u;
			    uint u0 = _flw_instances[base + 0u];
			    uint u1 = _flw_instances[base + 1u];
			    uint u2 = _flw_instances[base + 2u];
			    mat4 p;
			    for (int i = 0; i < 16; i++) { p[i / 4][i % 4] = uintBitsToFloat(_flw_instances[base + 3u + uint(i)]); }
			    return FlwInstance(unpackUnorm4x8(u0), ivec2(int(u1 << 16) >> 16, int(u1) >> 16), vec2(u2 & 0xFFFFu, u2 >> 16), p);
			}
			""";

	static void compile(String source, int kind, String what) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			// Same target as VulkanMod's SPIRVUtils (javap: set_target_env(options, vulkan, 4202496 = VK_API_VERSION_1_2)).
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, 4202496);
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, what, "main", options);
			try {
				int status = Shaderc.shaderc_result_get_compilation_status(result);
				if (status != Shaderc.shaderc_compilation_status_success) {
					String err = Shaderc.shaderc_result_get_error_message(result);
					throw new AssertionError(what + " failed to compile:\n" + err + "\n----\n" + numbered(source, err));
				}
				assertTrue(Shaderc.shaderc_result_get_length(result) > 0, what + ": empty SPIR-V");
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** The source lines around the first error, numbered, so a failing run shows what shaderc saw. */
	private static String numbered(String src, String err) {
		var m = Pattern.compile(":(\\d+):").matcher(err == null ? "" : err);
		int at = m.find() ? Integer.parseInt(m.group(1)) : 1;
		String[] lines = src.split("\n", -1);
		StringBuilder sb = new StringBuilder();
		for (int i = Math.max(0, at - 6); i < Math.min(lines.length, at + 4); i++) {
			sb.append(i + 1).append(": ").append(lines[i]).append('\n');
		}
		return sb.toString();
	}

	@Test
	void layoutTablesAgree() {
		assertEquals(VkLayout.BINDING_COUNT, 12);
		assertEquals(VkLayout.PUSH_BASE_VERTEX + 4, VkLayout.PUSH_SIZE);
		assertTrue(VkLayout.PUSH_SIZE <= 128, "128 bytes is the push-constant size every Vulkan device guarantees");
		assertEquals(VkLayout.VERTEX_STRIDE, 36);
		assertEquals(6, VkLayout.VERTEX_ATTRIBUTES.length);
		for (int[] a : VkLayout.VERTEX_ATTRIBUTES) {
			assertEquals(a[0], VkLayout.BINDINGS.attributes().values().stream().filter(v -> v == a[0]).findFirst().orElseThrow());
		}
	}
}
