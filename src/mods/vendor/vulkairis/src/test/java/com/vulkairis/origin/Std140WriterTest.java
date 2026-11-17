package com.vulkairis.origin;

import com.vulkairis.origin.uniforms.GlProjection;
import com.vulkairis.origin.uniforms.Std140Writer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Std140WriterTest {
	private static ByteBuffer buf(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static final double[] MAT4 = {
			1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

	@Test
	void scalarsAndIntegers() {
		ByteBuffer b = buf(32);
		assertTrue(Std140Writer.write(b, 4, 32, "float", new double[]{0.25}));
		assertTrue(Std140Writer.write(b, 8, 32, "int", new double[]{23999}));
		assertTrue(Std140Writer.write(b, 12, 32, "bool", new double[]{1}));
		assertEquals(0.25f, b.getFloat(4));
		assertEquals(23999, b.getInt(8));
		assertEquals(1, b.getInt(12));
	}

	@Test
	void vectorsArePacked() {
		ByteBuffer b = buf(32);
		Std140Writer.write(b, 16, 32, "vec3", new double[]{1, 2, 3});
		Std140Writer.write(b, 0, 32, "ivec2", new double[]{7, 9});
		assertEquals(1f, b.getFloat(16));
		assertEquals(2f, b.getFloat(20));
		assertEquals(3f, b.getFloat(24));
		assertEquals(7, b.getInt(0));
		assertEquals(9, b.getInt(4));
	}

	@Test
	void mat4IsColumnMajorContiguous() {
		ByteBuffer b = buf(64);
		Std140Writer.write(b, 0, 64, "mat4x4", MAT4);
		for (int i = 0; i < 16; i++) {
			assertEquals((float) MAT4[i], b.getFloat(i * 4));
		}
	}

	@Test
	void mat3TakesUpperLeftOfMat4WithColumnStride16() {
		ByteBuffer b = buf(48);
		Std140Writer.write(b, 0, 48, "mat3x3", MAT4);
		// column 0 = 1,2,3 ; column 1 = 5,6,7 ; column 2 = 9,10,11, each column on 16 bytes
		assertEquals(1f, b.getFloat(0));
		assertEquals(3f, b.getFloat(8));
		assertEquals(0f, b.getFloat(12));
		assertEquals(5f, b.getFloat(16));
		assertEquals(7f, b.getFloat(24));
		assertEquals(9f, b.getFloat(32));
		assertEquals(11f, b.getFloat(40));
	}

	@Test
	void arraysUseSixteenByteStride() {
		ByteBuffer b = buf(64);
		Std140Writer.write(b, 0, 64, "float[3]", new double[]{1, 2, 3});
		assertEquals(1f, b.getFloat(0));
		assertEquals(2f, b.getFloat(16));
		assertEquals(3f, b.getFloat(32));
		ByteBuffer v = buf(32);
		Std140Writer.write(v, 0, 32, "vec4[2]", new double[]{1, 2, 3, 4, 5, 6, 7, 8});
		assertEquals(4f, v.getFloat(12));
		assertEquals(5f, v.getFloat(16));
	}

	@Test
	void mat4ArrayFromConcatenatedMatrices() {
		double[] two = new double[32];
		two[0] = 1;
		two[16] = 2;
		two[31] = 3;
		ByteBuffer b = buf(128);
		Std140Writer.write(b, 0, 128, "mat4x4[2]", two);
		assertEquals(1f, b.getFloat(0));
		assertEquals(2f, b.getFloat(64));
		assertEquals(3f, b.getFloat(124));
	}

	@Test
	void neverWritesPastLimitAndRejectsUnsupported() {
		ByteBuffer b = buf(64);
		Std140Writer.write(b, 0, 8, "vec4", new double[]{1, 2, 3, 4});
		assertEquals(2f, b.getFloat(4));
		assertEquals(0f, b.getFloat(8));
		assertFalse(Std140Writer.write(b, 0, 64, "dvec3", new double[]{1, 2, 3}));
		assertFalse(Std140Writer.write(b, 0, 64, "struct", new double[]{1}));
		assertFalse(Std140Writer.write(b, 0, 64, "float[]", new double[]{1}));
	}

	@Test
	void glProjectionMatchesOpenGlPerspective() {
		Matrix4f vk = new Matrix4f().setPerspective(1.2f, 16f / 9f, 0.05f, 512f, true);
		Matrix4f gl = new Matrix4f().setPerspective(1.2f, 16f / 9f, 0.05f, 512f, false);
		Matrix4f converted = GlProjection.toGlDepth(vk, new Matrix4f());
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				assertEquals(gl.get(c, r), converted.get(c, r), 1e-4f, "m" + c + r);
			}
		}
		// Stored depth is the same number on both APIs: GL window depth == Vulkan NDC depth.
		Vector4f p = new Vector4f(0.3f, -0.2f, -37f, 1f);
		Vector4f a = vk.transform(new Vector4f(p));
		Vector4f g = gl.transform(new Vector4f(p));
		assertEquals(a.z / a.w, (g.z / g.w + 1f) / 2f, 1e-5f);
	}
}
