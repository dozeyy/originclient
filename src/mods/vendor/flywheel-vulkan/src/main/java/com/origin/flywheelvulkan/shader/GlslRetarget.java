package com.origin.flywheelvulkan.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Flywheel's assembled OpenGL GLSL (one vertex + one fragment source) into GLSL 450 that
 * shaderc accepts for a Vulkan target. Pure text work, no GL or Vulkan calls (unit tested).
 *
 * <p>Why each step: VulkanMod's shaderc options (SPIRVUtils, checked with javap) set no
 * auto-map-locations or auto-map-bindings, so every interface variable needs an explicit
 * {@code layout(location)} and every resource an explicit {@code set/binding}. Vulkan GLSL also
 * has no loose (non-opaque) uniforms: those live in the push-constant block our own main
 * shaders declare, so any loose uniform that is still here is an error, not a silent drop.
 *
 * <ul>
 *   <li>{@code #version} becomes 450; {@code #line} and {@code #extension} lines are dropped
 *       (Flywheel's #line uses file ids that only mean something to its GL error printer).</li>
 *   <li>{@code gl_VertexID}/{@code gl_InstanceID} become {@code gl_VertexIndex}/{@code gl_InstanceIndex}:
 *       the Vulkan forms include vertexOffset/firstInstance, which is how the draw passes
 *       baseVertex and the instance region (flw_vertexId = gl_VertexIndex - baseVertex).</li>
 *   <li>Uniform and buffer blocks get {@code set = 0, binding = N} by block name; their old
 *       {@code binding = X} (often a GL macro) is removed.</li>
 *   <li>Samplers get {@code set = 0, binding = N} by name.</li>
 *   <li>Vertex inputs get the attribute location by name; vertex outputs get locations in
 *       declaration order and the fragment inputs reuse them by name; fragment outputs go to
 *       location 0 (one colour attachment; Flywheel's OIT outputs sit in disabled #ifdef branches).</li>
 *   <li>The conservative-depth {@code gl_FragDepth} redeclaration is dropped (an optimisation hint
 *       whose extension is gone).</li>
 * </ul>
 */
public final class GlslRetarget {
	private GlslRetarget() {
	}

	/** Binding tables. Block names (uniform and buffer), sampler names and attribute names. */
	public record Bindings(Map<String, Integer> blocks, Map<String, Integer> samplers, Map<String, Integer> attributes) {
	}

	public record Result(String vertex, String fragment, Map<String, Integer> varyings) {
	}

	public static final class RetargetException extends Exception {
		public RetargetException(String message) {
			super(message);
		}
	}

	private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\b.*$");
	private static final Pattern LINE = Pattern.compile("^\\s*#\\s*line\\b.*$");
	private static final Pattern EXTENSION = Pattern.compile("^\\s*#\\s*extension\\b.*$");
	private static final Pattern DEPTH_HINT = Pattern.compile("^\\s*layout\\s*\\(\\s*depth_\\w+\\s*\\)\\s*out\\s+float\\s+gl_FragDepth\\s*;.*$");
	private static final Pattern VERTEX_ID = Pattern.compile("\\bgl_VertexID\\b");
	private static final Pattern INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");
	/** {@code layout(...) uniform Name {} or {@code layout(...) <memory qualifiers> buffer Name}. */
	private static final Pattern BLOCK = Pattern.compile(
			"^(\\s*)layout\\s*\\(([^)]*)\\)\\s*((?:(?:restrict|readonly|writeonly|coherent|volatile)\\s+)*)(uniform|buffer)\\s+(\\w+)(.*)$");
	private static final Pattern BINDING_QUALIFIER = Pattern.compile("\\s*,?\\s*\\bbinding\\s*=\\s*\\w+\\s*,?\\s*");
	private static final Pattern OPAQUE_UNIFORM = Pattern.compile(
			"^(\\s*)uniform\\s+((?:lowp|mediump|highp)\\s+)?([iu]?(?:sampler|image|texture)\\w*)\\s+(\\w+)\\s*;(.*)$");
	private static final Pattern LOOSE_UNIFORM = Pattern.compile("^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\b(?!\\s*\\{).*$");
	private static final Pattern INTERFACE = Pattern.compile(
			"^(\\s*)((?:(?:flat|smooth|noperspective|centroid)\\s+)*)(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[\\s*\\d+\\s*])?\\s*;(.*)$");

	public static Result retarget(String vertex, String fragment, Bindings bindings) throws RetargetException {
		Map<String, Integer> varyings = new LinkedHashMap<>();
		String v = stage(vertex, true, bindings, varyings);
		String f = stage(fragment, false, bindings, varyings);
		return new Result(v, f, varyings);
	}

	private static String stage(String source, boolean vertex, Bindings b, Map<String, Integer> varyings) throws RetargetException {
		List<String> out = new ArrayList<>();
		out.add("#version 450");
		int depth = 0;
		int nextVarying = 0;
		for (int loc : varyings.values()) {
			nextVarying = Math.max(nextVarying, loc + 1);
		}

		for (String raw : source.split("\r?\n", -1)) {
			if (VERSION.matcher(raw).matches() || LINE.matcher(raw).matches() || EXTENSION.matcher(raw).matches()
					|| DEPTH_HINT.matcher(raw).matches()) {
				out.add("");
				continue;
			}
			String line = INSTANCE_ID.matcher(VERTEX_ID.matcher(raw).replaceAll("gl_VertexIndex")).replaceAll("gl_InstanceIndex");

			if (depth == 0 && !isPreprocessor(line)) {
				Matcher m;
				if ((m = BLOCK.matcher(line)).matches()) {
					String quals = m.group(2);
					if (!quals.contains("push_constant")) {
						Integer binding = b.blocks().get(m.group(5));
						if (binding == null) {
							throw new RetargetException("no binding for " + m.group(4) + " block '" + m.group(5) + "'");
						}
						String kept = BINDING_QUALIFIER.matcher(quals).replaceAll(",").replaceAll("^\\s*,|,\\s*$", "").trim();
						String layout = (kept.isEmpty() ? "" : kept + ", ") + "set = 0, binding = " + binding;
						line = m.group(1) + "layout(" + layout + ") " + m.group(3) + m.group(4) + " " + m.group(5) + m.group(6);
					}
				} else if ((m = OPAQUE_UNIFORM.matcher(line)).matches()) {
					Integer binding = b.samplers().get(m.group(4));
					if (binding == null) {
						throw new RetargetException("no binding for sampler '" + m.group(4) + "'");
					}
					line = m.group(1) + "layout(set = 0, binding = " + binding + ") uniform " + m.group(3) + " " + m.group(4) + ";" + m.group(5);
				} else if (LOOSE_UNIFORM.matcher(line).matches() && !line.contains("{")) {
					throw new RetargetException("loose uniform has no Vulkan home: " + line.trim());
				} else if ((m = INTERFACE.matcher(line)).matches()) {
					String dir = m.group(3);
					String name = m.group(5);
					int loc;
					if (vertex && dir.equals("in")) {
						Integer a = b.attributes().get(name);
						if (a == null) {
							throw new RetargetException("no location for vertex input '" + name + "'");
						}
						loc = a;
					} else if (vertex) {
						Integer known = varyings.get(name);
						if (known == null) {
							known = nextVarying;
							varyings.put(name, known);
							nextVarying += locationsFor(m.group(4), m.group(6));
						}
						loc = known;
					} else if (dir.equals("in")) {
						Integer known = varyings.get(name);
						if (known == null) {
							throw new RetargetException("fragment input '" + name + "' has no matching vertex output");
						}
						loc = known;
					} else {
						loc = 0;
					}
					line = m.group(1) + "layout(location = " + loc + ") " + m.group(2) + dir + " " + m.group(4) + " " + name
							+ (m.group(6) == null ? "" : m.group(6)) + ";" + m.group(7);
				}
			}

			depth += braceDelta(line);
			if (depth < 0) {
				throw new RetargetException("unbalanced braces");
			}
			out.add(line);
		}
		return String.join("\n", out);
	}

	private static boolean isPreprocessor(String line) {
		return line.stripLeading().startsWith("#");
	}

	/** Locations a varying of this type uses (matrices take one per column). */
	static int locationsFor(String type, String array) {
		int per = switch (type) {
			case "mat2", "dmat2" -> 2;
			case "mat3", "dmat3" -> 3;
			case "mat4", "dmat4" -> 4;
			default -> 1;
		};
		if (array != null) {
			per *= Integer.parseInt(array.replaceAll("[^0-9]", ""));
		}
		return per;
	}

	/** Brace depth change of one line, ignoring // comments (Flywheel has no braces inside block comments at top level). */
	static int braceDelta(String line) {
		int cut = line.indexOf("//");
		String code = cut >= 0 ? line.substring(0, cut) : line;
		int d = 0;
		for (int i = 0; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '{') {
				d++;
			} else if (c == '}') {
				d--;
			}
		}
		return d;
	}
}
