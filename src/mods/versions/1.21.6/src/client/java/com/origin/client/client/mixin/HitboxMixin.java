package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hitbox mod: replaces vanilla's plain white box with a fully-styled one so the
// page's settings do something. Per-type filtering + Max Distance decide whether
// to draw; players additionally honour Line Color, Line Width, Show Damaged and
// Show Look Vector; Line Pattern (Solid/Dashed/Dotted) applies to every box.
// We draw into vanilla's own line buffer then cancel the vanilla draw.
@Mixin(EntityRenderDispatcher.class)
public class HitboxMixin {


	private static void drawBox(VertexConsumer c, PoseStack.Pose pose, AABB box,
								float r, float g, float b, float a, int passes, String pattern) {
		double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
		double ccx = (box.minX + box.maxX) / 2, ccy = (box.minY + box.maxY) / 2, ccz = (box.minZ + box.maxZ) / 2;
		for (int p = 0; p < passes; p++) {
			double grow = p * 0.01;
			for (int yi = 0; yi < 2; yi++) {
				for (int zi = 0; zi < 2; zi++) {
					edge(c, pose, box.minX, ys[yi], zs[zi], box.maxX, ys[yi], zs[zi], ccx, ccy, ccz, grow, r, g, b, a, pattern);
				}
			}
			for (int xi = 0; xi < 2; xi++) {
				for (int zi = 0; zi < 2; zi++) {
					edge(c, pose, xs[xi], box.minY, zs[zi], xs[xi], box.maxY, zs[zi], ccx, ccy, ccz, grow, r, g, b, a, pattern);
				}
			}
			for (int xi = 0; xi < 2; xi++) {
				for (int yi = 0; yi < 2; yi++) {
					edge(c, pose, xs[xi], ys[yi], box.minZ, xs[xi], ys[yi], box.maxZ, ccx, ccy, ccz, grow, r, g, b, a, pattern);
				}
			}
		}
	}

	private static void edge(VertexConsumer c, PoseStack.Pose pose, double ax, double ay, double az,
							 double bx, double by, double bz, double ccx, double ccy, double ccz,
							 double grow, float r, float g, float b, float a, String pattern) {
		double aox = ax + Math.signum(ax - ccx) * grow, aoy = ay + Math.signum(ay - ccy) * grow, aoz = az + Math.signum(az - ccz) * grow;
		double box2 = bx + Math.signum(bx - ccx) * grow, boy = by + Math.signum(by - ccy) * grow, boz = bz + Math.signum(bz - ccz) * grow;
		patternLine(c, pose, aox, aoy, aoz, box2, boy, boz, r, g, b, a, pattern);
	}

