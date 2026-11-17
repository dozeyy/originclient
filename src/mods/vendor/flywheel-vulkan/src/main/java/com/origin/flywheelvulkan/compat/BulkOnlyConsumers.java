package com.origin.flywheelvulkan.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vertex consumers that accept only {@code putBulkData}, swapped for one that takes per-vertex writes.
 *
 * <p>VulkanMod's Fabric-renderer block context writes every quad vertex by vertex
 * ({@code AbstractRenderContext.bufferQuad}: addVertex / setColor / setUv / ...). Catnip's
 * {@code ShadedBlockSbbBuilder} (Ponder, nested in Create and in Trainworks) implements only
 * {@code putBulkData} and throws "ShadedBlockSbbBuilder only supports putBulkData!" for anything
 * else. Every SuperByteBuffer bake of a non-vanilla model (CachedBuffers.block/partial for the
 * kinetic block-entity renderers, contraption and schematic meshes) reaches that consumer through
 * {@code ModelBlockRenderer.tesselateBlock}, which VulkanMod hands to the context; on OpenGL the
 * Create port and Trainworks dodge it only when Sodium is loaded, and Vulkan mode switches Sodium off.
 * So the whole class crashed in Vulkan mode as soon as such a block came into view
 * (crash-2026-11-17_03.40.00 / 03.42.48, Trainworks kinetic renderers).
 *
 * <p>The fix is the builder's own escape hatch: {@code unwrap(boolean shade)} records a shade-group
 * boundary at the current vertex and returns the raw BufferBuilder underneath, which supports the
 * whole VertexConsumer API. Called once per quad with that quad's own diffuse flag, it records
 * exactly what {@code putBulkData} would have recorded ({@code prepareForGeometry(quad.isShade())}),
 * so the buffer's draw-time diffuse matches the bulk path.
 *
 * <p>Looked up by class name (Ponder is optional and not on this module's classpath). Any failure
 * leaves the consumer untouched and logs once: the game then behaves as it did before this fix.
 */
public final class BulkOnlyConsumers {
	private static final Logger LOG = LoggerFactory.getLogger("origin-flywheel-vulkan");

	/** Catnip-Fabric 1.21.1: Ponder 1.0.44 (Trainworks) and 1.0.82 (Create 6.0.0.0), javap 2026-11-17. */
	static final String SHADED_SBB = "net.createmod.catnip.render.ShadedBlockSbbBuilder";

	private static final MethodType UNWRAP_TYPE = MethodType.methodType(Object.class, Object.class, boolean.class);
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean FIRST = new AtomicBoolean();

	/** Per consumer class: its unwrap(boolean) handle, or empty for every other consumer. */
	private static final ClassValue<Optional<MethodHandle>> UNWRAP = new ClassValue<>() {
		@Override
		protected Optional<MethodHandle> computeValue(Class<?> type) {
			for (Class<?> c = type; c != null; c = c.getSuperclass()) {
				if (!c.getName().equals(SHADED_SBB)) continue;
				try {
					MethodHandle h = MethodHandles.publicLookup().findVirtual(c, "unwrap",
							MethodType.methodType(c.getMethod("unwrap", boolean.class).getReturnType(), boolean.class));
					return Optional.of(h.asType(UNWRAP_TYPE));
				} catch (ReflectiveOperationException | RuntimeException e) {
					warnOnce("no usable unwrap(boolean) on " + c.getName() + " (" + e + ")");
					return Optional.empty();
				}
			}
			return Optional.empty();
		}
	};

	private BulkOnlyConsumers() {
	}

	/**
	 * The consumer to write one quad into vertex by vertex: {@code consumer} itself, or for a
	 * bulk-only catnip builder its unwrapped BufferBuilder with the quad's shade group recorded.
	 *
	 * @param diffuseShade the quad's diffuse flag ({@code !material.disableDiffuse()},
	 *                     what {@code BakedQuad.isShade()} is for a vanilla quad)
	 */
	public static Object forPerVertexWrites(Object consumer, boolean diffuseShade) {
		if (consumer == null) return null;
		Optional<MethodHandle> unwrap = UNWRAP.get(consumer.getClass());
		if (unwrap.isEmpty()) return consumer;
		try {
			Object raw = unwrap.get().invokeExact(consumer, diffuseShade);
			if (raw != null && FIRST.compareAndSet(false, true)) {
				LOG.info("[flywheel-vulkan] {} unwrapped for VulkanMod's per-vertex writes (first quad)",
						consumer.getClass().getSimpleName());
			}
			return raw != null ? raw : consumer;
		} catch (Throwable t) {
			warnOnce("unwrap failed on " + consumer.getClass().getName() + " (" + t + ")");
			return consumer;
		}
	}

	private static void warnOnce(String why) {
		if (WARNED.compareAndSet(false, true)) {
			LOG.warn("[flywheel-vulkan] bulk-only vertex consumer left as is: {}", why);
		}
	}
}
