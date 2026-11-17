package com.origin.flywheelvulkan.backend;

import java.lang.reflect.Field;
import java.util.BitSet;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.uniform.UniformBuffer;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * The three private Flywheel 1.0.6-44 fields the Vulkan backend reads. Each was checked with
 * javap on the pinned jar (2026-11-16). The version gate keeps any other Flywheel build away from
 * here; if a lookup still fails, {@link #resolve()} throws and the backend reports itself
 * unsupported, so Flywheel falls back to vanilla rendering instead of crashing.
 *
 * <ul>
 * <li>{@code Uniforms.ALL_BUFFERS} ({@code private static final UniformBuffer[]}): the five
 *     uniform blocks Flywheel fills every frame (frame, fog, options, player, level). Its GL path
 *     uploads them with glBindBufferRange; this backend copies their CPU side into a Vulkan UBO.
 *     Without it the shaders would read zeroed matrices and nothing would show.</li>
 * <li>{@code UniformBuffer.clientBuffer} ({@code private final MemoryBlock}): the CPU copy of one
 *     block (pointer + 16-aligned size).</li>
 * <li>{@code LightStorage.changed} ({@code private final BitSet}): which light sections changed
 *     since the last upload. Its public upload methods take GL buffers only. Without it, light on
 *     contraptions would never update after the first upload.</li>
 * </ul>
 */
final class FlywheelInternals {
	private static Field allBuffers;
	private static Field clientBuffer;
	private static Field lightChanged;

	private FlywheelInternals() {
	}

	static void resolve() throws ReflectiveOperationException {
		if (lightChanged != null) {
			return;
		}
		Field a = Uniforms.class.getDeclaredField("ALL_BUFFERS");
		Field c = UniformBuffer.class.getDeclaredField("clientBuffer");
		Field l = LightStorage.class.getDeclaredField("changed");
		a.setAccessible(true);
		c.setAccessible(true);
		l.setAccessible(true);
		if (a.getType() != UniformBuffer[].class || c.getType() != MemoryBlock.class || l.getType() != BitSet.class) {
			throw new NoSuchFieldException("Flywheel internals changed shape");
		}
		allBuffers = a;
		clientBuffer = c;
		lightChanged = l;
	}

	/** Index order = Uniforms.FRAME_INDEX..LEVEL_INDEX = VkLayout.FRAME..LEVEL. */
	static MemoryBlock[] uniformBlocks() {
		try {
			UniformBuffer[] buffers = (UniformBuffer[]) allBuffers.get(null);
			MemoryBlock[] out = new MemoryBlock[buffers.length];
			for (int i = 0; i < buffers.length; i++) {
				out[i] = (MemoryBlock) clientBuffer.get(buffers[i]);
			}
			return out;
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	static BitSet lightChanged(LightStorage storage) {
		try {
			return (BitSet) lightChanged.get(storage);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}
}
