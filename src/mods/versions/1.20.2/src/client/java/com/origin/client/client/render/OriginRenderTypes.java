package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 *
 * Why not RenderType.debugQuads() through the frame's shared buffer (what the
 * outline used before): Iris (installed with Origin on 1.20.2) swaps
 * RenderBuffers' bufferSource for its own fully-buffered one during
 * renderLevel even with shaders OFF, so quads handed to it are drawn LATER, in
 * Iris's flush, under whatever matrices/state are current then. The outline
 * then disagreed with the depth buffer it was meant to be tested against --
 * worst right after a zoom, when the FOV was changing -- and its hidden back
 * edges showed through the block. So: our own state, and our own buffer that
 * is drawn on the spot (begin() ... draw()), at the moment the world matrices
 * and depth are right.
 *
 * The state, spelled out instead of inherited from debugQuads' defaults:
 *   POSITION_COLOR shader, TRANSLUCENT blend, NO_CULL (quads wound either way),
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   COLOR_WRITE only -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth,
 *   VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge (scales the view a
 *     hair toward the camera) so faces lying ON a block surface win the depth
 *     tie instead of z-fighting it.
 *
 * Built on the public RenderType constructor with vanilla's OWN state shards
 * (protected statics, reachable from this subclass) -- RenderType.create is
 * not public, and there is no access widener in this module.
 *
 * ERA NOTE: this is the 1.20-1.20.4 shape -- the pre-ByteBufferBuilder era, so
 * the private buffer is a plain BufferBuilder (it grows on demand) and
 * vertices are emitted vertex(...).color(...).endVertex(). 1.20.5+ swaps in
 * ByteBufferBuilder/MeshData; keep each module's own era shape.
 */
public final class OriginRenderTypes extends RenderType {

	/** Translucent, depth-tested, non-depth-writing world quads (see class doc). */
	public static final RenderType OVERLAY_QUADS = new OriginRenderTypes();

	// Private immediate buffer. 64 KiB start; BufferBuilder grows on demand.
	// Never shared with vanilla/Iris, so nothing else ever flushes or defers it.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new BufferBuilder(1 << 16));

	private OriginRenderTypes() {
		super("originclient_overlay_quads", DefaultVertexFormat.POSITION_COLOR,
				VertexFormat.Mode.QUADS, 1 << 16, false, false,
				() -> {
					POSITION_COLOR_SHADER.setupRenderState();
					TRANSLUCENT_TRANSPARENCY.setupRenderState();
					LEQUAL_DEPTH_TEST.setupRenderState();
					NO_CULL.setupRenderState();
					VIEW_OFFSET_Z_LAYERING.setupRenderState();
					COLOR_WRITE.setupRenderState();
				},
				() -> {
					COLOR_WRITE.clearRenderState();
					VIEW_OFFSET_Z_LAYERING.clearRenderState();
					NO_CULL.clearRenderState();
					LEQUAL_DEPTH_TEST.clearRenderState();
					TRANSLUCENT_TRANSPARENCY.clearRenderState();
					POSITION_COLOR_SHADER.clearRenderState();
				});
	}

	// ---- the after-shaders BANK (render/AfterShadersPass) ----
	// While an Iris shader pack is in use, the overlays that must stay above the
	// pack's clouds / fog (waypoints, party pings, WorldEdit CUI, Chunk Borders)
	// are wrapped in AfterShadersPass.bankBegin()/bankEnd(), which sets `banking`:
	// begin() then hands out the bank, the overlays' vanilla RenderType.lines()
	// go to bankLines() instead of the frame's shared buffer, draw() leaves them alone, and
	// AfterShadersPass.run() draws them with drawBank() once Iris has composited.
	// Separate from the immediate buffers above, so an unwrapped caller's draw()
	// (block outline, cosmetics) can never flush banked vertices early. Vertices
	// are posed at emission (the world PoseStack is baked in), so drawBank() only
	// needs RenderSystem's model-view + projection put back. One source per render
	// type: an immediate source with one shared builder draws the previous type's
	// batch the moment another type is requested. Shaders OFF: banking stays false
	// and nothing here is touched. Render thread only.
	static boolean banking;
	private static MultiBufferSource.BufferSource bankQuads, bankLines, bankExtra;
	// The bank's own native buffers: kept so discardBank() can free them (BufferSource has no close).
	private static BufferBuilder bankQuadsBuf, bankLinesBuf, bankExtraBuf;
	static {
		newBank();
	}

