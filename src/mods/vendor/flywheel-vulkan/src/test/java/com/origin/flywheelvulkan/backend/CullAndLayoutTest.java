package com.origin.flywheelvulkan.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class CullAndLayoutTest {
	// Camera at (100, 64, 100) world, looking down -Z, 70 degree FOV, like Minecraft's default view.
	private static final Matrix4f VP = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 512f);

	private static FrustumIntersection frustumAtOrigin(int ox, int oy, int oz) {
		return CullMath.frustum(VP, 100, 64, 100, ox, oy, oz, new Matrix4f(), new FrustumIntersection());
	}

	@Test
	void sphereInFrontIsVisibleBehindIsNot() {
		var f = frustumAtOrigin(0, 0, 0);
		assertTrue(CullMath.visible(f, new Vector4f(100, 64, 90, 1)), "10 blocks ahead");
		assertFalse(CullMath.visible(f, new Vector4f(100, 64, 110, 1)), "10 blocks behind");
		assertFalse(CullMath.visible(f, new Vector4f(100, 64, 1000, 1)), "far behind");
		assertTrue(CullMath.visible(f, new Vector4f(100, 64, 100.5f, 1)), "sphere around the camera");
	}

	@Test
	void renderOriginShiftsInstanceSpace() {
		// Engine render origin at (96, 64, 96): an instance at (4, 0, -6) is world (100, 64, 90), ahead.
		var f = frustumAtOrigin(96, 64, 96);
		assertTrue(CullMath.visible(f, new Vector4f(4, 0, -6, 0.5f)));
		assertFalse(CullMath.visible(f, new Vector4f(4, 0, 14, 0.5f)));
	}

	@Test
	void sphereStraddlingTheSidePlaneIsKept() {
		var f = frustumAtOrigin(0, 0, 0);
		// Far off to the side at 10 blocks depth: out with a small radius, in with a big one.
		assertFalse(CullMath.visible(f, new Vector4f(140, 64, 90, 1)));
		assertTrue(CullMath.visible(f, new Vector4f(140, 64, 90, 40)));
	}

	@Test
	void transformedSphereFollowsPoseAndScale() {
		var s = new Vector4f(0.5f, 0.5f, 0.5f, 0.87f);
		CullMath.transformSphere(new Matrix4f().translate(10, 0, 0).scale(1, 3, 2), s);
		assertEquals(10.5f, s.x, 1e-5);
		assertEquals(1.5f, s.y, 1e-5);
		assertEquals(1.0f, s.z, 1e-5);
		assertEquals(0.87f * 3, s.w, 1e-5, "radius grows by the largest axis scale");
	}

	@Test
	void orientedSphereRotatesAboutThePivot() {
		var s = new Vector4f(1.5f, 0.5f, 0.5f, 0.5f);
		CullMath.orientSphere(4, 0, 0, 0.5f, 0.5f, 0.5f, new Quaternionf().rotateY((float) Math.toRadians(90)), s, new Vector3f());
		// (1, 0, 0) from the pivot rotated 90 degrees about Y is (0, 0, -1).
		assertEquals(4.5f, s.x, 1e-5);
		assertEquals(0.5f, s.y, 1e-5);
		assertEquals(-0.5f, s.z, 1e-5);
		assertEquals(0.5f, s.w, 1e-5);
	}

	@Test
	void regionsStartOnTheirOwnStride() {
		var r = new InstanceRegions();
		long a = r.add(3, 84);   // TransformedInstance: 21 uints
		long b = r.add(5, 64);   // a 16-uint type
		long c = r.add(2, 84);
		long d = r.add(0, 52);   // empty region still aligned, takes no room
		long e = r.add(1, 52);
		assertEquals(0, a);
		assertEquals(0, b % 64);
		assertTrue(b >= 3 * 84);
		assertEquals(0, c % 84);
		assertTrue(c >= b + 5 * 64);
		assertEquals(d, e);
		assertEquals(0, e % 52);
		assertEquals(e + 52, r.totalBytes());
		assertEquals(b / 64, InstanceRegions.firstInstance(b, 64));
		// Regions never overlap: each starts at or after the previous one's end.
		assertTrue(c >= b + 5 * 64 && e >= c + 2 * 84);
	}

	@Test
	void strideMatchesTheShaderUintCount() {
		assertEquals(84, InstanceRegions.strideBytes(84));
		assertEquals(88, InstanceRegions.strideBytes(85));
		assertEquals(4, InstanceRegions.strideBytes(1));
		assertThrows(IllegalArgumentException.class, () -> new InstanceRegions().add(1, 6));
		assertThrows(IllegalStateException.class, () -> InstanceRegions.firstInstance(10, 4));
	}
}
