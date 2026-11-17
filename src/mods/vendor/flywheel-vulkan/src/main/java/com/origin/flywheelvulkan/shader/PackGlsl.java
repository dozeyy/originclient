package com.origin.flywheelvulkan.shader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splices Flywheel's retargeted vertex shader ({@link GlslRetarget}) in front of a shader pack's
 * vertex shader as Vulkairis prepares it for foreign draws ({@code ForeignPackPrograms}), so a Flywheel
 * instance runs through the pack's own gbuffers_block / shadow program.
 *
 * <p>The pack source calls {@code vulkairis_feed(out vec3 pos, out vec2 uv, out vec2 light, out vec4 color,
 * out vec3 normal)} first thing in its {@code main} and builds every pack attribute from those five values
 * (Vulkairis's {@code TerrainVertexAdapter.adaptForeign}). This class defines that function: it runs
 * Flywheel's whole vertex stage ({@code main} renamed {@code _flw_vkMain}, its varyings demoted to plain
 * globals) and hands over the camera-relative position, uv, lightmap coordinate (0..255, like chunk
 * vertices), colour and normal. Flywheel's own {@code #define}s are undone before the pack's code, so
 * neither side's macros reach the other.
 *
 * <p>Pure string logic, compiled for real with shaderc in {@code PackGlslTest}.
 */
public final class PackGlsl {
	private PackGlsl() {
	}

	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\b.*$");
	private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\b.*$");
	private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+(\\w+)");
	/** A retargeted vertex output: {@code layout(location = N) [interp] out T name[n];}. */
	private static final Pattern OUTPUT = Pattern.compile(
			"^(\\s*)layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*(?:(?:flat|smooth|noperspective|centroid)\\s+)*out\\s+(\\w+)\\s+(\\w+)\\s*(\\[\\s*\\d+\\s*])?\\s*;(.*)$");
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");

	/** Flywheel's vertex entry point after the splice. */
	public static final String FLYWHEEL_MAIN = "_flw_vkMain";

	/** The feed Vulkairis's adapted pack source declares and calls (parameter names are ours). */
	static final String FEED = """
			void vulkairis_feed(out vec3 _fv_pos, out vec2 _fv_uv, out vec2 _fv_light, out vec4 _fv_color, out vec3 _fv_normal) {
			    _flw_vkMain();
			    // flw_vertexPos is relative to Flywheel's render origin; flw_cameraPos is the camera relative to it.
			    _fv_pos = flw_vertexPos.xyz - flw_cameraPos;
			    _fv_uv = flw_vertexTexCoord;
			    _fv_light = flw_vertexLight * 256.0;
			    _fv_color = flw_vertexColor;
			    _fv_normal = flw_vertexNormal;
			}
			""";

	public static final class SpliceException extends Exception {
		public SpliceException(String message) {
			super(message);
		}
	}

	/**
	 * @param flywheelVertex the retargeted Flywheel vertex shader (GLSL 450, set 0, its own push block)
	 * @param packVertex     the pack's adapted vertex shader (descriptors already in another set)
	 * @return one vertex shader: version, both sides' extensions, Flywheel + feed, then the pack
	 */
	public static String splice(String flywheelVertex, String packVertex) throws SpliceException {
		if (flywheelVertex == null || packVertex == null) {
			throw new SpliceException("missing source");
		}
		String version = null;
		Set<String> extensions = new LinkedHashSet<>();
		Set<String> macros = new LinkedHashSet<>();

		List<String> flw = new ArrayList<>();
		for (String line : flywheelVertex.split("\r?\n", -1)) {
			if (VERSION.matcher(line).matches()) {
				flw.add("");
				continue;
			}
			if (EXTENSION.matcher(line).matches()) {
				extensions.add(line.trim());
				flw.add("");
				continue;
			}
			Matcher d = DEFINE.matcher(line);
			if (d.find()) {
				macros.add(d.group(1));
			}
			Matcher o = OUTPUT.matcher(line);
			if (o.matches()) {
				// Not an interface variable any more: the pack's own outputs are what the fragment reads.
				line = o.group(1) + o.group(2) + " " + o.group(3) + (o.group(4) == null ? "" : o.group(4)) + ";" + o.group(5);
			}
			flw.add(line);
		}
		String flwBody = String.join("\n", flw);
		Matcher main = MAIN.matcher(flwBody);
		if (!main.find()) {
			throw new SpliceException("Flywheel vertex has no main()");
		}
		flwBody = flwBody.substring(0, main.start()) + "void " + FLYWHEEL_MAIN + "()" + flwBody.substring(main.end());
		if (MAIN.matcher(flwBody).find()) {
			throw new SpliceException("Flywheel vertex has more than one main()");
		}

		List<String> pack = new ArrayList<>();
		for (String line : packVertex.split("\r?\n", -1)) {
			if (VERSION.matcher(line).matches()) {
				if (version == null) {
					version = line.trim();
				}
				pack.add("");
				continue;
			}
			if (EXTENSION.matcher(line).matches()) {
				extensions.add(line.trim());
				pack.add("");
				continue;
			}
			pack.add(line);
		}
		String packBody = String.join("\n", pack);
		if (!packBody.contains("vulkairis_feed(")) {
			throw new SpliceException("pack vertex does not call vulkairis_feed");
		}

		StringBuilder out = new StringBuilder(flwBody.length() + packBody.length() + 1024);
		out.append(version != null ? version : "#version 450").append('\n');
		for (String e : extensions) {
			out.append(e).append('\n');
		}
		out.append("\n// ---- Flywheel instance vertex (origin:vulkan) ----\n").append(flwBody).append('\n');
		out.append(FEED);
		for (String m : macros) {
			out.append("#undef ").append(m).append('\n');
		}
		out.append("\n// ---- shader pack vertex (Vulkairis) ----\n").append(packBody).append('\n');
		return out.toString();
	}
}
