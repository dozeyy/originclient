package com.origin.flywheelvulkan.backend;

import dev.engine_room.flywheel.api.material.Material;

/** One mesh of one instancer's model: Flywheel's InstancedDraw, minus the GL. */
final class VkDraw {
	final VkInstancer<?> instancer;
	final VkMeshPool.PooledMesh mesh;
	final Material material;
	final int bias;
	final int indexOfMeshInModel;
	private final VkMeshPool pool;
	private boolean deleted;

	VkDraw(VkInstancer<?> instancer, VkMeshPool pool, VkMeshPool.PooledMesh mesh, Material material, int bias, int indexOfMeshInModel) {
		this.instancer = instancer;
		this.pool = pool;
		this.mesh = mesh;
		this.material = material;
		this.bias = bias;
		this.indexOfMeshInModel = indexOfMeshInModel;
	}

	int bias() {
		return bias;
	}

	int indexOfMeshInModel() {
		return indexOfMeshInModel;
	}

	Material material() {
		return material;
	}

	boolean deleted() {
		return deleted;
	}

	void delete() {
		if (!deleted) {
			deleted = true;
			pool.release(mesh);
		}
	}
}
