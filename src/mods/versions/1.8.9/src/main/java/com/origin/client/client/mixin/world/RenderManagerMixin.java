package com.origin.client.client.mixin.world;

import com.origin.client.client.OriginFreelookState;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.FillQuads;
import com.origin.client.client.render.ThickLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.entity.projectile.EntityWitherSkull;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Three world-area hooks on RenderManager (HEAD/RETURN only -- OptiFine patches it):
 *
 * Hitbox mod: replaces vanilla's plain white box (F3+B -- the hub flips
 * RenderManager.setDebugBoundingBox with the mod) with a fully-styled one.
 * Per-type filtering + Max Distance decide whether to draw; Line Color, Line
 * Width, Show Hittable and Show Damaged style every entity's box; Show Look
 * Vector is for everything but yourself. Line Pattern (Solid/Dashed/Dotted)
 * applies to every box. 1.8.9: renderDebugBoundingBox(entity, x, y, z, yaw,
 * partialTicks) gets the entity's camera-relative render position; the GL
 * model-view is the camera, so the box is the entity's AABB shifted there.
 * Width 1 = GL lines; thicker = one ThickLine box per edge through FillQuads.
 *
 * Entity Distance (SETTINGS > Performance): shouldRender RETURN -- a percentage
 * of the render distance; only ever turns a vanilla "yes" into "no".
 *
 * Freelook, labels: cacheActiveRenderInfo works out playerViewY/X -- the angles
 * every name tag (and Origin's world labels) turn to face -- from the view
 * entity's rotation; while freelook holds the camera they are the camera's.
 */
@Mixin(RenderManager.class)
public abstract class RenderManagerMixin {

	// ---- Entity Distance ----

	@Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
	private void originclient$entityCull(Entity entity, ICamera camera, double camX, double camY, double camZ,
										 CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!cir.getReturnValueZ()) {
				return;
			}
			double pct = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
			if (pct >= 100) {
				return;
			}
			double max = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0 * (pct / 100.0);
			double dx = entity.posX - camX;
			double dy = entity.posY - camY;
			double dz = entity.posZ - camZ;
			if (dx * dx + dy * dy + dz * dz > max * max) {
				cir.setReturnValue(false);
			}
		} catch (Throwable ignored) {
			// vanilla's answer
		}
	}

	// ---- Freelook: label facing ----

	@Inject(method = "cacheActiveRenderInfo", at = @At("RETURN"))
	private void originclient$freelookView(World world, FontRenderer font, Entity viewer, Entity pointed,
										   GameSettings options, float partialTicks, CallbackInfo ci) {
		try {
			if (!OriginFreelookState.active || viewer == null || viewer != Minecraft.getMinecraft().thePlayer) {
				return;
			}
			RenderManager self = (RenderManager) (Object) this;
			self.playerViewY = OriginFreelookState.cameraYaw;
			self.playerViewX = OriginFreelookState.cameraPitch;
			if (options != null && options.thirdPersonView == 2) {
				self.playerViewY += 180.0F;   // vanilla's front-view turn
			}
		} catch (Throwable ignored) {
			// labels face the body's rotation
		}
	}

	// ---- Hitboxes ----

	@Inject(method = "renderDebugBoundingBox", at = @At("HEAD"), cancellable = true)
	private void originclient$hitbox(Entity entity, double x, double y, double z, float yaw, float partialTicks,
									 CallbackInfo ci) {
		if (!Mods.on("hitboxes")) {
			return;
		}
		ci.cancel();
		try {
			originclient$drawHitbox((RenderManager) (Object) this, entity, x, y, z, partialTicks);
		} catch (Throwable ignored) {
			// no box this frame
		}
	}

	private static void originclient$drawHitbox(RenderManager rm, Entity entity, double x, double y, double z, float partialTicks) {
		Minecraft mc = Minecraft.getMinecraft();
		double max = Mods.num("hitboxes", "maxDistance");
		if (max > 0) {
			double dx = entity.posX - rm.viewerPosX, dy = entity.posY - rm.viewerPosY, dz = entity.posZ - rm.viewerPosZ;
			if (dx * dx + dy * dy + dz * dz > max * max) {
				return;
			}
		}
		if (!Mods.bool("hitboxes", originclient$categoryKey(entity))) {
			return;
		}
		// Your own box, first person: never (vanilla doesn't draw the camera entity there).
		if (entity == mc.getRenderViewEntity() && mc.gameSettings.thirdPersonView == 0) {
			return;
		}

		// Colour and width apply to EVERY entity, not just players.
		int col = OriginColorPicker.liveColor("hitboxes", "lineColor");
		// Item entities are immune to both tints (Will): you can't attack a dropped
		// item, so colouring one "hittable" or "damaged" would be a lie.
		boolean tintable = !(entity instanceof EntityItem);
		if (tintable && Mods.bool("hitboxes", "showDamaged")
				&& entity instanceof EntityLivingBase && ((EntityLivingBase) entity).hurtTime > 0) {
			col = OriginColorPicker.liveColor("hitboxes", "damagedColor");
		}
		// Hittable tint: whatever your crosshair is on. Wins over the damaged tint.
		MovingObjectPosition hit = mc.objectMouseOver;
		if (tintable && Mods.bool("hitboxes", "showHittable") && hit != null && hit.entityHit == entity) {
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

		AxisAlignedBB bb = entity.getEntityBoundingBox();
		if (bb == null) {
			return;
		}
		AxisAlignedBB box = bb.offset(-entity.posX + x, -entity.posY + y, -entity.posZ + z);

		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		GlStateManager.depthMask(false);
		GlStateManager.disableTexture2D();
		GlStateManager.disableLighting();
		GlStateManager.disableCull();
		try {
			// ONE thick line per edge -- not a stack of thin ones. Every pattern gets
			// width: patternSegments decides where the pieces are, ThickLine how
			// thick each piece is. Width 1 stays on the cheaper GL line path.
			if (passes > 1) {
				WorldRenderer q = FillQuads.begin();
				double t = (passes - 1) * 0.012;
				double ccx = (box.minX + box.maxX) / 2, ccy = (box.minY + box.maxY) / 2, ccz = (box.minZ + box.maxZ) / 2;
				for (double[] e : originclient$edges(box)) {
					for (double[] s : originclient$patternSegments(e, pattern)) {
						ThickLine.edge(q, s[0], s[1], s[2], s[3], s[4], s[5], ccx, ccy, ccz, t, r, g, b, a);
					}
				}
				FillQuads.draw();
			} else {
				originclient$beginLines();
				WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
				for (double[] e : originclient$edges(box)) {
					for (double[] s : originclient$patternSegments(e, pattern)) {
						originclient$seg(wr, s, r, g, b, a);
					}
				}
				Tessellator.getInstance().draw();
			}

			// Every entity EXCEPT yourself (you can't see your own ray anyway, and
			// OptiFine shaders would draw it into the shadow map); mobs aim too.
			if (entity != mc.thePlayer && Mods.bool("hitboxes", "showLookVector")) {
				double eye = entity.getEyeHeight();
				Vec3 look = entity.getLook(partialTicks);
				originclient$beginLines();
				WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
				double[] ray = {x, y + eye, z, x + look.xCoord * 2, y + eye + look.yCoord * 2, z + look.zCoord * 2};
				for (double[] s : originclient$patternSegments(ray, pattern)) {
					originclient$seg(wr, s, r, g, b, a);
				}
				Tessellator.getInstance().draw();
			}
		} finally {
			GL11.glLineWidth(1.0F);
			GlStateManager.enableTexture2D();
			GlStateManager.enableLighting();
			if (cull) {
				GlStateManager.enableCull();
			}
			GlStateManager.disableBlend();
			GlStateManager.depthMask(true);
			com.origin.client.client.gui.Gfx.resetColor();
		}
	}

	private static void originclient$beginLines() {
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GL11.glLineWidth(2.0F);
		Tessellator.getInstance().getWorldRenderer().begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
	}

	private static void originclient$seg(WorldRenderer wr, double[] s, float r, float g, float b, float a) {
		wr.pos(s[0], s[1], s[2]).color(r, g, b, a).endVertex();
		wr.pos(s[3], s[4], s[5]).color(r, g, b, a).endVertex();
	}

	/** An AABB's 12 edges as {ax, ay, az, bx, by, bz}. */
	private static double[][] originclient$edges(AxisAlignedBB box) {
		double[] xs = {box.minX, box.maxX}, ys = {box.minY, box.maxY}, zs = {box.minZ, box.maxZ};
		double[][] out = new double[12][];
		int n = 0;
		for (double yv : ys) {
			for (double zv : zs) {
				out[n++] = new double[]{box.minX, yv, zv, box.maxX, yv, zv};
			}
		}
		for (double xv : xs) {
			for (double zv : zs) {
				out[n++] = new double[]{xv, box.minY, zv, xv, box.maxY, zv};
			}
		}
		for (double xv : xs) {
			for (double yv : ys) {
				out[n++] = new double[]{xv, yv, box.minZ, xv, yv, box.maxZ};
			}
		}
		return out;
	}

	/**
	 * Cuts an edge into the pattern's visible pieces. The pattern decides WHERE
	 * the pieces are, the renderer how thick they are -- so Dashed/Dotted honour
	 * Line Width too.
	 */
	private static java.util.List<double[]> originclient$patternSegments(double[] e, String pattern) {
		java.util.List<double[]> out = new java.util.ArrayList<>();
		if (pattern == null || pattern.equals("Solid")) {
			out.add(e);
			return out;
		}
		double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 0) {
			return out;
		}
		double dash = pattern.equals("Dotted") ? 0.08 : 0.22;
		double period = dash * 2;
		for (double s = 0; s < len; s += period) {
			double t0 = s / len, t1 = Math.min(len, s + dash) / len;
			out.add(new double[]{e[0] + dx * t0, e[1] + dy * t0, e[2] + dz * t0,
					e[0] + dx * t1, e[1] + dy * t1, e[2] + dz * t1});
		}
		return out;
	}

	// 1.8.9 classes for the newer versions' categories (no Monster/Projectile
	// bases here: IMob marks hostiles, IProjectile thrown things).
	private static String originclient$categoryKey(Entity e) {
		if (e instanceof EntityPlayer) return "players";
		if (e instanceof EntityItem) return "items";
		if (e instanceof EntityItemFrame) return "itemFrames";
		if (e instanceof EntityWitherSkull) return "witherSkulls";
		if (e instanceof EntityFireball) return "fireballs";
		if (e instanceof EntityFireworkRocket) return "fireworks";
		if (e instanceof EntitySnowball) return "snowballs";
		if (e instanceof EntityArrow) return "arrows";
		if (e instanceof EntityXPOrb) return "expOrbs";
		if (e instanceof IProjectile) return "projectiles";
		if (e instanceof IMob) return "monsters";
		if (e instanceof EntityAnimal) return "passive";
		return "other";
	}
}
