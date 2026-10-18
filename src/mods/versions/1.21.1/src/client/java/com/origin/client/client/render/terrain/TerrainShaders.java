package com.origin.client.client.render.terrain;

// Origin Render's compute shaders (GLSL 4.50). Buffer bindings, shared by both
// cull shaders (TerrainCuller.bindBuffers):
//   0 sections   per section: vec4 (camera-relative min corner, -), uvec4 (hint slot, first command, command count, -)
//   1 templates  per command: 5 uints as the CPU wrote them (instanceCount 0)
//   2 outA / 3 outB  the indirect buffers: template copied, instanceCount 0 or 1
//   4 hints      per hint slot: 1 = visible last frame
//   5 drawnA     per section: 1 = phase A drew it this frame
//   6 stats      [0] sections tested, [1] visible (two-phase pass only)
final class TerrainShaders {
	private TerrainShaders() {
	}

	private static final String COMMON = """
			#version 450
			struct Section { vec4 box; uvec4 meta; };
			layout(std430, binding = 0) readonly buffer Sections { Section sections[]; };
			layout(std430, binding = 1) readonly buffer Templates { uint tmpl[]; };
			layout(std430, binding = 2) buffer OutA { uint outA[]; };
			layout(std430, binding = 3) buffer OutB { uint outB[]; };
			layout(std430, binding = 4) buffer Hints { uint hints[]; };
			layout(std430, binding = 5) buffer DrawnA { uint drawnA[]; };
			layout(std430, binding = 6) buffer Stats { uint stats[]; };
			uniform uint u_count;
			""";

	/** Phase A: draw what was visible last frame. */
	static final String PHASE_A = COMMON + """
			layout(local_size_x = 64) in;
			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= u_count) return;
				uvec4 m = sections[i].meta;
				uint d = hints[m.x] != 0u ? 1u : 0u;
				drawnA[i] = d;
				for (uint c = m.y; c < m.y + m.z; c++) {
					uint b = c * 5u;
					outA[b] = tmpl[b];
					outA[b + 1u] = d;
					outA[b + 2u] = tmpl[b + 2u];
					outA[b + 3u] = tmpl[b + 3u];
					outA[b + 4u] = 0u;
				}
			}
			""";

