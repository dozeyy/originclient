package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 *
 * Why not RenderType.debugQuads() through the frame's shared buffer (what the
 * outline used before): Iris swaps the level's buffer source for its own
 * fully-buffered one during level rendering even with shaders OFF, so quads
 * handed to it are drawn LATER, in Iris's flush, under whatever matrices/state
 * are current then. The outline then disagreed with the depth buffer it was
 * meant to be tested against -- worst right after a zoom -- and its hidden back
 * edges showed through the block. So: our own state, and our own buffer that is
 * drawn on the spot (begin() ... draw()), while the world matrices and depth
 * are the ones the frame is using.
 *
 * PER-VERSION DELTA (1.21.10, RenderPipelines era): the GL state lives in a
 * {@link RenderPipeline}, not in RenderType state shards (1.21.1's approach):
 *   DEBUG_FILLED_SNIPPET -- vanilla's position_color world snippet (the one
 *     debugQuads is built on): TRANSLUCENT blend, POSITION_COLOR,
 *   QUADS, withCull(false) -- quads are wound either way,
 *   depth test LEQUAL (the builder default) -- terrain in front hides it,
 *   withDepthWrite(false) -- overlapping translucent faces and anything drawn
 *     after aren't punched out by our depth,
 *   withDepthBias(-1, -10) -- polygon offset toward the camera, so faces lying
 *     ON a block surface win the depth tie instead of z-fighting it (the
 *     pipeline-era stand-in for VIEW_OFFSET_Z_LAYERING).
 *
 * The RenderType wrapping the pipeline is made by vanilla's own factory,
 * RenderType.create(String, int, boolean, boolean, RenderPipeline,
 * CompositeState) on this era -- reached reflectively by SHAPE (parameter
 * types, never names, so it survives obfuscation), because it is not public and
 * this module has no access widener. The same lookup also matches 1.21.11's
 * create(String, RenderSetup), so the file is the 1.21.11 module's apart from
 * the RenderType package and ResourceLocation. If the factory is ever not
 * found, the type falls back to vanilla's RenderType.debugQuads() -- still
 * drawn on the spot through the private buffer (the part that fixes the
 * outline), only without the no-depth-write / polygon-offset refinements.
 * Never a crash (mandate 4).
 */
public final class OriginRenderTypes {

	private static final String NAME = "originclient_overlay_quads";
	private static final int BUFFER_SIZE = 1 << 16;

	// Private immediate buffer. 64 KiB start; ByteBufferBuilder grows on demand.
	// Never shared with vanilla/Iris, so nothing else ever flushes or defers it.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new ByteBufferBuilder(BUFFER_SIZE));

	private static RenderType overlayQuads;

	private OriginRenderTypes() {
	}

	/** The consumer to emit overlay-quad vertices into (addVertex + setColor). */
	public static VertexConsumer begin() {
		return IMMEDIATE.getBuffer(overlayQuads());
	}

	/** Draws everything emitted since begin(), NOW, with the current matrices
	 *  and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		IMMEDIATE.endBatch();
	}

	/** Translucent, depth-tested, non-depth-writing world quads (see class doc).
	 *  Built on first use, on the render thread (pipelines compile lazily). */
	private static RenderType overlayQuads() {
		if (overlayQuads == null) {
			RenderType built = null;
			try {
				RenderPipeline pipeline = RenderPipelines.register(
						RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
								.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/overlay_quads"))
								.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
								.withCull(false)
								.withDepthWrite(false)
								.withDepthBias(-1.0f, -10.0f)
								.build());
				built = createFromPipeline(pipeline);
				if (built == null) {
					com.origin.client.OriginClient.LOGGER.warn("Origin: no RenderType factory matched; overlay quads use debugQuads");
				}
			} catch (Throwable t) {
				com.origin.client.OriginClient.LOGGER.warn("Origin: overlay render type unavailable, using debugQuads", t);
			}
			if (built == null) {
				built = RenderType.debugQuads();
			}
			overlayQuads = built;
		}
		return overlayQuads;
	}

	/** Finds vanilla's static RenderType factory by parameter shape and calls it
	 *  with an otherwise-default state. Returns null when no shape matches.
	 *  1.21.11:        create(String, RenderSetup)  + RenderSetup.builder(pipeline).createRenderSetup()
	 *  1.21.5-1.21.10: create(String, int, boolean, boolean, RenderPipeline, CompositeState)
	 *                  + CompositeState.builder().createCompositeState(false) */
	private static RenderType createFromPipeline(RenderPipeline pipeline) throws ReflectiveOperationException {
		for (Method m : RenderType.class.getDeclaredMethods()) {
			if (!Modifier.isStatic(m.getModifiers()) || m.isSynthetic()
					|| !RenderType.class.isAssignableFrom(m.getReturnType())) {
				continue;
			}
			Class<?>[] p = m.getParameterTypes();
			if (p.length < 2 || p[0] != String.class) {
				continue;
			}
			boolean pipelineDirect = false;
			for (Class<?> c : p) {
				pipelineDirect |= c == RenderPipeline.class;
			}
			Object[] args = new Object[p.length];
			args[0] = NAME;
			boolean ok = true, sawState = false;
			for (int i = 1; i < p.length && ok; i++) {
				if (p[i] == int.class) {
					args[i] = BUFFER_SIZE;
				} else if (p[i] == boolean.class) {
					args[i] = false;   // affectsCrumbling / sortOnUpload
				} else if (p[i] == RenderPipeline.class) {
					args[i] = pipeline;
				} else {
					Object state = defaultState(p[i], pipeline, pipelineDirect);
					ok = state != null;
					sawState = true;
					args[i] = state;
				}
			}
			if (!ok || !sawState) {
				continue;
			}
			m.setAccessible(true);
			return (RenderType) m.invoke(null, args);
		}
		return null;
	}

	/** A default instance of the factory's state class via its own builder:
	 *  a static builder factory (no-arg when the pipeline is passed to create()
	 *  directly, else taking the pipeline), then the builder method that returns
	 *  the state (no-arg, or the boolean outline flag = false). */
	private static Object defaultState(Class<?> stateClass, RenderPipeline pipeline, boolean pipelineDirect)
			throws ReflectiveOperationException {
		for (Method factory : stateClass.getDeclaredMethods()) {
			Class<?> builderClass = factory.getReturnType();
			if (!Modifier.isStatic(factory.getModifiers()) || factory.isSynthetic()
					|| builderClass == stateClass || builderClass.isPrimitive()) {
				continue;
			}
			Class<?>[] fp = factory.getParameterTypes();
			Object[] fargs;
			if (pipelineDirect && fp.length == 0) {
				fargs = new Object[0];
			} else if (!pipelineDirect && fp.length == 1 && fp[0] == RenderPipeline.class) {
				fargs = new Object[] {pipeline};
			} else {
				continue;
			}
			for (Method build : builderClass.getDeclaredMethods()) {
				if (Modifier.isStatic(build.getModifiers()) || build.isSynthetic() || build.getReturnType() != stateClass) {
					continue;
				}
				Class<?>[] bp = build.getParameterTypes();
				Object[] bargs;
				if (bp.length == 0) {
					bargs = new Object[0];
				} else if (bp.length == 1 && bp[0] == boolean.class) {
					bargs = new Object[] {false};   // no outline pass
				} else {
					continue;
				}
				factory.setAccessible(true);
				build.setAccessible(true);
				return build.invoke(factory.invoke(null, fargs), bargs);
			}
		}
		return null;
	}
}
