package com.origin.client.client.mixin;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hitbox mod on 1.21.11, where hitboxes moved AGAIN: the submit-era
// HitboxFeatureRenderer (1.21.10) is gone, replaced by the new gizmo system --
// EntityHitboxDebugRenderer emits CuboidGizmo/LineGizmo primitives, and the
// gizmo pipeline draws them with real per-vertex stroke width (this is the
// same version that added the LineWidth vertex element). The old renderHitbox
// mixin silently never applied, which is why Hitboxes did nothing here.
//
// We own the emit: at emitGizmos HEAD, when the mod is on, walk the same live
// entities vanilla would, apply the Origin filters/styling, emit our own
// gizmos, cancel vanilla's. Mod off = vanilla F3+B untouched.
//
// Unlike the render-state eras (1.21.5-1.21.10), this hook sees LIVE entities
// again, so the full 1.21.1 feature set applies: Show Damaged, Show Hittable,
// Show Look Vector, per-type filtering, distance, color, width, pattern. And
// width here is the REAL stroke width the gizmo pipeline supports natively --
// no quad geometry needed.
//
// OriginClientMod.applyHitboxes toggles the ENTITY_HITBOXES debug entry with
// the mod, which is what makes this renderer run at all.
@Mixin(EntityHitboxDebugRenderer.class)
public class HitboxMixin {

	@Inject(method = "emitGizmos", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
	private void originclient$emitStyled(double camX, double camY, double camZ,
			DebugValueAccess debugValues, Frustum frustum, float partialTick, CallbackInfo ci) {
		if (!Mods.on("hitboxes")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			ci.cancel();
			return;
		}
		double max = Mods.num("hitboxes", "maxDistance");
		int baseCol = OriginColorPicker.liveColor("hitboxes", "lineColor");
		if ((baseCol >>> 24) == 0) {
			baseCol |= 0xFF000000;
		}
		// Real stroke width -- the slider maps straight to gizmo stroke pixels.
		float width = (float) Math.max(1, Math.round(Mods.num("hitboxes", "lineWidth")));
		String pattern = Mods.mode("hitboxes", "linePattern");
		boolean showDamaged = Mods.bool("hitboxes", "showDamaged");
		boolean showHittable = Mods.bool("hitboxes", "showHittable");
		boolean showLook = Mods.bool("hitboxes", "showLookVector");

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (max > 0 && entity.distanceToSqr(camX, camY, camZ) > max * max) {
				continue;
			}
			if (!frustum.isVisible(entity.getBoundingBox())) {
				continue;
			}
			if (!Mods.bool("hitboxes", categoryKey(entity))) {
				continue;
			}
			// Your own box, first person: vanilla suppresses the camera entity in
			// the MAIN pass, but Iris still feeds it to the SHADOW pass -- so it
			// only ever shows as a box-shaped shadow. Skip it in first person
			// (third person still shows it, matching vanilla).
			if (entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
				continue;
			}

			int col = baseCol;
			// Item entities are immune to both tints (Will): you can't attack a
			// dropped item, so colouring one "hittable" or "damaged" would be a lie.
			boolean tintable = !(entity instanceof ItemEntity);
			if (tintable && showDamaged
					&& entity instanceof net.minecraft.world.entity.LivingEntity le && le.hurtTime > 0) {
				col = OriginColorPicker.liveColor("hitboxes", "damagedColor");
			}
			if (tintable && showHittable
					&& mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
					&& ehr.getEntity() == entity) {
				col = OriginColorPicker.liveColor("hitboxes", "hittableColor");
			}
			if ((col >>> 24) == 0) {
				col |= 0xFF000000;
			}

			AABB box = entity.getBoundingBox();
			if (pattern == null || pattern.equals("Solid")) {
				Gizmos.cuboid(box, GizmoStyle.stroke(col, width));
			} else {
				// Dashed/Dotted: emit each visible piece as its own line gizmo.
				// The pattern decides WHERE the pieces are; the stroke width makes
				// each piece thick -- same split as every other version.
				int fcol = col;
				forEachEdge(box, (ax, ay, az, bx, by, bz) ->
						patternSegments(ax, ay, az, bx, by, bz, pattern,
								(sx, sy, sz, ex, ey, ez) ->
										Gizmos.line(new Vec3(sx, sy, sz), new Vec3(ex, ey, ez), fcol, width)));
			}

			// Every entity EXCEPT yourself (your own ray is never visible in the
			// main pass, only as an Iris shadow artifact).
			if (showLook && entity != mc.player) {
				Vec3 eye = entity.getEyePosition(partialTick);
				Vec3 look = entity.getViewVector(partialTick);
				Gizmos.line(eye, eye.add(look.scale(2)), col, width);
			}
		}
		ci.cancel();
	}

	/** Receives each of an AABB's 12 edges as a pair of points. */
	@FunctionalInterface
	private interface EdgeSink {
		void accept(double ax, double ay, double az, double bx, double by, double bz);
	}

	/** Walks an AABB's 12 edges. */
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