	/**
	 * Phase B: is the section's box (one block of margin all round: block models
	 * may reach one block outside their section) hidden behind the pyramid?
	 * Visible unless provably hidden: a box crossing the near plane, a depth of
	 * 1.0 (sky) anywhere under it, or any doubt counts as visible.
	 */
	static final String PHASE_B = COMMON + """
			layout(local_size_x = 64) in;
			uniform mat4 u_viewProj;
			uniform vec2 u_depthLin; // (m22, m32) of the perspective: viewDist = m32 / (ndcZ + m22)
			uniform vec2 u_hizSize;  // pyramid level-0 size
			uniform int u_hizMax;    // last level
			uniform uint u_twoPhase;
			uniform sampler2D u_hiz;

			bool visible(vec3 mn) {
				mn -= vec3(1.0);
				vec3 mx = mn + vec3(18.0);
				vec2 lo = vec2(1e30), hi = vec2(-1e30);
				float minW = 1e30;
				for (int k = 0; k < 8; k++) {
					vec3 p = vec3((k & 1) != 0 ? mx.x : mn.x, (k & 2) != 0 ? mx.y : mn.y, (k & 4) != 0 ? mx.z : mn.z);
					vec4 c = u_viewProj * vec4(p, 1.0);
					if (c.w <= 0.1) return true; // touches / behind the camera
					minW = min(minW, c.w);
					vec2 uv = c.xy / c.w * 0.5 + 0.5;
					lo = min(lo, uv);
					hi = max(hi, uv);
				}
				if (hi.x < 0.0 || hi.y < 0.0 || lo.x > 1.0 || lo.y > 1.0) return false; // off screen
				vec2 p0 = clamp(lo, 0.0, 1.0) * u_hizSize;
				vec2 p1 = clamp(hi, 0.0, 1.0) * u_hizSize;
				vec2 ext = p1 - p0;
				int level = clamp(int(ceil(log2(max(max(ext.x, ext.y), 1.0)))), 0, u_hizMax);
				ivec2 a, b;
				// the box must span at most 2x2 texels of the level it reads, so
				// four fetches cover it; step up a level if rounding says otherwise
				for (;;) {
					ivec2 dim = max(ivec2(u_hizSize) >> level, ivec2(1));
					a = clamp(ivec2(floor(p0)) >> level, ivec2(0), dim - 1);
					b = clamp(ivec2(floor(p1)) >> level, ivec2(0), dim - 1);
					if ((b.x - a.x <= 1 && b.y - a.y <= 1) || level >= u_hizMax) break;
					level++;
				}
				if (b.x - a.x > 1 || b.y - a.y > 1) return true;
				float d = max(max(texelFetch(u_hiz, a, level).r, texelFetch(u_hiz, ivec2(b.x, a.y), level).r),
						max(texelFetch(u_hiz, ivec2(a.x, b.y), level).r, texelFetch(u_hiz, b, level).r));
				if (d >= 1.0) return true; // sky behind: nothing to hide it
				float den = (d * 2.0 - 1.0) + u_depthLin.x;
				if (den >= 0.0) return true;
				float occW = u_depthLin.y / den;
				if (!(occW > 0.0)) return true;
				// hidden only when clearly behind (precision margin grows with distance)
				return !(minW > occW + 1.0 + occW * 0.01);
			}

			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= u_count) return;
				Section s = sections[i];
				bool vis = visible(s.box.xyz);
				uint draw;
				if (u_twoPhase != 0u) {
					hints[s.meta.x] = vis ? 1u : 0u;
					draw = (vis && drawnA[i] == 0u) ? 1u : 0u;
					atomicAdd(stats[0], 1u);
					if (vis) atomicAdd(stats[1], 1u);
				} else {
					draw = vis ? 1u : 0u;
				}
				for (uint c = s.meta.y; c < s.meta.y + s.meta.z; c++) {
					uint b = c * 5u;
					outB[b] = tmpl[b];
					outB[b + 1u] = draw;
					outB[b + 2u] = tmpl[b + 2u];
					outB[b + 3u] = tmpl[b + 3u];
					outB[b + 4u] = 0u;
				}
			}
			""";

	/** Level 0: max over every depth pixel the texel overlaps (conservative for any screen size). */
	static final String HIZ_FIRST = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			uniform sampler2D u_depth;
			uniform ivec2 u_src;
			uniform ivec2 u_dst;
			layout(r32f, binding = 0) writeonly uniform image2D u_out;
			void main() {
				ivec2 p = ivec2(gl_GlobalInvocationID.xy);
				if (p.x >= u_dst.x || p.y >= u_dst.y) return;
				ivec2 lo = (p * u_src) / u_dst;
				ivec2 hi = min(((p + 1) * u_src + u_dst - 1) / u_dst, u_src);
				float m = 0.0;
				for (int y = lo.y; y < hi.y; y++)
					for (int x = lo.x; x < hi.x; x++)
						m = max(m, texelFetch(u_depth, ivec2(x, y), 0).r);
				imageStore(u_out, p, vec4(m));
			}
			""";

	/** Each further level: max of the 2x2 below it. */
	static final String HIZ_REDUCE = """
			#version 450
			layout(local_size_x = 8, local_size_y = 8) in;
			uniform ivec2 u_inSize;
			uniform ivec2 u_outSize;
			layout(r32f, binding = 0) readonly uniform image2D u_in;
			layout(r32f, binding = 1) writeonly uniform image2D u_out;
			void main() {
				ivec2 p = ivec2(gl_GlobalInvocationID.xy);
				if (p.x >= u_outSize.x || p.y >= u_outSize.y) return;
				ivec2 q = p * 2;
				ivec2 e = u_inSize - 1;
				float m = max(max(imageLoad(u_in, min(q, e)).r, imageLoad(u_in, min(q + ivec2(1, 0), e)).r),
						max(imageLoad(u_in, min(q + ivec2(0, 1), e)).r, imageLoad(u_in, min(q + ivec2(1, 1), e)).r));
				imageStore(u_out, p, vec4(m));
			}
			""";
}
