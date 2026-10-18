package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.origin.client.client.render.GuiTris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Cosmetics screen's tile thumbnails for model items (hats, masks, wings,
 * backpacks): the real model on your character, zoomed to the part it sits
 * on - what 1.21.1's MenuPlayerPreview.renderThumb shows.
 *
 * PER-VERSION DELTA (1.21.11, deferred GUI): 1.21.1 draws each thumbnail as a
 * small 3D player straight into the GUI buffer. Here a 3D draw in the GUI is a
 * picture-in-picture render state, and the big preview already uses the
 * entity one in the same frame; nothing in this module shows several of them
 * sharing a frame safely, so the thumbnails don't use it. Instead the scene is
 * projected here, on the CPU, into 2D textured triangles (GuiTris, the
 * module's deferred-GUI triangle element): your skin's boxes with vanilla's
 * player-model layout and the item's FORMAT.md model (ModelCosmetic.collect),
 * with the same orthographic camera as the big preview (180 deg about Z, a
 * 0.12 rad tilt) and the player renderer's 15/16 scale. Hidden faces are
 * dropped (skin) or painted over (items are drawn far-to-near, like their
 * no-cull render type), and the item is split at the middle of the part it
 * rides: the half behind it first, then the body, then the half in front, so a
 * halo rings the head and wings sit behind the back. Lighting is a soft fixed
 * key light; GLOW parts are drawn full-bright at 80% opacity (the GUI has no
 * additive blend).
 *
 * Fails soft: false (the caller shows an icon) whenever anything is missing.
 * Render thread.
 */
public final class CosmeticThumbs {
	private CosmeticThumbs() {
	}

	private static final float CAMERA_TILT = -0.12f;
	/** PlayerRenderer's model scale. */
	private static final float PLAYER_SCALE = 0.9375f;
	private static final Vector3f KEY = new Vector3f(-0.35f, -0.55f, -0.76f).normalize();

	/** One triangle-pair to draw: screen corners, UVs, colour, and depth (bigger = farther). */
	private static final class Quad {
		final float[] p = new float[8];
		final float[] uv = new float[8];
		int argb;
		float depth;
	}

	private static final Comparator<Quad> FAR_FIRST = (a, b) -> Float.compare(b.depth, a.depth);

	/**
	 * Draws item `id` (worn in `slot`) on your character.
	 *
	 * @param cx, cy  where the point `focus` blocks above the feet goes, in the current GUI pose's units
	 * @param scale   pose units per block
	 * @param turn    degrees, the preview's yaw (0 = facing you, 180 = your back)
	 * @param alpha   0..1 fade
	 */
	public static boolean draw(GuiGraphics g, float cx, float cy, float scale, float focus, float turn,
			Cosmetics.Slot slot, String id, float alpha) {
		try {
			ModelCosmetic model = ModelCosmetic.of(id);
			PlayerSkin skin = MenuPlayerPreview.skinNow();
			if (model == null || skin == null || skin.body() == null || alpha <= 0.01f) {
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			GpuTextureView skinView = mc.getTextureManager().getTexture(skin.body().texturePath()).getTextureView();
			Identifier itemTex = CosmeticCatalog.texture(id);
			GpuTextureView itemView = mc.getTextureManager().getTexture(itemTex).getTextureView();
			if (skinView == null || itemView == null) {
				return false;
			}

			// screen <- entity (the big preview's camera) <- model px (the player renderer's set-up)
			float cosT = (float) Math.cos(CAMERA_TILT);
			Matrix4f base = new Matrix4f()
					.translate(cx, cy + scale * focus * cosT, 0f)
					.scale(-scale, -scale, scale)
					.rotateX(CAMERA_TILT)
					.rotateY((float) Math.toRadians(-turn))
					.scale(-1f, -1f, 1f)
					.scale(PLAYER_SCALE)
					.translate(0f, -1.501f, 0f)
					.scale(1 / 16f);
			int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);

			// the body
			List<Quad> body = new ArrayList<>();
			boolean slim = String.valueOf(skin.model()).toUpperCase(java.util.Locale.ROOT).contains("SLIM");
			skinBoxes(base, slim, mc, body, a);
			body.sort(FAR_FIRST);

			// the item, split at the middle of the part it rides
			boolean onHead = slot != Cosmetics.Slot.BACK;
			Vector3f mid = base.transformPosition(new Vector3f(0f, onHead ? -4f : 6f, 0f));
			Matrix4f itemM = new Matrix4f(base).scale(1f, -1f, -1f);   // the head / body pivots are at the origin standing
			List<Quad> behind = new ArrayList<>(), front = new ArrayList<>();
			float t = (net.minecraft.util.Util.getMillis() % 3_600_000L) / 1000f;
			boolean ok = model.collect(itemM, t, (xyz, uv, nx, ny, nz, kind) -> {
				Quad q = new Quad();
				float depth = 0f;
				for (int v = 0; v < 4; v++) {
					q.p[v * 2] = xyz[v * 3];
					q.p[v * 2 + 1] = xyz[v * 3 + 1];
					depth += xyz[v * 3 + 2];
					q.uv[v * 2] = uv[v * 2];
					q.uv[v * 2 + 1] = uv[v * 2 + 1];
				}
				q.depth = depth / 4f;
				boolean lit = kind == ModelGeometry.LIT;
				float shade = lit ? shade(nx, ny, nz, true) : 1f;
				int qa = kind == ModelGeometry.GLOW ? Math.round(a * 0.8f) : a;
				q.argb = argb(qa, shade);
				(q.depth > mid.z ? behind : front).add(q);
			});
			if (!ok) {
				return false;
			}
			behind.sort(FAR_FIRST);
			front.sort(FAR_FIRST);

			// Three elements, strictly in this order (nextStratum keeps the GUI from re-sorting them).
			emit(g, itemView, behind);
			g.nextStratum();
			emit(g, skinView, body);
			g.nextStratum();
			emit(g, itemView, front);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	private static void emit(GuiGraphics g, GpuTextureView view, List<Quad> quads) {
		if (quads.isEmpty()) {
			return;
		}
		GuiTris tris = GuiTris.textured(RenderPipelines.GUI_TEXTURED, view,
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		for (Quad q : quads) {
			float[] p = q.p, uv = q.uv;
			tris.tri(p[0], p[1], uv[0], uv[1], p[2], p[3], uv[2], uv[3], p[4], p[5], uv[4], uv[5], q.argb);
			tris.tri(p[0], p[1], uv[0], uv[1], p[4], p[5], uv[4], uv[5], p[6], p[7], uv[6], uv[7], q.argb);
		}
		tris.submit(g);
	}

	/** 0.55..1 from a fixed key light (screen space: x right, y down, z away from you). */
	private static float shade(float nx, float ny, float nz, boolean twoSided) {
		float d = nx * KEY.x + ny * KEY.y + nz * KEY.z;
		if (twoSided) {
			d = Math.abs(d);   // no-cull item faces are seen from either side
		}
		return 0.55f + 0.45f * Math.max(0f, d);
	}

	private static int argb(int a, float shade) {
		int c = Math.round(255f * Math.max(0f, Math.min(1f, shade)));
		return (a << 24) | (c << 16) | (c << 8) | c;
	}

	// ------------------------------------------------------------ the skin

	/** Vanilla's player model, standing (PlayerModel.createMesh): boxes in model px, texOffs on the 64 x 64 skin. */
	private static void skinBoxes(Matrix4f m, boolean slim, Minecraft mc, List<Quad> out, int a) {
		float armW = slim ? 3f : 4f, armY = slim ? 2.5f : 2f;
		box(m, out, a, 0, 0, 0f, 0f, 0f, -4, -8, -4, 8, 8, 8, 0f);                 // head
		box(m, out, a, 16, 16, 0f, 0f, 0f, -4, 0, -2, 8, 12, 4, 0f);               // body
		box(m, out, a, 40, 16, -5f, armY, 0f, -armW + 1f, -2, -2, armW, 12, 4, 0f); // right arm
		box(m, out, a, 32, 48, 5f, armY, 0f, -1, -2, -2, armW, 12, 4, 0f);          // left arm
		box(m, out, a, 0, 16, -1.9f, 12f, 0f, -2, 0, -2, 4, 12, 4, 0f);             // right leg
		box(m, out, a, 16, 48, 1.9f, 12f, 0f, -2, 0, -2, 4, 12, 4, 0f);             // left leg
		// the outer layer, as your Skin Customization options say
		if (mc.options.isModelPartEnabled(PlayerModelPart.HAT)) {
			box(m, out, a, 32, 0, 0f, 0f, 0f, -4, -8, -4, 8, 8, 8, 0.5f);
		}
		if (mc.options.isModelPartEnabled(PlayerModelPart.JACKET)) {
			box(m, out, a, 16, 32, 0f, 0f, 0f, -4, 0, -2, 8, 12, 4, 0.25f);
		}
		if (mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE)) {
			box(m, out, a, 40, 32, -5f, armY, 0f, -armW + 1f, -2, -2, armW, 12, 4, 0.25f);
		}
		if (mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE)) {
			box(m, out, a, 48, 48, 5f, armY, 0f, -1, -2, -2, armW, 12, 4, 0.25f);
		}
		if (mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG)) {
			box(m, out, a, 0, 32, -1.9f, 12f, 0f, -2, 0, -2, 4, 12, 4, 0.25f);
		}
		if (mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG)) {
			box(m, out, a, 0, 48, 1.9f, 12f, 0f, -2, 0, -2, 4, 12, 4, 0.25f);
		}
	}

	private static final Vector3f P = new Vector3f(), N = new Vector3f();

	/**
	 * One ModelPart Cube (pivot px, box min + size px, texOffs u/v, grown by
	 * `grow` like a CubeDeformation): vanilla's face order, corner order and
	 * face UVs; faces turned away from you are skipped (vanilla culls them).
	 */
	private static void box(Matrix4f m, List<Quad> out, int a, int u, int v, float px, float py, float pz,
			float bx, float by, float bz, float w, float h, float d, float grow) {
		float x0 = px + bx - grow, y0 = py + by - grow, z0 = pz + bz - grow;
		float x1 = px + bx + w + grow, y1 = py + by + h + grow, z1 = pz + bz + d + grow;
		float u0 = u, u1 = u + d, u2 = u + d + w, u3 = u + d + w + w, u4 = u + d + w + d, u5 = u + d + w + d + w;
		float v0 = v, v1 = v + d, v2 = v + d + h;
		face(m, out, a, x1, y0, z1, x0, y0, z1, x0, y0, z0, x1, y0, z0, u1, v0, u2, v1, 0f, -1f, 0f);
		face(m, out, a, x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, u2, v1, u3, v0, 0f, 1f, 0f);
		face(m, out, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, v1, u1, v2, -1f, 0f, 0f);
		face(m, out, a, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, u1, v1, u2, v2, 0f, 0f, -1f);
		face(m, out, a, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, u2, v1, u4, v2, 1f, 0f, 0f);
		face(m, out, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u4, v1, u5, v2, 0f, 0f, 1f);
	}

	/** One Cube polygon: corners get UVs (uB,vA) (uA,vA) (uA,vB) (uB,vB), as ModelPart.Polygon assigns them. */
	private static void face(Matrix4f m, List<Quad> out, int a,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
			float dx, float dy, float dz, float uA, float vA, float uB, float vB, float nx, float ny, float nz) {
		m.transformDirection(N.set(nx, ny, nz));
		if (N.z >= 0f) {
			return;   // facing away from you
		}
		N.normalize();
		Quad q = new Quad();
		float[] xs = {ax, bx, cx, dx}, ys = {ay, by, cy, dy}, zs = {az, bz, cz, dz};
		float[] us = {uB, uA, uA, uB}, vs = {vA, vA, vB, vB};
		float depth = 0f;
		for (int i = 0; i < 4; i++) {
			m.transformPosition(P.set(xs[i], ys[i], zs[i]));
			q.p[i * 2] = P.x;
			q.p[i * 2 + 1] = P.y;
			q.uv[i * 2] = us[i] / 64f;
			q.uv[i * 2 + 1] = vs[i] / 64f;
			depth += P.z;
		}
		q.depth = depth / 4f;
		q.argb = argb(a, shade(N.x, N.y, N.z, false));
		out.add(q);
	}
}
