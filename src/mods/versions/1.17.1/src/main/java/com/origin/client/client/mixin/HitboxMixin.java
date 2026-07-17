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
// to draw; Line Color, Line Width, Show Hittable and Show Damaged style every
// entity's box; Show Look Vector is player-only. Line Pattern (Solid/Dashed/
// Dotted) applies to every box. We cancel the vanilla draw and do our own.
@Mixin(EntityRenderDispatcher.class)
public class HitboxMixin {

	/**
	 * The buffer source for the entity currently being rendered.
	 *
	 * renderHitbox is static and only receives vanilla's LINES consumer, but a
	 * genuinely thick line has to be quads. Grabbing a global buffer instead --
	 * renderBuffers().bufferSource() -- does NOT work: it's flushed at a different
	 * point in the frame than the pose we're handed, so the camera-relative
	 * vertices get transformed a second time and the box flies off into the sky.
	 * Capturing the MultiBufferSource that render() is actually using keeps our
	 * quads in the same batch and the same pose context as the hitbox itself.
	 *
	 * Single-threaded render thread, and set immediately before every renderHitbox
	 * call in the same method, so a plain static is safe here.
	 */
	private static net.minecraft.client.renderer.MultiBufferSource originclient$buffers;

	@Inject(method = "render", at = @At("HEAD"))
	private <E extends Entity> void originclient$captureBuffers(E entity, double x, double y, double z,
			float rotationYaw, float partialTick, PoseStack poseStack,
			net.minecraft.client.renderer.MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
		originclient$buffers = buffers;
	}

	// <=1.20 / pre-1.20: renderHitbox passes only partialTick (the r/g/b the
	// 1.21+ signature carries were added later and this mixin never used them).
	@Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
	private static void originclient$filterHitbox(PoseStack poseStack, VertexConsumer consumer, Entity entity,
												  float partialTick, CallbackInfo ci) {
		if (!Mods.on("hitboxes")) {
			return;
		}
		double max = Mods.num("hitboxes", "maxDistance");
		if (max > 0) {
			Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			if (entity.position().distanceTo(cam) > max) {
				ci.cancel();
				return;
			}
		}
		if (!Mods.bool("hitboxes", categoryKey(entity))) {
			ci.cancel();
			return;
		}

		// Your own box, first person: vanilla suppresses the camera entity in the
		// MAIN pass, but Iris still feeds it to the SHADOW pass -- so it never draws
		// as a box, only as a box-shaped shadow on the ground. Skip it entirely for
		// the camera entity in first person (third person still shows it, matching
		// vanilla). Same reasoning as the Show Look Vector exclusion below.
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
			ci.cancel();
			return;
		}

