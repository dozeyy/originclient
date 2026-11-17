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

	// Shader-pack pipelines per PackDraw role, valid while the epoch matches (no per-frame lookups).
	final long[] packPipeline = new long[2];
	final int[] packEpoch = { -1, -1 };
	private VkShaders.ProgramKey programKey;
	private VkPipelines.StateKey stateKey;

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

	/** Constant per draw: the instancer's type and environment and this mesh's material never change. */
	VkShaders.ProgramKey programKey() {
		if (programKey == null) {
			programKey = VkShaders.ProgramKey.of(instancer.key.type(), instancer.key.environment().contextShader(), material);
		}
		return programKey;
	}

	VkPipelines.StateKey stateKey() {
		if (stateKey == null) {
			stateKey = VkPipelines.StateKey.of(material);
		}
		return stateKey;
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
