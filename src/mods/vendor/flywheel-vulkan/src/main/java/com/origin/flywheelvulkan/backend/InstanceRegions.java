package com.origin.flywheelvulkan.backend;

/**
 * Packs every instancer's visible instances into one per-frame storage buffer.
 *
 * <p>The shader finds an instance at {@code gl_InstanceIndex * strideUints} (SsboInstanceComponent),
 * and gl_InstanceIndex starts at the draw's firstInstance. So each instancer's region starts at a
 * byte offset that is a multiple of its own stride, and its firstInstance is offset / stride.
 * Instancers of different types (different strides) share the buffer that way, one descriptor
 * for all of them.
 */
public final class InstanceRegions {
	private long cursor;

	public void reset() {
		cursor = 0;
	}

	/** Reserves room for {@code count} instances of {@code strideBytes}; returns the region's byte offset. */
	public long add(int count, int strideBytes) {
		if (strideBytes <= 0 || strideBytes % 4 != 0) {
			throw new IllegalArgumentException("stride must be a positive multiple of 4: " + strideBytes);
		}
		long offset = alignUp(cursor, strideBytes);
		cursor = offset + (long) count * strideBytes;
		return offset;
	}

	public long totalBytes() {
		return cursor;
	}

	/** firstInstance for a region; the byte offset is always a multiple of the stride. */
	public static int firstInstance(long offset, int strideBytes) {
		long first = offset / strideBytes;
		if (first * strideBytes != offset || first > Integer.MAX_VALUE) {
			throw new IllegalStateException("region " + offset + " not addressable with stride " + strideBytes);
		}
		return (int) first;
	}

	/** SsboInstanceComponent: ceilingDiv(layout.byteSize(), 4) uints per instance. */
	public static int strideBytes(int layoutByteSize) {
		return ((layoutByteSize + 3) / 4) * 4;
	}

	static long alignUp(long value, long alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}
}