		// Draw our own styled box, then cancel vanilla's.
		//
		// Colour and width apply to EVERY entity, not just players. This used to
		// be wrapped in `if (entity instanceof Player)`, which meant Line Color,
		// chroma and Line Width silently did nothing on mobs -- they kept vanilla's
		// white -- and the whole mod read as broken. Only Show Look Vector is
		// player-specific now (see below), because only players aim.
		int col = OriginColorPicker.liveColor("hitboxes", "lineColor");
		// Item entities are immune to both tints (Will): you can't attack a dropped
		// item, so colouring one "hittable" or "damaged" would be a lie.
		boolean tintable = !(entity instanceof net.minecraft.world.entity.item.ItemEntity);
		// Damaged tint: any LivingEntity can be hurt, not only players.
		if (tintable && Mods.bool("hitboxes", "showDamaged")
				&& entity instanceof net.minecraft.world.entity.LivingEntity le && le.hurtTime > 0) {
			col = OriginColorPicker.liveColor("hitboxes", "damagedColor");
		}
		// Hittable tint: whatever your crosshair is on -- i.e. the thing you'd hit
		// if you attacked right now. Wins over the damaged tint.
		if (tintable && Mods.bool("hitboxes", "showHittable")
				&& Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
				&& ehr.getEntity() == entity) {
			col = OriginColorPicker.liveColor("hitboxes", "hittableColor");
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

		AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
		PoseStack.Pose pose = poseStack.last();
		// ONE thick line per edge, exactly like the block outline -- not a stack of
		// thin ones. Stacking offset copies is what drew the ladder of parallel red
		// lines; more lines is not a thicker line.
		//
		// Every pattern gets width, not just Solid: patternSegments decides where
		// the pieces are, ThickLine decides how thick each piece is. At width 1
		// there's nothing to thicken, so that stays on the cheaper line path.
		if (passes > 1 && originclient$buffers != null) {
			// 1.17.1 has no RenderType.debugQuads() -- FillQuads is the module's
			// bytecode-matched equivalent (see render/FillQuads).
			var q = originclient$buffers.getBuffer(com.origin.client.client.render.FillQuads.INSTANCE);
			double t = (passes - 1) * 0.012;
			double ccx = (box.minX + box.maxX) / 2, ccy = (box.minY + box.maxY) / 2, ccz = (box.minZ + box.maxZ) / 2;
			// The alpha guard above reassigns `a`, so these can't be captured directly.
			float fr = r, fg = g, fb = b, fa = a;
			forEachEdge(box, (ax, ay, az, bx, by, bz) ->
					patternSegments(ax, ay, az, bx, by, bz, pattern,
							(sx, sy, sz, ex, ey, ez) ->
									com.origin.client.client.render.ThickLine.edge(q, pose,
											sx, sy, sz, ex, ey, ez, ccx, ccy, ccz, t, fr, fg, fb, fa)));
		} else {
			drawBox(consumer, pose, box, r, g, b, a, 1, pattern);
		}

		// Every entity EXCEPT yourself.
		//
		// It used to be `entity instanceof Player`, which included the camera
		// entity -- you. That was the worst of both: vanilla suppresses the camera
		// entity in the main pass so you could never actually see your own ray,
		// but Iris still renders it into the SHADOW pass, so it showed up as a line
		// of shadow on the ground and nowhere else. Excluding self removes that
		// artifact, and widening past Player makes the option do something useful:
		// mobs aim too.
		if (entity != Minecraft.getInstance().player && Mods.bool("hitboxes", "showLookVector")) {
			double eye = entity.getEyeHeight();
			Vec3 look = entity.getViewVector(partialTick);
			patternLine(consumer, pose, 0, eye, 0, look.x * 2, eye + look.y * 2, look.z * 2, r, g, b, a, pattern);
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

	private static void drawBox(VertexConsumer c, PoseStack.Pose pose, AABB box,
								float r, float g, float b, float a, int passes, String pattern) {
		double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
		double ccx = (box.minX + box.maxX) / 2, ccy = (box.minY + box.maxY) / 2, ccz = (box.minZ + box.maxZ) / 2;
		for (int p = 0; p < passes; p++) {
			// Vanilla's RenderType.lines() pins GL line width to
			// max(2.5, windowWidth/1920*2.5) and gives no way to set it per-draw,
			// so "width" is faked by stacking concentric boxes. The old 0.01 step
			// put those passes 1cm apart -- sub-pixel beyond arm's reach, so the
			// slider appeared to do nothing. 0.03 is far enough to read at normal
			// range while still hugging the entity rather than ballooning off it.
			double grow = p * 0.03;
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

	/**
	 * Cuts an edge into the pattern's visible pieces and hands each to `sink`.
	 *
	 * Split out from patternLine so the dash maths lives in exactly one place and
	 * BOTH renderers can use it: the thin line path draws each piece as a line,
	 * the thick path draws each piece as a box. That's what lets Dashed/Dotted
	 * honour Line Width -- the pattern decides WHERE the pieces are, the renderer
	 * decides how thick they are. The two are independent, and previously only
	 * Solid could be thick purely because this loop was welded to seg().
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

	private static void patternLine(VertexConsumer c, PoseStack.Pose pose, double ax, double ay, double az,
									double bx, double by, double bz, float r, float g, float b, float a, String pattern) {
		patternSegments(ax, ay, az, bx, by, bz, pattern,
				(sx, sy, sz, ex, ey, ez) -> seg(c, pose, sx, sy, sz, ex, ey, ez, r, g, b, a));
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
		c.vertex(pose.pose(), (float) ax, (float) ay, (float) az).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
		c.vertex(pose.pose(), (float) bx, (float) by, (float) bz).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
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