	private static void newBank() {
		bankQuadsBuf = new BufferBuilder(1 << 16);
		bankLinesBuf = new BufferBuilder(1 << 14);
		bankExtraBuf = new BufferBuilder(1 << 14);
		bankQuads = MultiBufferSource.immediate(bankQuadsBuf);
		bankLines = MultiBufferSource.immediate(bankLinesBuf);
		bankExtra = MultiBufferSource.immediate(bankExtraBuf);
	}

	/** True while the current overlay's vertices go to the after-shaders bank. */
	public static boolean banking() {
		return banking;
	}

	/** The bank source for an overlay's vanilla RenderType.lines() (chunk-border rings, the waypoint
	 *  highlight). Use only while banking(). */
	public static MultiBufferSource.BufferSource bankLines() {
		return bankLines;
	}

	/** The bank source for a caller's own extra render type (the WorldEdit CUI's
	 *  see-through pass). Use only while banking(). */
	public static MultiBufferSource.BufferSource bankExtra() {
		return bankExtra;
	}

	/** Draws the bank NOW under the current matrices (AfterShadersPass.run()):
	 *  the see-through pass, the quads, then the lines -- lines last, as at LAST,
	 *  where they landed after every quad. */
	static void drawBank() {
		bankExtra.endBatch();
		bankQuads.endBatch();
		bankLines.endBatch();
	}

	/** Drops what the bank holds without drawing it (a frame that never reached
	 *  the post hook, or a failed draw). Rare path: fresh sources, so a builder
	 *  left mid-batch can never leak a half-written vertex into a later frame. */
	static void discardBank() {
		// Free the old native buffers (~100 KB off-heap each time otherwise, B189PRE-4):
		// nothing else holds them once the sources below are replaced (as 1.21.10, 4d0f66b0).
		BufferBuilder[] old = {bankQuadsBuf, bankLinesBuf, bankExtraBuf};
		newBank();
		for (BufferBuilder b : old) {
			freeNative(b);
		}
	}

	/**
	 * Frees a BufferBuilder's off-heap buffer. Before 1.20.5 there is no ByteBufferBuilder
	 * to close: BufferBuilder mallocs its one ByteBuffer through MemoryTracker (1.17+), and
	 * garbage collection never frees that. Found by type, not name (field names are
	 * intermediary in a real game): exactly one instance ByteBuffer field, or nothing is
	 * touched and the buffer leaks as before. The field is nulled BEFORE the free, so a
	 * stray later use throws a Java exception instead of touching freed memory.
	 */
	private static void freeNative(BufferBuilder b) {
		try {
			java.lang.reflect.Field only = null;
			for (java.lang.reflect.Field f : BufferBuilder.class.getDeclaredFields()) {
				if (f.getType() == java.nio.ByteBuffer.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					if (only != null) {
						return;   // more than one: not the shape we know, leave it
					}
					only = f;
				}
			}
			if (only == null) {
				return;
			}
			only.setAccessible(true);
			java.nio.ByteBuffer buf = (java.nio.ByteBuffer) only.get(b);
			if (buf == null || !buf.isDirect()) {
				return;
			}
			only.set(b, null);
			org.lwjgl.system.MemoryUtil.memFree(buf);
		} catch (Throwable ignored) {
			// fail-soft: the buffer stays allocated, exactly as before this fix
		}
	}

	/** The consumer to emit OVERLAY_QUADS vertices into (vertex + color + endVertex). */
	public static VertexConsumer begin() {
		return (banking ? bankQuads : IMMEDIATE).getBuffer(OVERLAY_QUADS);
	}

	/** Draws everything emitted since begin(), NOW, with the current matrices
	 *  and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		if (banking) {
			return;   // banked: AfterShadersPass.run() draws it after the pack's composite
		}
		IMMEDIATE.endBatch();
	}
}
