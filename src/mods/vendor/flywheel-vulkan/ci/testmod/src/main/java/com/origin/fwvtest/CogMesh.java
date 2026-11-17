package com.origin.fwvtest;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import dev.engine_room.flywheel.api.vertex.VertexList;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * A cog in the unit block: a hub box plus 8 teeth around the Y axis, as quads (Flywheel's
 * QuadMesh layout: 4 vertices per quad). Every face is wound counter-clockwise seen from
 * outside, checked against its normal, so back-face culling keeps the outside.
 */
final class CogMesh implements VertexList {
	private final float[] data; // x y z u v nx ny nz per vertex
	private final int count;

	private CogMesh(float[] data) {
		this.data = data;
		this.count = data.length / 8;
	}

	static CogMesh build(TextureAtlasSprite sprite) {
		List<float[]> out = new ArrayList<>();
		box(out, sprite, new Matrix4f(), 0.25f, 0.4f, 0.25f, 0.75f, 0.6f, 0.75f);
		for (int k = 0; k < 8; k++) {
			Matrix4f m = new Matrix4f().translate(0.5f, 0.5f, 0.5f)
					.rotateY((float) (k * Math.PI / 4))
					.translate(-0.5f, -0.5f, -0.5f);
			box(out, sprite, m, 0.44f, 0.42f, 0.72f, 0.56f, 0.58f, 0.98f);
		}
		float[] all = new float[out.size() * 8];
		for (int i = 0; i < out.size(); i++) {
			System.arraycopy(out.get(i), 0, all, i * 8, 8);
		}
		return new CogMesh(all);
	}

	private static void box(List<float[]> out, TextureAtlasSprite s, Matrix4f m,
			float x0, float y0, float z0, float x1, float y1, float z1) {
		float[][] c = {
				{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
				{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1},
		};
		int[][] faces = {
				{0, 3, 2, 1}, // -Z
				{4, 5, 6, 7}, // +Z
				{0, 4, 7, 3}, // -X
				{1, 2, 6, 5}, // +X
				{0, 1, 5, 4}, // -Y
				{3, 7, 6, 2}, // +Y
		};
		float[][] normals = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
		float[][] uv = {{s.getU0(), s.getV1()}, {s.getU0(), s.getV0()}, {s.getU1(), s.getV0()}, {s.getU1(), s.getV1()}};
		for (int f = 0; f < 6; f++) {
			Vector3f[] p = new Vector3f[4];
			for (int i = 0; i < 4; i++) {
				float[] q = c[faces[f][i]];
				p[i] = m.transformPosition(new Vector3f(q[0], q[1], q[2]));
			}
			Vector3f n = m.transformDirection(new Vector3f(normals[f])).normalize();
			Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
			boolean flip = cross.dot(n) < 0;
			for (int i = 0; i < 4; i++) {
				Vector3f v = p[flip ? 3 - i : i];
				out.add(new float[] {v.x, v.y, v.z, uv[i][0], uv[i][1], n.x, n.y, n.z});
			}
		}
	}

	@Override public float x(int i) { return data[i * 8]; }
	@Override public float y(int i) { return data[i * 8 + 1]; }
	@Override public float z(int i) { return data[i * 8 + 2]; }
	@Override public float r(int i) { return 1f; }
	@Override public float g(int i) { return 1f; }
	@Override public float b(int i) { return 1f; }
	@Override public float a(int i) { return 1f; }
	@Override public float u(int i) { return data[i * 8 + 3]; }
	@Override public float v(int i) { return data[i * 8 + 4]; }
	@Override public int overlay(int i) { return OverlayTexture.NO_OVERLAY; }
	// 0, like baked block models: Flywheel takes max(mesh light, instance light), so the
	// instance's light decides.
	@Override public int light(int i) { return 0; }
	@Override public float normalX(int i) { return data[i * 8 + 5]; }
	@Override public float normalY(int i) { return data[i * 8 + 6]; }
	@Override public float normalZ(int i) { return data[i * 8 + 7]; }
	@Override public int vertexCount() { return count; }
}
