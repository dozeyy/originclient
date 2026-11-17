package com.origin.flywheelvulkan.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

class PackGlslTest {
	/**
	 * The shape Vulkairis's ForeignPackPrograms hands over (TerrainVertexAdapter.adaptForeign +
	 * ForeignGlsl.moveToSet): pack attributes as globals, its descriptors in set 1, its varyings with
	 * locations, the feed prototype and a main that calls it. It also uses an identifier Flywheel
	 * defines as a macro (VERTEX_SHADER) and defines a macro Flywheel uses (flw_vertexPos): the
	 * splice must keep both sides' macros apart or this fails to compile.
	 */
	private static final String PACK = """
			#version 450 core
			#extension GL_ARB_shading_language_packing : enable
			#define flw_vertexPos broken_if_flywheel_sees_this
			layout(set = 1, std140, binding = 0) uniform VulkairisUniformBlock {
			    mat4 vulkairis_ModelViewMatrix;
			    mat4 vulkairis_ProjectionMatrix;
			    mat4 vulkairis_TextureMatrix[2];
			    vec3 cameraPosition;
			};
			layout(set = 1, binding = 1) uniform sampler2D gtexture;
			vec4 vulkairis_Vertex;
			vec4 vulkairis_Color;
			vec4 vulkairis_MultiTexCoord0;
			vec4 vulkairis_MultiTexCoord1;
			vec3 vulkairis_Normal;
			vec4 mc_Entity;
			layout(location = 0) out vec2 texcoord;
			layout(location = 1) out vec4 tint;
			layout(location = 2) out vec2 lmcoord;
			layout(location = 3) flat out vec3 normal;
			void vulkairis_packMain() {
			    float VERTEX_SHADER = 1.0;
			    texcoord = (vulkairis_TextureMatrix[0] * vulkairis_MultiTexCoord0).xy;
			    lmcoord = (vulkairis_TextureMatrix[1] * vulkairis_MultiTexCoord1).xy;
			    tint = vulkairis_Color * VERTEX_SHADER;
			    normal = vulkairis_Normal + mc_Entity.xyz * 0.0;
			    gl_Position = vulkairis_ProjectionMatrix * vulkairis_ModelViewMatrix * vulkairis_Vertex;
			}

			// Origin: vertex stream -> pack attributes.
			void vulkairis_feed(out vec3 pos, out vec2 uv, out vec2 light, out vec4 color, out vec3 normal);
			void main() {
			    vec3 vulkairis_tPos;
			    vec2 vulkairis_tUv;
			    vec2 vulkairis_tLight;
			    vec4 vulkairis_tColor;
			    vec3 vulkairis_tNormal;
			    vulkairis_feed(vulkairis_tPos, vulkairis_tUv, vulkairis_tLight, vulkairis_tColor, vulkairis_tNormal);
			    vulkairis_Vertex = vec4(vulkairis_tPos, 1.0);
			    vulkairis_Color = vulkairis_tColor;
			    vulkairis_MultiTexCoord0 = vec4(vulkairis_tUv, 0.0, 1.0);
			    vulkairis_MultiTexCoord1 = vec4(vulkairis_tLight, 0.0, 1.0);
			    vulkairis_Normal = vulkairis_tNormal;
			    mc_Entity = vec4(-1.0, -1.0, 0.0, 0.0);
			    vulkairis_packMain();
			    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
			}
			""";

	private static String flywheelVertex(String context) throws Exception {
		var vert = GlslRetargetTest.assemble("VERTEX_SHADER", context, false, List.of(
				"flywheel:internal/api_impl.vert", "@struct", "flywheel:instance/transformed.vert",
				"flywheel:material/default.vert", "flywheel:internal/vertex_input.vert", "@unpack",
				"origin_flywheel_vulkan:vulkan/main.vert"));
		var frag = GlslRetarget.retarget(vert, "void main(){}", VkLayout.BINDINGS);
		return frag.vertex();
	}

	/** Flywheel's real vertex stage (plain and embedded, i.e. contraptions/trains) spliced into a pack vertex compiles. */
	@Test
	void realFlywheelVertexSplicedIntoAPackCompiles() throws Exception {
		for (String context : new String[] {null, "FLW_EMBEDDED"}) {
			String out = PackGlsl.splice(flywheelVertex(context), PACK);
			GlslRetargetTest.compile(out, Shaderc.shaderc_glsl_vertex_shader, "pack splice " + context);
		}
	}

	@Test
	void spliceShape() throws Exception {
		String out = PackGlsl.splice(flywheelVertex(null), PACK);
		// The pack's version line wins, extensions are hoisted right under it.
		assertTrue(out.startsWith("#version 450 core\n#extension GL_ARB_shading_language_packing : enable\n"), out);
		assertEquals(1, count(out, "#version"), out);
		// One entry point (the pack's), Flywheel's main renamed and called from the feed.
		assertEquals(1, count(out, "void main()"), out);
		assertTrue(out.contains("void " + PackGlsl.FLYWHEEL_MAIN + "()"), out);
		// Flywheel's varyings are plain globals now; only the pack's 4 outputs remain interface variables.
		assertFalse(out.contains("out vec4 flw_vertexPos"), out);
		assertTrue(out.contains("\nvec4 flw_vertexPos;"), out);
		assertEquals(4, count(out, ") out ") + count(out, ") flat out "), out);
		// Flywheel's macros are undone before the pack code starts.
		int undef = out.indexOf("#undef VERTEX_SHADER");
		int packStart = out.indexOf("shader pack vertex");
		assertTrue(undef > 0 && undef < packStart, out);
		assertTrue(out.indexOf("void vulkairis_feed(out vec3 _fv_pos") < undef, out);
	}

	@Test
	void refusesWhatItCannotSplice() {
		assertThrows(PackGlsl.SpliceException.class, () -> PackGlsl.splice("#version 450\nvoid f(){}\n", PACK));
		assertThrows(PackGlsl.SpliceException.class, () -> PackGlsl.splice("#version 450\nvoid main(){}\n", "#version 450\nvoid main(){}\n"));
		assertThrows(PackGlsl.SpliceException.class, () -> PackGlsl.splice(null, PACK));
	}

	private static int count(String s, String needle) {
		int n = 0;
		for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
			n++;
		}
		return n;
	}
}
