package com.origin.flywheelvulkan.backend;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * CPU stand-in for Flywheel's GPU cull pass (internal/indirect/cull.glsl + instance/cull/*.glsl,
 * Flywheel 1.0.6, MIT): a model's bounding sphere is moved by the instance, then by the embedding
 * pose, then tested against the frustum. Pure JOML so it is unit tested.
 *
 * <p>Conservative by construction: JOML's frustum planes assume OpenGL clip depth (-w..w); with
 * a Vulkan 0..w projection the near plane sits behind the real one, so nothing visible is culled.
 */
public final class CullMath {
	private CullMath() {
	}

	/** matrix.glsl transformBoundingSphere: centre through the matrix, radius by its largest axis scale. */
	public static void transformSphere(Matrix4fc m, Vector4f sphere) {
		float x = sphere.x, y = sphere.y, z = sphere.z;
		float cx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
		float cy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
		float cz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
		float s0 = m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02();
		float s1 = m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12();
		float s2 = m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22();
		float scale = (float) Math.sqrt(Math.max(s0, Math.max(s1, s2)));
		sphere.set(cx, cy, cz, sphere.w * scale);
	}

	/** instance/cull/oriented.glsl: rotate about the pivot, then translate. Radius unchanged. */
	public static void orientSphere(float posX, float posY, float posZ, float pivotX, float pivotY, float pivotZ,
			Quaternionfc rotation, Vector4f sphere, Vector3f scratch) {
		scratch.set(sphere.x - pivotX, sphere.y - pivotY, sphere.z - pivotZ);
		rotation.transform(scratch);
		sphere.set(scratch.x + pivotX + posX, scratch.y + pivotY + posY, scratch.z + pivotZ + posZ, sphere.w);
	}

	/**
	 * The frustum in render-origin space. Flywheel's viewProjection is camera-relative; instance
	 * positions are relative to the engine's render origin.
	 */
	public static FrustumIntersection frustum(Matrix4fc cameraRelativeViewProjection, double camX, double camY, double camZ,
			int originX, int originY, int originZ, Matrix4f scratch, FrustumIntersection out) {
		scratch.set(cameraRelativeViewProjection)
				.translate((float) (originX - camX), (float) (originY - camY), (float) (originZ - camZ));
		// true = normalise the planes; testSphere is only correct with normalised planes.
		return out.set(scratch, true);
	}

	public static boolean visible(FrustumIntersection frustum, Vector4f sphere) {
		return frustum.testSphere(sphere.x, sphere.y, sphere.z, sphere.w);
	}
}
