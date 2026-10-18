package com.origin.client.client.render.terrain;

import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

// Origin Render's GPU half: hierarchical-Z occlusion culling of terrain sections,
// done entirely on the graphics card, writing the indirect draw commands the
// terrain is then drawn with (glMultiDrawElementsIndirect).
//
// How a pass works (OriginRender drives it; this class knows nothing about
// Minecraft or Sodium, so tools/origin-render-tests runs it on a real GL 4.5
// driver):
//   1. The caller lists every section Sodium already decided to draw (frustum +
//      cave culling, done on the CPU as usual) with its draw commands - addSection
//      / addCommand - then upload().
//   2. Two-phase (the opaque pass): phaseA() keeps every section that was visible
//      LAST frame and those get drawn; buildHiZ() turns the depth buffer that
//      just produced into a max-depth pyramid; phaseB() tests every section's box
//      against it and emits only the ones that are visible now but weren't drawn
//      in phase A. It also records who was visible, for next frame's phase A.
//      Result: a section is skipped ONLY when geometry really drawn this frame
//      hides it, so nothing pops in when the camera turns. The hints from last
//      frame only decide what gets drawn first.
//   3. Single-phase (the cutout pass, drawn right after): buildHiZ() over the
//      finished opaque depth, then phaseB(single).
// Culled commands stay in the buffer with instanceCount 0, so each region is
// still exactly one indirect draw with the command layout the caller wrote.
//
// GL state: compute work saves and restores the current program, and binds its
// buffers, images and textures only on binding points Minecraft and Sodium don't
// use or cache (SSBO 0-5, image units 0-1, texture units 14-15 with our own sampler
// objects), unbinding them after. Uploads use direct-state-access, so no
// ARRAY/ELEMENT/COPY buffer binding that Sodium's state tracker caches is touched.
// Needs OpenGL 4.5 (compute + SSBO + indirect draws + DSA): supported() checks.
public final class TerrainCuller {
	/** Hint slots: region hash buckets x 256 sections per Sodium region. Collisions only cost a phase-A guess. */
	public static final int REGION_SLOTS = 4096, SECTIONS_PER_REGION = 256;
	/** Bytes per indirect command: count, instanceCount, firstIndex, baseVertex, baseInstance. */
	public static final int COMMAND_BYTES = 20;
	private static final int SECTION_BYTES = 32;
	private static final int UNIT_DEPTH = 14, UNIT_HIZ = 15;

	public static boolean supported() {
		try {
			var caps = GL.getCapabilities();
			return caps.OpenGL45;
		} catch (Throwable t) {
			return false;
		}
	}

	private final int progA, progB, progHiz0, progHizN;
	private final int sections, templates, outA, outB, hints, drawnA, stats;
	private final int depthSampler, hizSampler;
	private int hizTex = 0, hizW = 0, hizH = 0, hizLevels = 0;
	private long sectionCap = 0, commandCap = 0;

	private ByteBuffer sectionData = MemoryUtil.memAlloc(4096 * SECTION_BYTES);
	private ByteBuffer commandData = MemoryUtil.memAlloc(4096 * 4 * COMMAND_BYTES);
	private int sectionCount = 0, commandCount = 0, maxElements = 0;
	private int curFirst = 0;

	private final StatsReadback readback;

	/** Compiles everything; throws IllegalStateException with the driver's log on failure. */
	public TerrainCuller() {
		progA = compute(TerrainShaders.PHASE_A);
		progB = compute(TerrainShaders.PHASE_B);
		progHiz0 = compute(TerrainShaders.HIZ_FIRST);
		progHizN = compute(TerrainShaders.HIZ_REDUCE);
		sections = GL45C.glCreateBuffers();
		templates = GL45C.glCreateBuffers();
		outA = GL45C.glCreateBuffers();
		outB = GL45C.glCreateBuffers();
		drawnA = GL45C.glCreateBuffers();
		hints = GL45C.glCreateBuffers();
		GL45C.glNamedBufferData(hints, (long) REGION_SLOTS * SECTIONS_PER_REGION * 4, GL15C.GL_DYNAMIC_COPY);
		clearUint(hints);
		stats = GL45C.glCreateBuffers();
		GL45C.glNamedBufferData(stats, 16, GL15C.GL_DYNAMIC_COPY);
		clearUint(stats);
		depthSampler = sampler(GL11C.GL_NEAREST);
		hizSampler = sampler(GL11C.GL_NEAREST_MIPMAP_NEAREST);
		readback = new StatsReadback(stats);
	}

