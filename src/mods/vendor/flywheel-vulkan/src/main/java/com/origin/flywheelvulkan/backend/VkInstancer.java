package com.origin.flywheelvulkan.backend;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.FrustumIntersection;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceWriter;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.instance.PosedInstance;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.ResourceLocation;

/**
 * One instancer of the Vulkan backend. Instances live CPU side (BaseInstancer); every frame the
 * visible ones are written straight into that frame's instance storage buffer.
 *
 * <p>{@link #parallelUpdate} is Flywheel 1.0.6's InstancedInstancer.parallelUpdate (MIT License,
 * Copyright (c) 2021-2024 Jozufozu; see LICENSE_flywheel-vulkan), unchanged apart from not
 * tracking GPU-side changes, since this backend rewrites visible instances every frame.
 */
final class VkInstancer<I extends Instance> extends BaseInstancer<I> {
	/** How the CPU culls this type; one per Flywheel cull shader it knows. */
	enum Cull {
		TRANSFORMED, POSED, ORIENTED, NONE;

		private static final ResourceLocation T = ResourceUtil.rl("instance/cull/transformed.glsl");
		private static final ResourceLocation P = ResourceUtil.rl("instance/cull/posed.glsl");
		private static final ResourceLocation O = ResourceUtil.rl("instance/cull/oriented.glsl");

		/** Unknown cull shaders (Create's own instance types) are never culled: always correct, just not skipped. */
		static Cull of(ResourceLocation cullShader) {
			if (T.equals(cullShader)) return TRANSFORMED;
			if (P.equals(cullShader)) return POSED;
			if (O.equals(cullShader)) return ORIENTED;
			return NONE;
		}
	}

	final InstancerKey<I> key;
	final int strideBytes;
	private final InstanceWriter<I> writer;
	private final Cull cull;
	final List<VkDraw> draws = new ArrayList<>();

	/** This frame's region in the instance buffer (set by the draw manager). */
	long regionOffset;
	int visibleCount;

	VkInstancer(InstancerKey<I> key, Recreate<I> recreate) {
		super(key, recreate);
		this.key = key;
		this.strideBytes = InstanceRegions.strideBytes(type.layout().byteSize());
		this.writer = type.writer();
		this.cull = Cull.of(type.cullShader());
	}

	int instanceCountUnsafe() {
		return instances.size();
	}

	/**
	 * Writes every visible instance from {@code ptr} on, {@link #strideBytes} apart.
	 * {@code embedPose} is the embedding's composed pose, or null for the world itself.
	 */
	int writeVisible(long ptr, FrustumIntersection frustum, Matrix4f embedPose, Vector4f sphere, Vector3f scratch) {
		// Nothing reads the GPU-side change bits here (every visible instance is rewritten), so keep them empty.
		changed.clear();
		var model = key.model().boundingSphere();
		int n = 0;
		for (int i = 0, size = instances.size(); i < size; i++) {
			I instance = instances.get(i);
			if (frustum != null && !visible(instance, model, frustum, embedPose, sphere, scratch)) {
				continue;
			}
			writer.write(ptr + (long) n * strideBytes, instance);
			n++;
		}
		return n;
	}

	/** Mirrors the GPU cull for the lib types; anything else (or a mismatched class) counts as visible. */
	private boolean visible(I instance, org.joml.Vector4fc model, FrustumIntersection frustum, Matrix4f embedPose,
			Vector4f sphere, Vector3f scratch) {
		sphere.set(model);
		if (cull == Cull.TRANSFORMED && instance instanceof TransformedInstance t) {
			CullMath.transformSphere(t.pose, sphere);
		} else if (cull == Cull.POSED && instance instanceof PosedInstance p) {
			CullMath.transformSphere(p.pose, sphere);
		} else if (cull == Cull.ORIENTED && instance instanceof OrientedInstance o) {
			CullMath.orientSphere(o.posX, o.posY, o.posZ, o.pivotX, o.pivotY, o.pivotZ, o.rotation, sphere, scratch);
		} else {
			return true;
		}
		if (embedPose != null) {
			CullMath.transformSphere(embedPose, sphere);
		}
		return CullMath.visible(frustum, sphere);
	}

	/** Writes the single instance at {@code index}; false if it no longer exists. */
	boolean writeOne(long ptr, int index) {
		if (index < 0 || index >= instances.size()) {
			return false;
		}
		writer.write(ptr, instances.get(index));
		return true;
	}

	@Override
	public void parallelUpdate() {
		if (deleted.isEmpty()) {
			return;
		}

		// Figure out which elements are to be removed.
		final int oldSize = this.instances.size();
		int removeCount = deleted.cardinality();

		if (oldSize == removeCount) {
			clear();
			return;
		}

		final int newSize = oldSize - removeCount;

		// Start from the first deleted index.
		int writePos = deleted.nextSetBit(0);

		if (writePos < newSize) {
			// Since we'll be shifting everything into this space we can consider it all changed.
			changed.set(writePos, newSize);
		}

		// We definitely shouldn't consider the deleted instances as changed though,
		// else we might try some out of bounds accesses later.
		changed.clear(newSize, oldSize);

		// Punch out the deleted instances, shifting over surviving instances to fill their place.
		for (int scanPos = writePos; (scanPos < oldSize) && (writePos < newSize); scanPos++, writePos++) {
			// Find next non-deleted element.
			scanPos = deleted.nextClearBit(scanPos);

			if (scanPos != writePos) {
				// Grab the old instance/handle from scanPos...
				var handle = handles.get(scanPos);
				I instance = instances.get(scanPos);

				// ... and move it to writePos.
				handles.set(writePos, handle);
				instances.set(writePos, instance);

				// Make sure the handle knows it's been moved
				handle.index = writePos;
			}
		}

		deleted.clear();
		instances.subList(newSize, oldSize)
				.clear();
		handles.subList(newSize, oldSize)
				.clear();
	}

	@Override
	public void delete() {
		for (VkDraw draw : draws) {
			draw.delete();
		}
		draws.clear();
	}
}
