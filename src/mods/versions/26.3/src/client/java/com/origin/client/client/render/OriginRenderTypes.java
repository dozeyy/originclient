package com.origin.client.client.render;

import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginShaders;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Origin's world-overlay quad surface + the way it reaches the screen. Used by
 * the block outline/overlay, waypoint beams, chunk-border posts, party ping
 * beams and the WorldEdit CUI -- translucent, depth-tested quads in the world --
 * plus vanilla's LINES type for chunk-border rings and waypoint boxes.
 *
 * <p>PER-VERSION DELTA (26.x): there is no MultiBufferSource / immediate buffer
 * any more. The level is drawn from SUBMITS collected during the frame
 * (SubmitNodeCollector), so the 1.21.11 contract is kept as
 * <em>record, then submit</em>:
 * <ul>
 *   <li>{@link #begin()} / {@link #beginLines()} hand back a recording
 *       VertexConsumer -- callers emit vertices exactly as on 1.21.11
 *       (camera-relative positions through the context's PoseStack);</li>
 *   <li>{@link #draw()} submits what was recorded as custom geometry to the
 *       frame's collector (one submit per type), replaying the vertices into
 *       vanilla's buffer when the frame is drawn.</li>
 * </ul>
 * The collector comes from {@link #beginFrame}, which OriginClientMod registers
 * FIRST on Fabric's LevelRenderEvents.COLLECT_SUBMITS -- the event every Origin
 * world overlay draws from on 26.2. Submitted through the vanilla collector,
 * the geometry is drawn in the frame's own pass with the frame's matrices and
 * depth (also what Iris intercepts), so the 1.21.11 Iris deferral problem
 * doesn't arise. Outside a frame (no collector) draw() just discards.
 *
 * <p>OVERLAY_QUADS = vanilla DEBUG_QUADS (POSITION_COLOR quads, translucent) with
 * no culling, a closer-or-equal depth test, no depth write, and the view-offset
 * layering vanilla's outline uses, so faces lying ON a block win the depth tie.
 *
 * <p>26.3 DEPTH IS REVERSED-Z: the depth buffer clears to 0.0 (far) and nearer
 * fragments have LARGER depth, so "closer or equal" is GREATER_THAN_OR_EQUAL --
 * what every vanilla 26.3 world pipeline uses (e.g. LINES_DEPTH_BIAS). The
 * 26.2-derived LESS_THAN_OR_EQUAL passed only for fragments BEHIND the terrain:
 * the outline's front edges vanished and its back edges showed through the block.
 */
public final class OriginRenderTypes {
	private OriginRenderTypes() {
	}

	private static RenderType overlay;
	private static boolean overlayBroken;

	/** Translucent, depth-tested (reversed-Z GEQUAL), non-depth-writing, unculled POSITION_COLOR quads (null if unavailable). */
	public static RenderType overlayQuads() {
		if (overlay == null && !overlayBroken && RenderPipelines.DEBUG_QUADS != null) {
			try {
				RenderPipeline p = OriginShaders.derive(RenderPipelines.DEBUG_QUADS, "pipeline/overlay_quads", null,
						b -> b.withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)));
				overlay = RenderType.create("originclient_overlay_quads",
						RenderSetup.builder(p).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup());
			} catch (Throwable t) {
				overlayBroken = true;
				com.origin.client.OriginClient.LOGGER.warn("Origin: overlay quad type failed; using debug quads", t);
			}
		}
		return overlay != null ? overlay : RenderTypes.debugQuads();
	}

	// ---- per-frame submit target ----

	private static LevelRenderContext frame;

	/** COLLECT_SUBMITS, registered first: remembers this frame's collector. */
	public static void beginFrame(LevelRenderContext context) {
		frame = context;
	}

	/** The current frame's context (null outside COLLECT_SUBMITS). */
	public static LevelRenderContext frame() {
		return frame;
	}

	private static final Recorder QUADS = new Recorder();
	private static final Recorder LINES = new Recorder();

	/** The consumer to emit OVERLAY_QUADS vertices into (addVertex + setColor). */
	public static VertexConsumer begin() {
		return QUADS;
	}

	/** The consumer to emit RenderTypes.lines() vertices into (addVertex +
	 *  setColor + setNormal + setLineWidth). */
	public static VertexConsumer beginLines() {
		return LINES;
	}

	private static final Recorder EXTRA = new Recorder();

	/** A third recorder for one caller-chosen quad type (the WorldEdit CUI's
	 *  see-through pass); submitted by {@link #drawExtra}. */
	public static VertexConsumer beginExtra() {
		return EXTRA;
	}

	/** Submits the {@link #beginExtra} vertices as {@code type} (quads). */
	public static void drawExtra(RenderType type) {
		LevelRenderContext ctx = frame;
		try {
			if (ctx != null) {
				EXTRA.submit(ctx, type, false);
			}
		} finally {
			EXTRA.clear();
		}
	}

	/** Submits everything recorded since the last draw() to this frame. */
	public static void draw() {
		LevelRenderContext ctx = frame;
		try {
			if (ctx != null) {
				QUADS.submit(ctx, overlayQuads(), false);
				LINES.submit(ctx, RenderTypes.lines(), true);
			}
		} finally {
			QUADS.clear();
			LINES.clear();
		}
	}

	/**
	 * Records vertices (positions already through the caller's matrix -- the
	 * VertexConsumer defaults transform, then call addVertex(x, y, z)) and
	 * replays them into a custom-geometry submit.
	 */
	private static final class Recorder implements VertexConsumer {
		// per vertex: x, y, z, nx, ny, nz, lineWidth ; color separately
		private float[] f = new float[7 * 256];
		private int[] c = new int[256];
		private int n = 0;

		private int cur() {
			return n - 1;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			if (n == c.length) {
				f = java.util.Arrays.copyOf(f, f.length * 2);
				c = java.util.Arrays.copyOf(c, c.length * 2);
			}
			int o = n * 7;
			f[o] = x;
			f[o + 1] = y;
			f[o + 2] = z;
			f[o + 3] = 0;
			f[o + 4] = 1;
			f[o + 5] = 0;
			f[o + 6] = 1;
			c[n] = 0xFFFFFFFF;
			n++;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			if (n > 0) {
				c[cur()] = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
			}
			return this;
		}

		@Override
		public VertexConsumer setColor(int argb) {
			if (n > 0) {
				c[cur()] = argb;
			}
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		// 26.3: a third UV set (the in-pass glint coordinates); Origin's overlays have none.
		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			if (n > 0) {
				int o = cur() * 7;
				f[o + 3] = x;
				f[o + 4] = y;
				f[o + 5] = z;
			}
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float w) {
			if (n > 0) {
				f[cur() * 7 + 6] = w;
			}
			return this;
		}

		// One-time breadcrumbs (record -> submit -> replay) for the first 26.x boots.
		private static boolean loggedSubmit, loggedReplay;

		void clear() {
			n = 0;
		}

		void submit(LevelRenderContext ctx, RenderType type, boolean lines) {
			if (n == 0 || type == null) {
				return;
			}
			if (!loggedSubmit) {
				loggedSubmit = true;
				com.origin.client.OriginClient.LOGGER.info("Origin[world]: first {} submit, {} vertices", lines ? "lines" : "quads", n);
			}
			final float[] fs = java.util.Arrays.copyOf(f, n * 7);
			final int[] cs = java.util.Arrays.copyOf(c, n);
			final int count = n;
			// Identity pose: the recorded positions already carry the caller's matrix.
			ctx.submitNodeCollector().submitCustomGeometry(new PoseStack(), type, (pose, vc) -> {
				if (!loggedReplay) {
					loggedReplay = true;
					com.origin.client.OriginClient.LOGGER.info("Origin[world]: first replay into the frame ({} vertices)", count);
				}
				for (int i = 0; i < count; i++) {
					int o = i * 7;
					VertexConsumer v = vc.addVertex(fs[o], fs[o + 1], fs[o + 2]).setColor(cs[i]);
					if (lines) {
						v.setNormal(fs[o + 3], fs[o + 4], fs[o + 5]).setLineWidth(fs[o + 6]);
					}
				}
			});
		}
	}
}