	// ---- 1. the pass's sections (CPU) ----

	public void reset() {
		sectionCount = 0;
		commandCount = 0;
		maxElements = 0;
	}

	/**
	 * A section: its min corner relative to the camera (blocks) and its hint slot
	 * (regionSlot * 256 + section index in the region). Its commands follow.
	 */
	public void addSection(float relX, float relY, float relZ, int slot) {
		closeSection();
		sectionData = ensure(sectionData, (sectionCount + 1) * SECTION_BYTES);
		int o = sectionCount * SECTION_BYTES;
		sectionData.putFloat(o, relX).putFloat(o + 4, relY).putFloat(o + 8, relZ).putFloat(o + 12, 0f);
		sectionData.putInt(o + 16, slot).putInt(o + 20, commandCount).putInt(o + 24, 0).putInt(o + 28, 0);
		curFirst = commandCount;
		sectionCount++;
	}

	/** One draw (one facing of the current section) from the shared quad index buffer. */
	public void addCommand(int elementCount, int baseVertex) {
		commandData = ensure(commandData, (commandCount + 1) * COMMAND_BYTES);
		int o = commandCount * COMMAND_BYTES;
		commandData.putInt(o, elementCount).putInt(o + 4, 0).putInt(o + 8, 0).putInt(o + 12, baseVertex).putInt(o + 16, 0);
		commandCount++;
		maxElements = Math.max(maxElements, elementCount);
	}

	private void closeSection() {
		if (sectionCount > 0) {
			sectionData.putInt((sectionCount - 1) * SECTION_BYTES + 24, commandCount - curFirst);
		}
	}

	public int commandCount() {
		return commandCount;
	}

	public int sectionCount() {
		return sectionCount;
	}

	/** Largest single command, for sizing the shared index buffer. */
	public int maxElements() {
		return maxElements;
	}

	public void upload() {
		closeSection();
		if (sectionCount > sectionCap) {
			sectionCap = Math.max(sectionCount, sectionCap * 2);
			GL45C.glNamedBufferData(drawnA, sectionCap * 4, GL15C.GL_DYNAMIC_COPY);
		}
		if (commandCount > commandCap) {
			commandCap = Math.max(commandCount, commandCap * 2);
			GL45C.glNamedBufferData(outA, commandCap * COMMAND_BYTES, GL15C.GL_DYNAMIC_COPY);
			GL45C.glNamedBufferData(outB, commandCap * COMMAND_BYTES, GL15C.GL_DYNAMIC_COPY);
		}
		// orphan + fill: last frame's copy may still be read by the GPU
		upload(sections, sectionData, sectionCount * SECTION_BYTES);
		upload(templates, commandData, commandCount * COMMAND_BYTES);
	}

	private static void upload(int buf, ByteBuffer data, int bytes) {
		GL45C.glNamedBufferData(buf, Math.max(bytes, 16), GL15C.GL_STREAM_DRAW);
		if (bytes > 0) {
			GL45C.nglNamedBufferSubData(buf, 0, bytes, MemoryUtil.memAddress(data));
		}
	}

	// ---- 2. GPU work ----

