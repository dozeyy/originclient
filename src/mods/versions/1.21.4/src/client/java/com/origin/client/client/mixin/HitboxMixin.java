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

	@Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
	private static void originclient$filterHitbox(PoseStack poseStack, VertexConsumer consumer, Entity entity,
												  float partialTick, float red, float green, float blue, CallbackInfo ci) {
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

		// draw our own styled box, then cancel vanilla's
		boolean isPlayer = entity instanceof Player;
		float r = red, g = green, b = blue, a = 1f;
		if (isPlayer) {
			int col = OriginColorPicker.liveColor("hitboxes", "lineColor");
			if (Mods.bool("hitboxes", "showDamaged") && ((net.minecraft.world.entity.LivingEntity) entity).hurtTime > 0) {
				col = 0xFFE05555;
			}
			// Show Hittable Color: green on the entity your crosshair is on (the
			// one you'd hit if you attacked right now). Wins over the damaged tint.
			if (Mods.bool("hitboxes", "showHittable")
					&& Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr
					&& ehr.getEntity() == entity) {
				col = 0xFF7FA98F;
			}
			r = ((col >> 16) & 0xFF) / 255f;
			g = ((col >> 8) & 0xFF) / 255f;
			b = (col & 0xFF) / 255f;
			a = ((col >>> 24) & 0xFF) / 255f;
			if (a <= 0f) {
				a = 1f;
			}
		}
		int passes = isPlayer ? (int) Math.max(1, Math.round(Mods.num("hitboxes", "lineWidth"))) : 1;
		String pattern = Mods.mode("hitboxes", "linePattern");

		AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
		PoseStack.Pose pose = poseStack.last();
		drawBox(consumer, pose, box, r, g, b, a, passes, pattern);

		if (isPlayer && Mods.bool("hitboxes", "showLookVector")) {
			double eye = entity.getEyeHeight();
			Vec3 look = entity.getViewVector(partialTick);
			patternLine(consumer, pose, 0, eye, 0, look.x * 2, eye + look.y * 2, look.z * 2, r, g, b, a, pattern);
		}
		ci.cancel();
	}

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
