import com.origin.client.client.render.terrain.TerrainCuller;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Origin Render's GPU culler on a real OpenGL driver (Mesa llvmpipe under Xvfb
// in the sandbox, or any desktop GL). No Minecraft: TerrainCuller is plain
// LWJGL. The key property: a section is NEVER culled when any of it could be
// seen - checked against the GPU's own occlusion queries over random scenes.
//
// Context modes (run.py runs all three): "45" = a 4.5 core context; "mc" = the
// context Minecraft really gets on AMD/NVIDIA - 3.2 core forward-compatible,
// where the 4.3 features exist only as extensions (run.py caps Mesa at GL 3.2);
// "old" = also GLSL capped at 1.50, which supported() must refuse up front. The
// fixtures below therefore use only GL 3.2 calls themselves.
public class OriginRenderTests {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String what) {
		if (ok) {
			passed++;
		} else {
			failed++;
			System.out.println("FAIL: " + what);
		}
	}

	public static void main(String[] args) {
		String mode = args.length > 0 ? args[0] : "45";
		boolean mc = !mode.equals("45");
		if (!GLFW.glfwInit()) {
			throw new IllegalStateException("glfwInit failed (run under xvfb-run)");
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		// mc: exactly Minecraft's request (com.mojang.blaze3d.platform.Window)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, mc ? 3 : 4);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, mc ? 2 : 5);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, mc ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		long win = GLFW.glfwCreateWindow(64, 64, "origin-render-tests", 0, 0);
		if (win == 0) {
			throw new IllegalStateException("no GL " + (mc ? "3.2" : "4.5") + " context");
		}
		GLFW.glfwMakeContextCurrent(win);
		GL.createCapabilities();
		System.out.println("GL: " + GL11C.glGetString(GL11C.GL_VERSION) + " / " + GL11C.glGetString(GL11C.GL_RENDERER));
		var caps = GL.getCapabilities();
		if (mc) {
			check(!caps.OpenGL43, "mc context hides GL 4.3 (else this mode proves nothing)");
		}
		if (mode.equals("old")) {
			check(!TerrainCuller.supported(), "supported() refuses a driver without GLSL 4.30");
			System.out.println(passed + " passed, " + failed + " failed");
			GLFW.glfwTerminate();
			System.exit(failed == 0 ? 0 : 1);
		}
		check(TerrainCuller.supported(), "supported() on the " + (mc ? "Minecraft-like 3.2" : "4.5") + " context");

		TerrainCuller culler = new TerrainCuller(); // compiles all four shaders
		check(GL11C.glGetError() == 0, "no GL error after compile");

		hizMatchesReference(culler, 333, 197, 1);
		hizMatchesReference(culler, 1920, 1080, 2);
		hizMatchesReference(culler, 7, 3, 3);
		neverHidesVisible(culler, 320, 180, 42, 60, false);
		neverHidesVisible(culler, 397, 223, 7, 400, false);
		neverHidesVisible(culler, 211, 509, 99, 120, true);
		twoPhaseFlow(culler);
		stateRestored(culler);
		indirectDraws(culler);
		statsArrive(culler);

		culler.delete();
		check(GL11C.glGetError() == 0, "no GL error after delete");
		System.out.println(passed + " passed, " + failed + " failed");
		GLFW.glfwTerminate();
		System.exit(failed == 0 ? 0 : 1);
	}

	// ---- Hi-Z pyramid == CPU max-reduction ----

	static void hizMatchesReference(TerrainCuller culler, int w, int h, long seed) {
		Random r = new Random(seed);
		float[] depth = new float[w * h];
		for (int i = 0; i < depth.length; i++) {
			depth[i] = r.nextFloat();
		}
		int tex = texture(GL30C.GL_DEPTH_COMPONENT32F, w, h);
		depthData(tex, w, h, depth);
		culler.buildHiZ(tex, w, h);
		int hizTex = field(culler, "hizTex"), hw = field(culler, "hizW"), hh = field(culler, "hizH"), levels = field(culler, "hizLevels");
		check(hw == Math.max(1, Integer.highestOneBit(Math.max(1, w / 2))) && hh == Math.max(1, Integer.highestOneBit(Math.max(1, h / 2))),
				"pyramid level 0 is pow2 <= half screen (" + w + "x" + h + " -> " + hw + "x" + hh + ")");
		float[] prev = null;
		int pw = 0, ph = 0;
		boolean ok = true;
		for (int l = 0; l < levels; l++) {
			int lw = Math.max(1, hw >> l), lh = Math.max(1, hh >> l);
			float[] got = new float[lw * lh];
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hizTex);
			GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, l, GL11C.GL_RED, GL11C.GL_FLOAT, got);
			GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
			for (int y = 0; y < lh && ok; y++) {
				for (int x = 0; x < lw && ok; x++) {
					float want = 0;
					if (l == 0) {
						int x0 = x * w / lw, x1 = Math.min(((x + 1) * w + lw - 1) / lw, w);
						int y0 = y * h / lh, y1 = Math.min(((y + 1) * h + lh - 1) / lh, h);
						for (int yy = y0; yy < y1; yy++) {
							for (int xx = x0; xx < x1; xx++) {
								want = Math.max(want, depth[yy * w + xx]);
							}
						}
					} else {
						for (int dy = 0; dy < 2; dy++) {
							for (int dx = 0; dx < 2; dx++) {
								int sx = Math.min(2 * x + dx, pw - 1), sy = Math.min(2 * y + dy, ph - 1);
								want = Math.max(want, prev[sy * pw + sx]);
							}
						}
					}
					if (got[y * lw + x] != want) {
						ok = false;
						System.out.println("  level " + l + " (" + x + "," + y + ") got " + got[y * lw + x] + " want " + want);
					}
				}
			}
			prev = got;
			pw = lw;
			ph = lh;
		}
		check(ok, "Hi-Z equals CPU max-reduction at " + w + "x" + h + " (" + levels + " levels)");
		check(GL11C.glGetError() == 0, "no GL error building Hi-Z " + w + "x" + h);
		GL11C.glDeleteTextures(tex);
	}

	// ---- property: culled => zero samples visible (GPU occlusion query) ----

	static int W, H;

	/**
	 * reach = how deep the scene goes (blocks); edges = place sections hugging
	 * occluder edges (the hardest cases for a conservative test).
	 */
	static void neverHidesVisible(TerrainCuller culler, int w, int h, long seed, int reach, boolean edges) {
		W = w;
		H = h;
		int fbo = GL30C.glGenFramebuffers();
		int color = texture(GL11C.GL_RGBA8, W, H);
		int depth = texture(GL30C.GL_DEPTH_COMPONENT24, W, H);
		attach(fbo, GL30C.GL_COLOR_ATTACHMENT0, color);
		attach(fbo, GL30C.GL_DEPTH_ATTACHMENT, depth);
		int prog = boxProgram();
		int vbo = GL15C.glGenBuffers();
		int vao = vertexArray(vbo, 0);
		int query = GL15C.glGenQueries();

		Random r = new Random(seed);
		int totalCulled = 0, totalTested = 0, wrong = 0;
		for (int scene = 0; scene < 40; scene++) {
			Matrix4f vp = randomCamera(r, Math.max(512f, reach * 5f));
			// occluders: random walls/blocks in front of the camera
			List<float[]> occ = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				float x = r.nextFloat() * 160 - 80, y = r.nextFloat() * 60 - 30, z = -(8 + r.nextFloat() * reach);
				occ.add(new float[]{x, y, z, x + 8 + r.nextFloat() * 70, y + 8 + r.nextFloat() * 50, z + 1 + r.nextFloat() * 20});
			}
			GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
			GL11C.glViewport(0, 0, W, H);
			GL11C.glClearDepth(1.0);
			GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_COLOR_BUFFER_BIT);
			GL11C.glEnable(GL11C.GL_DEPTH_TEST);
			GL11C.glDepthFunc(GL11C.GL_LEQUAL);
			GL11C.glDepthMask(true);
			GL20C.glUseProgram(prog);
			setMatrix(prog, vp);
			GL30C.glBindVertexArray(vao);
			for (float[] b : occ) {
				drawBox(vbo, b);
			}
			// sections: a grid of 16^3 boxes behind / among the occluders
			culler.reset();
			List<float[]> secs = new ArrayList<>();
			for (int i = 0; i < 300; i++) {
				float x, y, z;
				if (edges) {
					// just behind an occluder, straddling its edge by up to a few blocks
					float[] o = occ.get(r.nextInt(occ.size()));
					x = (r.nextBoolean() ? o[0] - 16 : o[3]) + (r.nextFloat() - 0.5f) * 6;
					y = o[1] + r.nextFloat() * (o[4] - o[1]);
					// behind it, or just in front of its near face (must never be hidden)
					z = r.nextBoolean() ? o[2] - 16 - r.nextFloat() * 30 : o[5] + r.nextFloat() * 4 - 1;
				} else {
					x = (r.nextInt(24) - 12) * 16;
					y = (r.nextInt(8) - 4) * 16;
					z = -(r.nextInt(Math.max(2, reach / 4)) * 16 + 4);
				}
				secs.add(new float[]{x, y, z});
				culler.addSection(x, y, z, i);
				culler.addCommand(6, 0);
			}
			culler.upload();
			culler.buildHiZ(depth, W, H);
			culler.phaseB(vp, false);
			int[] cmds = readCommands(culler.indirectBuffer(false), culler.commandCount());
			// truth: draw each culled section's full 18^3 margin box with depth test, no writes
			GL20C.glUseProgram(prog);
			GL30C.glBindVertexArray(vao);
			GL11C.glDepthMask(false);
			GL11C.glDepthFunc(GL11C.GL_LESS);
			for (int i = 0; i < secs.size(); i++) {
				totalTested++;
				if (cmds[i * 5 + 1] != 0) {
					continue;
				}
				totalCulled++;
				float[] s = secs.get(i);
				GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, query);
				drawBox(vbo, new float[]{s[0] - 1, s[1] - 1, s[2] - 1, s[0] + 17, s[1] + 17, s[2] + 17});
				GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);
				if (GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT) != 0) {
					wrong++;
				}
			}
			GL11C.glDepthMask(true);
		}
		System.out.println("  property test " + w + "x" + h + (edges ? " edges" : "") + ", reach " + reach + ": culled " + totalCulled + " of " + totalTested + " sections, wrongly hidden: " + wrong);
		check(wrong == 0, "no section culled while any of it is visible (" + wrong + " wrong)");
		check(totalCulled > totalTested / 20, "the culler actually culls (" + totalCulled + "/" + totalTested + ")");
		check(GL11C.glGetError() == 0, "no GL error in property test");
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
		GL30C.glBindVertexArray(0);
		GL20C.glUseProgram(0);
		GL15C.glDeleteQueries(query);
	}

	/** Perspective * small "view bobbing" affine * random look direction, like Minecraft's. */
	static Matrix4f randomCamera(Random r, float far) {
		Matrix4f p = new Matrix4f().perspective((float) Math.toRadians(60 + r.nextInt(50)), W / (float) H, 0.05f, far);
		Matrix4f bob = new Matrix4f().translate((r.nextFloat() - 0.5f) * 0.2f, (r.nextFloat() - 0.5f) * 0.2f, 0)
				.rotateZ((r.nextFloat() - 0.5f) * 0.1f).rotateX((r.nextFloat() - 0.5f) * 0.1f);
		Matrix4f view = new Matrix4f().rotateX((r.nextFloat() - 0.5f) * 0.6f).rotateY((r.nextFloat() - 0.5f) * 0.8f);
		return p.mul(bob).mul(view);
	}

	// ---- two-phase: hints carry visibility to the next frame ----

	static void twoPhaseFlow(TerrainCuller culler) {
		int depth = texture(GL30C.GL_DEPTH_COMPONENT32F, 64, 64);
		float[] far = new float[64 * 64];
		java.util.Arrays.fill(far, 1f);
		depthData(depth, 64, 64, far);
		Matrix4f vp = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.05f, 512f);
		int[] slots = {7, 300_000, 1_048_575};
		for (int frame = 0; frame < 2; frame++) {
			culler.reset();
			for (int i = 0; i < 3; i++) {
				culler.addSection(-8 + i * 2, -8, -40 - i * 16, slots[i]);
				culler.addCommand(600 + i, 1000 * i);
				culler.addCommand(60 + i, 1000 * i + 400);
			}
			culler.upload();
			culler.phaseA();
			int[] a = readCommands(culler.indirectBuffer(true), culler.commandCount());
			culler.buildHiZ(depth, 64, 64);
			culler.phaseB(vp, true);
			int[] b = readCommands(culler.indirectBuffer(false), culler.commandCount());
			boolean firstFrame = frame == 0;
			boolean shape = true, aDraw = true, bDraw = true;
			for (int c = 0; c < 6; c++) {
				int i = c / 2;
				int wantCount = (c % 2 == 0 ? 600 : 60) + i, wantBase = 1000 * i + (c % 2 == 0 ? 0 : 400);
				shape &= a[c * 5] == wantCount && a[c * 5 + 3] == wantBase && b[c * 5] == wantCount && b[c * 5 + 3] == wantBase
						&& a[c * 5 + 2] == 0 && a[c * 5 + 4] == 0;
				aDraw &= a[c * 5 + 1] == (firstFrame ? 0 : 1);
				bDraw &= b[c * 5 + 1] == (firstFrame ? 1 : 0);
			}
			check(shape, "frame " + frame + ": commands keep count/baseVertex, firstIndex 0");
			check(aDraw, "frame " + frame + ": phase A draws " + (firstFrame ? "nothing (no history)" : "last frame's visible set"));
			check(bDraw, "frame " + frame + ": phase B draws " + (firstFrame ? "everything visible" : "nothing twice"));
		}
		check(GL11C.glGetError() == 0, "no GL error in two-phase flow");
		GL11C.glDeleteTextures(depth);
	}

	// ---- compute work leaves Minecraft/Sodium-visible state alone ----

	static void stateRestored(TerrainCuller culler) {
		int prog = boxProgram();
		GL20C.glUseProgram(prog);
		GL13();
		int depth = texture(GL30C.GL_DEPTH_COMPONENT32F, 32, 32);
		culler.reset();
		culler.addSection(0, 0, -40, 1);
		culler.addCommand(6, 0);
		culler.upload();
		culler.phaseA();
		culler.buildHiZ(depth, 32, 32);
		culler.phaseB(new Matrix4f().perspective(1f, 1f, 0.05f, 100f), true);
		check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == prog, "current program restored");
		check(GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING) == 0 && GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING) == 0,
				"no array / indirect buffer left bound");
		check(GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING) == 0, "no SSBO left bound");
		check(GL11C.glGetInteger(GL13_ACTIVE_TEXTURE) == GL13_TEXTURE0 + 3, "active texture unit untouched");
		boolean unitsClear = true;
		for (int u : new int[]{14, 15}) {
			GL13C.glActiveTexture(GL13_TEXTURE0 + u);
			unitsClear &= GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D) == 0 && GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) == 0;
		}
		GL13C.glActiveTexture(GL13_TEXTURE0);
		check(unitsClear, "texture units 14/15 and their samplers unbound");
		check(GL11C.glGetError() == 0, "no GL error in state test");
		GL20C.glUseProgram(0);
		GL20C.glDeleteProgram(prog);
		GL11C.glDeleteTextures(depth);
	}

	static final int GL13_ACTIVE_TEXTURE = 0x84E0, GL13_TEXTURE0 = 0x84C0;

	static void GL13() {
		GL13C.glActiveTexture(GL13_TEXTURE0 + 3);
	}

	// ---- the buffers really drive glMultiDrawElementsIndirect ----

	static void indirectDraws(TerrainCuller culler) {
		int fbo = GL30C.glGenFramebuffers();
		int color = texture(GL11C.GL_RGBA8, 16, 16);
		attach(fbo, GL30C.GL_COLOR_ATTACHMENT0, color);
		int depth = texture(GL30C.GL_DEPTH_COMPONENT32F, 16, 16);
		float[] far = new float[256];
		java.util.Arrays.fill(far, 1f);
		depthData(depth, 16, 16, far);
		// two quads (Sodium-style: 4 vertices each, shared index buffer 0,1,2,2,3,0), full screen
		float[] verts = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
		int vbo = GL15C.glGenBuffers();
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
		GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, verts, GL15C.GL_STATIC_DRAW);
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
		int ibo = GL15C.glGenBuffers();
		int vao = vertexArray(vbo, ibo);
		GL30C.glBindVertexArray(vao);
		GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 2, 3, 0}, GL15C.GL_STATIC_DRAW);
		GL30C.glBindVertexArray(0);
		int prog = program("""
				#version 150 core
				in vec3 p;
				void main() { gl_Position = vec4(p, 1.0); }
				""", """
				#version 150 core
				out vec4 c;
				void main() { c = vec4(1.0); }
				""");
		culler.reset();
		culler.addSection(0, 0, -40, 9);
		culler.addCommand(6, 4); // second quad via baseVertex
		culler.upload();
		culler.buildHiZ(depth, 16, 16);
		culler.phaseB(new Matrix4f().perspective(1f, 1f, 0.05f, 100f), false);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
		GL11C.glViewport(0, 0, 16, 16);
		GL11C.glDisable(GL11C.GL_DEPTH_TEST);
		GL11C.glClearColor(0, 0, 0, 0);
		GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
		GL20C.glUseProgram(prog);
		GL30C.glBindVertexArray(vao);
		GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, culler.indirectBuffer(false));
		GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0, culler.commandCount(), TerrainCuller.COMMAND_BYTES);
		GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, 0);
		ByteBuffer px = MemoryUtil.memAlloc(16 * 16 * 4);
		GL11C.glReadPixels(8, 8, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, px);
		check((px.get(0) & 0xFF) == 255, "glMultiDrawElementsIndirect draws the visible section");
		MemoryUtil.memFree(px);
		check(GL11C.glGetError() == 0, "no GL error drawing indirect");
		GL30C.glBindVertexArray(0);
		GL20C.glUseProgram(0);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
	}

	static void statsArrive(TerrainCuller culler) {
		int depth = texture(GL30C.GL_DEPTH_COMPONENT32F, 16, 16);
		float[] far = new float[256];
		java.util.Arrays.fill(far, 1f);
		depthData(depth, 16, 16, far);
		int[] got = null;
		for (int frame = 0; frame < 6 && got == null; frame++) {
			culler.beginFrameStats();
			got = culler.lastStats();
			culler.reset();
			for (int i = 0; i < 5; i++) {
				culler.addSection(-8, -8, -40 - 16 * i, 100 + i);
				culler.addCommand(6, 0);
			}
			culler.upload();
			culler.phaseA();
			culler.buildHiZ(depth, 16, 16);
			culler.phaseB(new Matrix4f().perspective(1f, 1f, 0.05f, 400f), true);
			culler.endFrameStats();
			GL11C.glFinish();
		}
		check(got != null && got[0] == 5 && got[1] == 5, "stats read back (tested 5, visible 5): "
				+ (got == null ? "none" : got[0] + "/" + got[1]));
		GL11C.glDeleteTextures(depth);
	}

	// ---- tiny GL helpers ----

	static int boxProgram() {
		return program("""
				#version 150 core
				in vec3 p;
				uniform mat4 u_vp;
				void main() { gl_Position = u_vp * vec4(p, 1.0); }
				""", """
				#version 150 core
				out vec4 c;
				void main() { c = vec4(1.0); }
				""");
	}

	/** A one-level 2D texture (complete for texelFetch: no mipmaps expected), left unbound. */
	static int texture(int internalFormat, int w, int h) {
		boolean depth = internalFormat == GL30C.GL_DEPTH_COMPONENT32F || internalFormat == GL30C.GL_DEPTH_COMPONENT24;
		int t = GL11C.glGenTextures();
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
		GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, w, h, 0,
				depth ? GL11C.GL_DEPTH_COMPONENT : GL11C.GL_RGBA, depth ? GL11C.GL_FLOAT : GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
		GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
		return t;
	}

	static void depthData(int t, int w, int h, float[] data) {
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
		GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, data);
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
	}

	static void attach(int fbo, int attachment, int tex) {
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
		GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, attachment, GL11C.GL_TEXTURE_2D, tex, 0);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
	}

	/** vec3 positions at attribute 0 from vbo; ibo (0 = none) recorded as its element buffer. */
	static int vertexArray(int vbo, int ibo) {
		int vao = GL30C.glGenVertexArrays();
		GL30C.glBindVertexArray(vao);
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
		GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 12, 0L);
		GL20C.glEnableVertexAttribArray(0);
		if (ibo != 0) {
			GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo);
		}
		GL30C.glBindVertexArray(0);
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
		return vao;
	}

	static void setMatrix(int prog, Matrix4f m) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			FloatBuffer f = s.mallocFloat(16);
			m.get(f);
			GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(prog, "u_vp"), false, f); // prog is in use
		}
	}

	/** Solid box (12 triangles) from {minX,minY,minZ,maxX,maxY,maxZ}. */
	static void drawBox(int vbo, float[] b) {
		float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
		float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
		int[] idx = {0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4, 0, 1, 5, 5, 4, 0, 3, 2, 6, 6, 7, 3, 0, 3, 7, 7, 4, 0, 1, 2, 6, 6, 5, 1};
		float[] v = new float[idx.length * 3];
		for (int i = 0; i < idx.length; i++) {
			v[i * 3] = c[idx[i]][0];
			v[i * 3 + 1] = c[idx[i]][1];
			v[i * 3 + 2] = c[idx[i]][2];
		}
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
		GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, v, GL15C.GL_STREAM_DRAW);
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, idx.length);
	}

	static int[] readCommands(int buf, int count) {
		int[] out = new int[count * 5];
		GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buf);
		GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, 0, out);
		GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
		return out;
	}

	static int program(String vs, String fs) {
		int p = GL20C.glCreateProgram();
		for (String[] s : new String[][]{{vs, "v"}, {fs, "f"}}) {
			int sh = GL20C.glCreateShader(s[1].equals("v") ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER);
			GL20C.glShaderSource(sh, s[0]);
			GL20C.glCompileShader(sh);
			if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == 0) {
				throw new IllegalStateException(GL20C.glGetShaderInfoLog(sh));
			}
			GL20C.glAttachShader(p, sh);
		}
		GL20C.glBindAttribLocation(p, 0, "p");
		GL20C.glLinkProgram(p);
		if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
			throw new IllegalStateException(GL20C.glGetProgramInfoLog(p));
		}
		return p;
	}

	static int field(Object o, String name) {
		try {
			var f = o.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.getInt(o);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}
}