	private static void patternLine(VertexConsumer c, PoseStack.Pose pose, double ax, double ay, double az,
									double bx, double by, double bz, float r, float g, float b, float a, String pattern) {
		if (pattern == null || pattern.equals("Solid")) {
			seg(c, pose, ax, ay, az, bx, by, bz, r, g, b, a);
			return;
		}
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 0) {
			return;
		}
		double dash = pattern.equals("Dotted") ? 0.08 : 0.22;
		double period = dash * 2;
		for (double s = 0; s < len; s += period) {
			double e = Math.min(len, s + dash);
			double t0 = s / len, t1 = e / len;
			seg(c, pose, ax + dx * t0, ay + dy * t0, az + dz * t0, ax + dx * t1, ay + dy * t1, az + dz * t1, r, g, b, a);
		}
	}

	private static void seg(VertexConsumer c, PoseStack.Pose pose, double ax, double ay, double az,
							double bx, double by, double bz, float r, float g, float b, float a) {
		float nx = (float) (bx - ax), ny = (float) (by - ay), nz = (float) (bz - az);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) {
			nx /= len;
			ny /= len;
			nz /= len;
		}
		c.addVertex(pose, (float) ax, (float) ay, (float) az).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
		c.addVertex(pose, (float) bx, (float) by, (float) bz).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
	}


	// 1.21.5+: hitboxes are pre-extracted into render state (renderHitboxes).
	// The state has no live Entity, so HitboxFactsMixin records the live-entity
	// facts (category, hurt, under the crosshair, is-it-you) at extraction and
	// HitboxFacts hands them back here: Passive / Monsters / Projectiles, Show
	// Damaged, Show Hittable and Show Look Vector work as on 1.21.1. Without
	// facts (that mixin not applied) it falls back to the entity type alone.
	@Inject(method = "renderHitboxes",
			at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
	private static void originclient$filterHitboxState(PoseStack poseStack,
			net.minecraft.client.renderer.entity.state.EntityRenderState state,
			net.minecraft.client.renderer.entity.state.HitboxesRenderState hitboxes,
			net.minecraft.client.renderer.MultiBufferSource buffers, CallbackInfo ci) {
		if (!Mods.on("hitboxes")) {
			return;
		}
		double max = Mods.num("hitboxes", "maxDistance");
		if (max > 0 && state.distanceToCameraSq > max * max) {
			ci.cancel();
			return;
		}
		com.origin.client.client.render.HitboxFactsState facts = com.origin.client.client.render.HitboxFacts.of(state);
		String category = facts != null ? facts.origin$hitboxCategory() : categoryKey(state.entityType);
		if (!Mods.bool("hitboxes", category)) {
			ci.cancel();
			return;
		}
		// Colour and width apply to EVERY entity, not just players -- matching
		// the 1.21.1 rework: player-only styling made Line Color and Line Width
		// silently do nothing on mobs, and the whole mod read as broken.
		int col = OriginColorPicker.liveColor("hitboxes", "lineColor");
		// Tints, as on 1.21.1: item entities are immune (you can't attack a
		// dropped item); the crosshair tint wins over the damage tint.
		if (facts != null && !"items".equals(category)) {
			if (facts.origin$hitboxDamaged() && Mods.bool("hitboxes", "showDamaged")) {
				col = OriginColorPicker.liveColor("hitboxes", "damagedColor");
			}
			if (facts.origin$hitboxHittable() && Mods.bool("hitboxes", "showHittable")) {
				col = OriginColorPicker.liveColor("hitboxes", "hittableColor");
			}
		}
		float r = ((col >> 16) & 0xFF) / 255f;
		float g = ((col >> 8) & 0xFF) / 255f;
		float b = (col & 0xFF) / 255f;
		float a = ((col >>> 24) & 0xFF) / 255f;
		if (a <= 0f) {
			a = 1f;
		}
		int passes = (int) Math.max(1, Math.round(Mods.num("hitboxes", "lineWidth")));
		String pattern = Mods.mode("hitboxes", "linePattern");
		VertexConsumer consumer = buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines());
		PoseStack.Pose pose = poseStack.last();
		// The alpha guard above reassigns `a`, so the lambdas capture copies.
		float fr = r, fg = g, fb = b, fa = a;
		for (Object o : hitboxes.hitboxes()) {
			var hb = (net.minecraft.client.renderer.entity.state.HitboxRenderState) o;
			AABB box = new AABB(hb.x0(), hb.y0(), hb.z0(), hb.x1(), hb.y1(), hb.z1())
					.move(hb.offsetX(), hb.offsetY(), hb.offsetZ());
			// ONE thick line per edge, exactly like the block outline -- not a
			// stack of thin ones (more lines is not a thicker line; see
			// ThickLine). Every pattern gets width: patternSegments decides where
			// the pieces are, ThickLine decides how thick each piece is. At width
			// 1 there's nothing to thicken, so that stays on the cheaper line path.
			if (passes > 1) {
				VertexConsumer q = buffers.getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
				double t = (passes - 1) * 0.012;
				double ccx = (box.minX + box.maxX) / 2, ccy = (box.minY + box.maxY) / 2, ccz = (box.minZ + box.maxZ) / 2;
				forEachEdge(box, (ax, ay, az, bx, by, bz) ->
						patternSegments(ax, ay, az, bx, by, bz, pattern,
								(sx, sy, sz, ex, ey, ez) ->
										com.origin.client.client.render.ThickLine.edge(q, pose,
												sx, sy, sz, ex, ey, ez, ccx, ccy, ccz, t, fr, fg, fb, fa)));
			} else {
				drawBox(consumer, pose, box, fr, fg, fb, fa, 1, pattern);
			}
		}
		// Look line, as on 1.21.1: from the eyes, 2 blocks along the view vector
		// vanilla stored in the state, box colour and pattern. Never your own
		// (it only ever shows as an Iris shadow).
		if (facts != null && !facts.origin$hitboxSelf() && Mods.bool("hitboxes", "showLookVector")) {
			double eye = state.eyeHeight;
			patternLine(consumer, pose, 0, eye, 0, hitboxes.viewX() * 2, eye + hitboxes.viewY() * 2,
					hitboxes.viewZ() * 2, fr, fg, fb, fa, pattern);
		}
		ci.cancel();
	}

	/** Receives each of an AABB's 12 edges as a pair of points. */
	@FunctionalInterface
	private interface EdgeSink {
		void accept(double ax, double ay, double az, double bx, double by, double bz);
	}

	/** Walks an AABB's 12 edges -- the same enumeration drawBox does inline. */
	private static void forEachEdge(AABB box, EdgeSink sink) {
		double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
		for (int yi = 0; yi < 2; yi++) {
			for (int zi = 0; zi < 2; zi++) {
				sink.accept(box.minX, ys[yi], zs[zi], box.maxX, ys[yi], zs[zi]);
			}
		}
		for (int xi = 0; xi < 2; xi++) {
			for (int zi = 0; zi < 2; zi++) {
				sink.accept(xs[xi], box.minY, zs[zi], xs[xi], box.maxY, zs[zi]);
			}
		}
		for (int xi = 0; xi < 2; xi++) {
			for (int yi = 0; yi < 2; yi++) {
				sink.accept(xs[xi], ys[yi], box.minZ, xs[xi], ys[yi], box.maxZ);
			}
		}
	}

	/**
	 * Cuts an edge into the pattern's visible pieces and hands each to `sink`.
	 * The pattern decides WHERE the pieces are, the renderer decides how thick
	 * they are -- which is what lets Dashed/Dotted honour Line Width.
	 */
	private static void patternSegments(double ax, double ay, double az,
										double bx, double by, double bz, String pattern, EdgeSink sink) {
		if (pattern == null || pattern.equals("Solid")) {
			sink.accept(ax, ay, az, bx, by, bz);
			return;
		}
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 0) {
			return;
		}
		double dash = pattern.equals("Dotted") ? 0.08 : 0.22;
		double period = dash * 2;
		for (double s = 0; s < len; s += period) {
			double e = Math.min(len, s + dash);
			double t0 = s / len, t1 = e / len;
			sink.accept(ax + dx * t0, ay + dy * t0, az + dz * t0,
					ax + dx * t1, ay + dy * t1, az + dz * t1);
		}
	}

	private static String categoryKey(net.minecraft.world.entity.EntityType<?> type) {
		if (type == net.minecraft.world.entity.EntityType.PLAYER) return "players";
		if (type == net.minecraft.world.entity.EntityType.ITEM) return "items";
		if (type == net.minecraft.world.entity.EntityType.ITEM_FRAME
				|| type == net.minecraft.world.entity.EntityType.GLOW_ITEM_FRAME) return "itemFrames";
		if (type == net.minecraft.world.entity.EntityType.WITHER_SKULL) return "witherSkulls";
		if (type == net.minecraft.world.entity.EntityType.FIREBALL
				|| type == net.minecraft.world.entity.EntityType.SMALL_FIREBALL
				|| type == net.minecraft.world.entity.EntityType.DRAGON_FIREBALL) return "fireballs";
		if (type == net.minecraft.world.entity.EntityType.FIREWORK_ROCKET) return "fireworks";
		if (type == net.minecraft.world.entity.EntityType.SNOWBALL) return "snowballs";
		if (type == net.minecraft.world.entity.EntityType.ARROW
				|| type == net.minecraft.world.entity.EntityType.SPECTRAL_ARROW) return "arrows";
		if (type == net.minecraft.world.entity.EntityType.EXPERIENCE_ORB) return "expOrbs";
		return "other";
	}

	private static String categoryKey(Entity e) {
		if (e instanceof Player) return "players";
		if (e instanceof ItemEntity) return "items";
		if (e instanceof ItemFrame) return "itemFrames";
		if (e instanceof WitherSkull) return "witherSkulls";
		if (e instanceof AbstractHurtingProjectile) return "fireballs";
		if (e instanceof FireworkRocketEntity) return "fireworks";
		if (e instanceof Snowball) return "snowballs";
		if (e instanceof AbstractArrow) return "arrows";
		if (e instanceof ExperienceOrb) return "expOrbs";
		if (e instanceof Projectile) return "projectiles";
		if (e instanceof Monster) return "monsters";
		if (e instanceof Animal) return "passive";
		return "other";
	}
}
