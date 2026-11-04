package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

import java.util.OptionalDouble;

/**
 * Origin's own world-overlay surfaces (quads + lines) and the private buffers
 * they are drawn through. Quads: the block outline/overlay, waypoint beams and
 * chunk-border posts -- anything that paints translucent, depth-tested quads
 * into the world. Lines: see WORLD_LINES.
 * (Hitboxes keep FillQuads through the entity buffer, as on 1.21.1.)
 *
 * Why not FillQuads through the frame's shared buffer (what the outline used
 * before): quads handed to the frame's buffer are drawn LATER, whenever that
 * buffer is flushed (and with Iris loaded, in Iris's own deferred flush), under
 * whatever matrices/state are current then. The outline could then disagree
 * with the depth buffer it was meant to be tested against. So: our own
 * state, and our own buffer that is drawn on the spot (begin() ... draw()), at
 * the moment the world matrices and depth are right.
 * (The 1.16.5 "outline shows its back edges after zooming" report was NOT this:
 * it was zoom leaving the culled chunk list narrow, so the targeted block itself
 * wasn't drawn -- see GameRendererMixin.)
 *
 * The state, spelled out instead of inherited from FillQuads' defaults:
 *   NO_TEXTURE (fixed function: texturing off, colour from the vertices),
 *   TRANSLUCENT blend, NO_CULL (quads wound either way),
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   COLOR_WRITE only -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth,
 *   VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge (1.16: pushes the GL
 *     model-view and scales it a hair toward the camera) so faces lying ON a
 *     block surface win the depth tie instead of z-fighting it.
 *
 * 1.16.5 shape: fixed-function era, no shader shards at all -- same public
 * RenderType constructor as the module's FillQuads (raw GL mode int, no
 * VertexFormat.Mode until 1.17) with vanilla's own shards (protected statics,
 * reachable from this subclass; all six exist on 1.16.5 -- LEQUAL_DEPTH_TEST,
 * COLOR_WRITE and VIEW_OFFSET_Z_LAYERING are the yarn LEQUAL_DEPTH_TEST /
 * COLOR_MASK / VIEW_OFFSET_Z_LAYERING fields of RenderPhase). The buffer is the
 * pre-1.20.5 MultiBufferSource.immediate(new BufferBuilder(n)).
 */
public final class OriginRenderTypes extends RenderType {

	/** Translucent, depth-tested, non-depth-writing world quads (see class doc). */
	public static final RenderType OVERLAY_QUADS = new OriginRenderTypes("originclient_overlay_quads",
			GL11.GL_QUADS,
			() -> {
				NO_TEXTURE.setupRenderState();
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
				NO_TEXTURE.clearRenderState();
			});

	// vanilla lines()'s own width rule: max(2.5px, windowWidth/1920*2.5).
	private static final LineStateShard VANILLA_LINE_WIDTH = new LineStateShard(OptionalDouble.empty());

	/**
	 * GL_LINES in vanilla RenderType.lines()'s exact state (1.16.5 RenderType
	 * line ~340: width rule, VIEW_OFFSET_Z_LAYERING, TRANSLUCENT, colour+depth
	 * write, plus the builder defaults it inherits: no texture, LEQUAL depth,
	 * fog, no alpha test) -- except it draws to the MAIN target. vanilla lines()
	 * outputs to ITEM_ENTITY_TARGET, which under Fabulous graphics is composited
	 * BEFORE WorldRenderEvents.LAST, so a LAST-time line drawn there vanished.
	 * Used by the world-render-hook line work (chunk-border rings + lattice,
	 * waypoint highlight box), through its own private buffer for the same
	 * reason as the quads: the frame's shared buffer is Iris's
	 * FullyBufferedMultiBufferSource during the level pass on 1.16.5 (Iris
	 * 1.4.5 batchedentityrendering), and its endBatch(RenderType) is a no-op.
	 */
	public static final RenderType WORLD_LINES = new OriginRenderTypes("originclient_world_lines",
			GL11.GL_LINES,
			() -> {
				NO_TEXTURE.setupRenderState();
				TRANSLUCENT_TRANSPARENCY.setupRenderState();
				LEQUAL_DEPTH_TEST.setupRenderState();
				NO_ALPHA.setupRenderState();
				FOG.setupRenderState();
				VIEW_OFFSET_Z_LAYERING.setupRenderState();
				VANILLA_LINE_WIDTH.setupRenderState();
				COLOR_DEPTH_WRITE.setupRenderState();
			},
			() -> {
				COLOR_DEPTH_WRITE.clearRenderState();
				VANILLA_LINE_WIDTH.clearRenderState();
				VIEW_OFFSET_Z_LAYERING.clearRenderState();
				FOG.clearRenderState();
				NO_ALPHA.clearRenderState();
				LEQUAL_DEPTH_TEST.clearRenderState();
				TRANSLUCENT_TRANSPARENCY.clearRenderState();
				NO_TEXTURE.clearRenderState();
			});

	// Private immediate buffers, one per type: quads and lines are emitted
	// interleaved (ChunkBorderRenderer), and a single immediate source would
	// flush one type and recycle its builder the moment the other is requested.
	// 64 KiB start each; BufferBuilder grows on demand. Never shared with
	// vanilla/Iris, so nothing else ever flushes or defers them.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new BufferBuilder(1 << 16));
	private static final MultiBufferSource.BufferSource IMMEDIATE_LINES =
			MultiBufferSource.immediate(new BufferBuilder(1 << 16));

	private OriginRenderTypes(String name, int glMode, Runnable setup, Runnable clear) {
		super(name, DefaultVertexFormat.POSITION_COLOR, glMode, 1 << 16, false, false, setup, clear);
	}

	// ---- the after-shaders BANK (render/AfterShadersPass) ----
	// While an Iris shader pack is in use, the overlays that must stay above the
	// pack's clouds / fog (waypoints, party pings, WorldEdit CUI, Chunk Borders)
	// are wrapped in AfterShadersPass.bankBegin()/bankEnd(), which sets `banking`:
	// begin()/beginLines() then hand out these sources, draw() leaves them alone, and
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
	static {
		newBank();
	}

	private static void newBank() {
		bankQuads = MultiBufferSource.immediate(new BufferBuilder(1 << 16));
		bankLines = MultiBufferSource.immediate(new BufferBuilder(1 << 14));
		bankExtra = MultiBufferSource.immediate(new BufferBuilder(1 << 14));
	}

	/** True while the current overlay's vertices go to the after-shaders bank. */
	public static boolean banking() {
		return banking;
	}

	/** The bank source for beginLines()'s type while banking. Use only while banking(). */
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
		newBank();
	}

	/** The consumer to emit OVERLAY_QUADS vertices into (vertex + color + endVertex). */
	public static VertexConsumer begin() {
		return (banking ? bankQuads : IMMEDIATE).getBuffer(OVERLAY_QUADS);
	}

	/** The consumer to emit WORLD_LINES vertex pairs into. POSITION_COLOR: a
	 *  .normal(...) call is accepted and ignored (BufferVertexConsumer skips an
	 *  element the format doesn't have). */
	public static VertexConsumer beginLines() {
		return (banking ? bankLines : IMMEDIATE_LINES).getBuffer(WORLD_LINES);
	}

	/** Draws everything emitted since begin()/beginLines(), NOW, with the current
	 *  matrices and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		if (banking) {
			return;   // banked: AfterShadersPass.run() draws it after the pack's composite
		}
		IMMEDIATE.endBatch();
		IMMEDIATE_LINES.endBatch();
	}
}