	/** Phase A: the indirect buffer keeps exactly the sections visible last frame. */
	public void phaseA() {
		if (sectionCount == 0) {
			return;
		}
		int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		try {
			GL20C.glUseProgram(progA);
			GL45C.glProgramUniform1ui(progA, GL20C.glGetUniformLocation(progA, "u_count"), sectionCount);
			bindBuffers();
			GL43C.glDispatchCompute(groups(sectionCount, 64), 1, 1);
			GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);
		} finally {
			unbindBuffers();
			GL20C.glUseProgram(prev);
		}
	}

	/**
	 * Max-depth pyramid of depthTex (a GL_TEXTURE_2D depth texture, width x height).
	 * Level 0 is the largest power of two at most half the screen per axis; every
	 * level-0 texel takes the max over all the depth pixels it overlaps, so the
	 * pyramid never claims something is nearer than it is.
	 */
	public void buildHiZ(int depthTex, int width, int height) {
		int w = Math.max(1, Integer.highestOneBit(Math.max(1, width / 2)));
		int h = Math.max(1, Integer.highestOneBit(Math.max(1, height / 2)));
		if (w != hizW || h != hizH || hizTex == 0) {
			if (hizTex != 0) {
				GL11C.glDeleteTextures(hizTex);
			}
			hizW = w;
			hizH = h;
			hizLevels = 32 - Integer.numberOfLeadingZeros(Math.max(w, h));
			hizTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
			GL45C.glTextureStorage2D(hizTex, hizLevels, GL30C.GL_R32F, w, h);
		}
		int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		try {
			GL20C.glUseProgram(progHiz0);
			GL45C.glBindTextureUnit(UNIT_DEPTH, depthTex);
			GL45C.glBindSampler(UNIT_DEPTH, depthSampler);
			GL45C.glProgramUniform1i(progHiz0, GL20C.glGetUniformLocation(progHiz0, "u_depth"), UNIT_DEPTH);
			GL45C.glProgramUniform2i(progHiz0, GL20C.glGetUniformLocation(progHiz0, "u_src"), width, height);
			GL45C.glProgramUniform2i(progHiz0, GL20C.glGetUniformLocation(progHiz0, "u_dst"), w, h);
			GL42C.glBindImageTexture(0, hizTex, 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
			GL43C.glDispatchCompute(groups(w, 8), groups(h, 8), 1);
			GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
			GL20C.glUseProgram(progHizN);
			int inSize = GL20C.glGetUniformLocation(progHizN, "u_inSize");
			int outSize = GL20C.glGetUniformLocation(progHizN, "u_outSize");
			for (int l = 1; l < hizLevels; l++) {
				int iw = Math.max(1, w >> (l - 1)), ih = Math.max(1, h >> (l - 1));
				int ow = Math.max(1, w >> l), oh = Math.max(1, h >> l);
				GL45C.glProgramUniform2i(progHizN, inSize, iw, ih);
				GL45C.glProgramUniform2i(progHizN, outSize, ow, oh);
				GL42C.glBindImageTexture(0, hizTex, l - 1, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32F);
				GL42C.glBindImageTexture(1, hizTex, l, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
				GL43C.glDispatchCompute(groups(ow, 8), groups(oh, 8), 1);
				GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
			}
			GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
		} finally {
			GL42C.glBindImageTexture(0, 0, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32F);
			GL42C.glBindImageTexture(1, 0, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32F);
			GL45C.glBindSampler(UNIT_DEPTH, 0);
			GL45C.glBindTextureUnit(UNIT_DEPTH, 0);
			GL20C.glUseProgram(prev);
		}
	}

	/**
	 * Phase B: tests every section against the pyramid. twoPhase = emit only the
	 * visible sections phase A didn't draw, and remember visibility for next
	 * frame; otherwise emit every visible section. viewProj = projection * modelView
	 * as the terrain shader uses them (camera-relative positions).
	 */
	public void phaseB(Matrix4fc viewProj, boolean twoPhase) {
		if (sectionCount == 0) {
			return;
		}
		int prev = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL20C.glUseProgram(progB);
			FloatBuffer m = stack.mallocFloat(16);
			viewProj.get(m);
			GL45C.glProgramUniformMatrix4fv(progB, GL20C.glGetUniformLocation(progB, "u_viewProj"), false, m);
			float[] lin = depthLinearization(viewProj);
			GL45C.glProgramUniform2f(progB, GL20C.glGetUniformLocation(progB, "u_depthLin"), lin[0], lin[1]);
			GL45C.glProgramUniform2f(progB, GL20C.glGetUniformLocation(progB, "u_hizSize"), hizW, hizH);
			GL45C.glProgramUniform1i(progB, GL20C.glGetUniformLocation(progB, "u_hizMax"), hizLevels - 1);
			GL45C.glProgramUniform1i(progB, GL20C.glGetUniformLocation(progB, "u_hiz"), UNIT_HIZ);
			GL45C.glProgramUniform1ui(progB, GL20C.glGetUniformLocation(progB, "u_count"), sectionCount);
			GL45C.glProgramUniform1ui(progB, GL20C.glGetUniformLocation(progB, "u_twoPhase"), twoPhase ? 1 : 0);
			GL45C.glBindTextureUnit(UNIT_HIZ, hizTex);
			GL45C.glBindSampler(UNIT_HIZ, hizSampler);
			bindBuffers();
			GL43C.glDispatchCompute(groups(sectionCount, 64), 1, 1);
			GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);
		} finally {
			unbindBuffers();
			GL45C.glBindSampler(UNIT_HIZ, 0);
			GL45C.glBindTextureUnit(UNIT_HIZ, 0);
			GL20C.glUseProgram(prev);
		}
	}

	/**
	 * (m22, m32) of the pure perspective inside projection * (affine view), so the
	 * shader can turn a depth-buffer value back into distance along the view axis.
	 * Minecraft bakes view bobbing / nausea into the projection; for any affine B,
	 * row 2 of P*B is -m22 * row 3 + m32 * e3, which this solves for.
	 */
	static float[] depthLinearization(Matrix4fc pv) {
		// JOML is column-major: mCR = column C, row R
		float r2x = pv.m02(), r2y = pv.m12(), r2z = pv.m22(), r2w = pv.m32();
		float r3x = pv.m03(), r3y = pv.m13(), r3z = pv.m23(), r3w = pv.m33();
		float len = r3x * r3x + r3y * r3y + r3z * r3z;
		float m22 = len == 0 ? 0 : -(r2x * r3x + r2y * r3y + r2z * r3z) / len;
		float m32 = r2w + m22 * r3w;
		return new float[]{m22, m32};
	}

	/** The buffer to draw from: phase A's or phase B's commands (same layout as written). */
	public int indirectBuffer(boolean phaseA) {
		return phaseA ? outA : outB;
	}

	// ---- stats (async, a few frames late; never stalls) ----

	/** Zero the counters (once per frame, before the opaque pass's phase B). */
	public void beginFrameStats() {
		readback.poll();
		clearUint(stats);
	}

	/** Copy this frame's counters out for reading later. */
	public void endFrameStats() {
		readback.capture();
	}

	/** {sections tested, sections visible} of a recent frame, or null before the first one lands. */
	public int[] lastStats() {
		return readback.latest();
	}

	public void delete() {
		GL20C.glDeleteProgram(progA);
		GL20C.glDeleteProgram(progB);
		GL20C.glDeleteProgram(progHiz0);
		GL20C.glDeleteProgram(progHizN);
		GL15C.glDeleteBuffers(new int[]{sections, templates, outA, outB, hints, drawnA, stats});
		GL45C.glDeleteSamplers(new int[]{depthSampler, hizSampler});
		if (hizTex != 0) {
			GL11C.glDeleteTextures(hizTex);
		}
		readback.delete();
		MemoryUtil.memFree(sectionData);
		MemoryUtil.memFree(commandData);
	}

	// ---- helpers ----

	private void bindBuffers() {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, sections);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, templates);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, outA);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, outB);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, hints);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, drawnA);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, stats);
	}

	private static void unbindBuffers() {
		for (int i = 0; i <= 6; i++) {
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, i, 0);
		}
	}

	private static int groups(int n, int size) {
		return Math.max(1, (n + size - 1) / size);
	}

	private static void clearUint(int buf) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			GL45C.glClearNamedBufferData(buf, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, stack.ints(0));
		}
	}

	private static int sampler(int minFilter) {
		int s = GL45C.glCreateSamplers();
		GL45C.glSamplerParameteri(s, GL11C.GL_TEXTURE_MIN_FILTER, minFilter);
		GL45C.glSamplerParameteri(s, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL45C.glSamplerParameteri(s, GL11C.GL_TEXTURE_WRAP_S, GL15C.GL_CLAMP_TO_EDGE);
		GL45C.glSamplerParameteri(s, GL11C.GL_TEXTURE_WRAP_T, GL15C.GL_CLAMP_TO_EDGE);
		// a depth texture with comparison on would return 0/1 instead of depth
		GL45C.glSamplerParameteri(s, GL30C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
		return s;
	}

	private static ByteBuffer ensure(ByteBuffer b, int bytes) {
		if (bytes <= b.capacity()) {
			return b;
		}
		return MemoryUtil.memRealloc(b, Math.max(bytes, b.capacity() * 2));
	}

	private static int compute(String src) {
		int sh = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		GL20C.glShaderSource(sh, src);
		GL20C.glCompileShader(sh);
		if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == 0) {
			String log = GL20C.glGetShaderInfoLog(sh);
			GL20C.glDeleteShader(sh);
			throw new IllegalStateException("Origin Render shader failed to compile: " + log);
		}
		int p = GL20C.glCreateProgram();
		GL20C.glAttachShader(p, sh);
		GL20C.glLinkProgram(p);
		GL20C.glDeleteShader(sh);
		if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
			String log = GL20C.glGetProgramInfoLog(p);
			GL20C.glDeleteProgram(p);
			throw new IllegalStateException("Origin Render shader failed to link: " + log);
		}
		return p;
	}

	/** Three small copy buffers with fences: read whichever the GPU has finished. */
	private static final class StatsReadback {
		private final int src;
		private final int[] bufs = new int[3];
		private final long[] fences = new long[3];
		private int next = 0;
		private int[] latest;

		StatsReadback(int src) {
			this.src = src;
			for (int i = 0; i < 3; i++) {
				bufs[i] = GL45C.glCreateBuffers();
				GL45C.glNamedBufferData(bufs[i], 16, GL15C.GL_STREAM_READ);
			}
		}

		void capture() {
			if (fences[next] != 0) {
				return; // still in flight: skip this frame's numbers
			}
			GL45C.glCopyNamedBufferSubData(src, bufs[next], 0, 0, 16);
			fences[next] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			next = (next + 1) % 3;
		}

		void poll() {
			for (int i = 0; i < 3; i++) {
				if (fences[i] == 0) {
					continue;
				}
				int r = GL32C.glClientWaitSync(fences[i], 0, 0);
				if (r == GL32C.GL_ALREADY_SIGNALED || r == GL32C.GL_CONDITION_SATISFIED) {
					GL32C.glDeleteSync(fences[i]);
					fences[i] = 0;
					try (MemoryStack stack = MemoryStack.stackPush()) {
						var out = stack.mallocInt(4);
						GL45C.glGetNamedBufferSubData(bufs[i], 0, out);
						latest = new int[]{out.get(0), out.get(1)};
					}
				}
			}
		}

		int[] latest() {
			return latest;
		}

		void delete() {
			for (int i = 0; i < 3; i++) {
				if (fences[i] != 0) {
					GL32C.glDeleteSync(fences[i]);
				}
			}
			GL15C.glDeleteBuffers(bufs);
		}
	}
}
