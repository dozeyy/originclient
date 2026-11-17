package com.origin.flywheelvulkan.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.origin.flywheelvulkan.shader.VkLayout;
import com.origin.flywheelvulkan.vk.HostBuffer;
import com.origin.flywheelvulkan.vk.Retirement;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.lib.vertex.VertexView;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Vulkan stand-in for Flywheel's MeshPool (MIT, Jozufozu): every live mesh packed into one vertex
 * buffer (Flywheel's InternalVertex layout, 36 bytes) and one uint32 index buffer. Each mesh gets
 * its own index range, so a draw is vkCmdDrawIndexed(indexCount, n, firstIndex, baseVertex, ...).
 *
 * <p>Whenever meshes come or go the two buffers are rebuilt whole (meshes change on chunk/visual
 * load, not per frame) and the old pair is retired until the GPU is done with it.
 */
final class VkMeshPool {
	static final class PooledMesh {
		final Mesh mesh;
		int refs;
		int baseVertex = -1;
		int firstIndex;

		PooledMesh(Mesh mesh) {
			this.mesh = mesh;
		}

		int indexCount() {
			return mesh.indexCount();
		}

		int vertexCount() {
			return mesh.vertexCount();
		}

		boolean drawable() {
			return refs > 0 && baseVertex >= 0 && mesh.vertexCount() > 0 && mesh.indexCount() > 0;
		}
	}

	private final Map<Mesh, PooledMesh> meshes = new HashMap<>();
	private final List<PooledMesh> list = new ArrayList<>();
	private final VertexView vertexView = InternalVertex.createVertexView();
	private HostBuffer vertices;
	private HostBuffer indices;
	private boolean dirty;

	PooledMesh acquire(Mesh mesh) {
		PooledMesh m = meshes.computeIfAbsent(mesh, k -> {
			PooledMesh p = new PooledMesh(k);
			list.add(p);
			dirty = true;
			return p;
		});
		m.refs++;
		return m;
	}

	void release(PooledMesh m) {
		if (--m.refs <= 0) {
			dirty = true;
		}
	}

	List<PooledMesh> meshes() {
		return list;
	}

	long vertexBuffer() {
		return vertices == null ? 0 : vertices.buffer;
	}

	long indexBuffer() {
		return indices == null ? 0 : indices.buffer;
	}

	/** Rebuild the buffers if meshes changed; call before recording draws for a frame. */
	void flush(Retirement retirement, int slot) {
		if (!dirty) {
			return;
		}
		dirty = false;
		list.removeIf(m -> {
			if (m.refs <= 0) {
				meshes.remove(m.mesh);
				return true;
			}
			return false;
		});

		long vertexBytes = 0;
		long indexCount = 0;
		for (PooledMesh m : list) {
			vertexBytes += (long) m.vertexCount() * VkLayout.VERTEX_STRIDE;
			indexCount += m.indexCount();
		}
		retire(vertices, retirement, slot);
		retire(indices, retirement, slot);
		vertices = null;
		indices = null;
		if (list.isEmpty()) {
			return;
		}
		vertices = HostBuffer.create(Math.max(vertexBytes, 4), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
		indices = HostBuffer.create(Math.max(indexCount * 4, 4), VK_BUFFER_USAGE_INDEX_BUFFER_BIT);

		long vOff = 0;
		int baseVertex = 0;
		int firstIndex = 0;
		for (PooledMesh m : list) {
			m.baseVertex = baseVertex;
			m.firstIndex = firstIndex;
			vertexView.ptr(vertices.ptr() + vOff);
			vertexView.vertexCount(m.vertexCount());
			m.mesh.write(vertexView);
			m.mesh.indexSequence().fill(indices.ptr() + (long) firstIndex * 4, m.indexCount());
			vOff += (long) m.vertexCount() * VkLayout.VERTEX_STRIDE;
			baseVertex += m.vertexCount();
			firstIndex += m.indexCount();
		}
	}

	private static void retire(HostBuffer b, Retirement retirement, int slot) {
		if (b != null) {
			retirement.retire(slot, b::destroy);
		}
	}

	/** Device idle. */
	void destroy() {
		if (vertices != null) {
			vertices.destroy();
		}
		if (indices != null) {
			indices.destroy();
		}
		vertices = null;
		indices = null;
		meshes.clear();
		list.clear();
	}
}
