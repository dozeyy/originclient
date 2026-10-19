package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws a player's model cosmetics - head (hats, halos, crowns), face (masks,
 * bandanas) and back (wings, backpacks) - on every player model: other
 * players in the world, you in third person, and the inventory / Cosmetics
 * screen previews. ONE layer for all three slots; the geometry is data
 * ({@link ModelCosmetic}). Registered on the player renderer through Fabric's
 * LivingEntityFeatureRendererRegistrationCallback (OriginClientMod).
 *
 * Placement (FORMAT.md): head + face items ride the head ModelPart (so they
 * follow yaw, pitch and the crouch), back items ride the body ModelPart (body
 * yaw and the crouch lean). Hidden for: invisible and spectating players, F1,
 * your own player in first person with no screen open, and - for back items
 * only - an equipped elytra.
 */
public final class CosmeticLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static boolean warned = false;

	public CosmeticLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		try {
			if (player.isInvisible() || player.isSpectator()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.options.hideGui) {
				return;   // F1
			}
			if (player == mc.player && mc.screen == null && mc.options.getCameraType().isFirstPerson()) {
				return;   // you can't see your own head items from inside it (a screen = an inventory-style preview)
			}
			CosmeticsService.Look look = CosmeticsService.lookOf(player.getUUID());
			if (look.head() == null && look.face() == null && look.back() == null) {
				return;
			}
			Feel feel = feelOf(player);
			renderItems(getParentModel(), pose, buffers, light, look, ageInTicks / 20f, feel.walk, feel.air,
					player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: cosmetics failed to draw", t);
			}
		}
	}

	/**
	 * Draws the head / face / back items of `look` on `model`, whose parts the
	 * caller has already posed (and whose hat / jacket visibility it has set:
	 * the renderer does from the player's skin options, MenuPlayerPreview from
	 * yours). Shared with MenuPlayerPreview (the title-screen preview has no
	 * entity). Each item is isolated: one failing never stops the others.
	 */
	public static void renderItems(PlayerModel<AbstractClientPlayer> model, PoseStack pose, MultiBufferSource buffers,
			int light, CosmeticsService.Look look, float t, float walk01, float air01, boolean hideBack) {
		// Sit over the outer skin layer when it is shown, instead of clipping into it (Will, 2026-10-19).
		ModelCosmetic.Fit headFit = model.hat.visible ? ModelCosmetic.Fit.HEAD : null;
		draw(model.head, look.head(), look, Cosmetics.Slot.HEAD, headFit, pose, buffers, light, t, walk01, air01);
		draw(model.head, look.face(), look, Cosmetics.Slot.FACE, headFit, pose, buffers, light, t, walk01, air01);
		if (!hideBack) {
			draw(model.body, look.back(), look, Cosmetics.Slot.BACK, model.jacket.visible ? ModelCosmetic.Fit.BODY : null,
					pose, buffers, light, t, walk01, air01);
		}
	}

	/** The player's position tweak for `slot` (model px, FORMAT space) moves the item along its bone. */
	private static void draw(ModelPart bone, String id, CosmeticsService.Look look, Cosmetics.Slot slot,
			ModelCosmetic.Fit fit, PoseStack pose, MultiBufferSource buffers, int light, float t, float walk01, float air01) {
		if (id == null) {
			return;
		}
		ModelCosmetic m = ModelCosmetic.of(id);
		if (m == null) {
			return;
		}
		pose.pushPose();
		try {
			bone.translateAndRotate(pose);
			// FORMAT space -> vanilla model space: 1/16 block per px, y up, face to +z
			// (vanilla is y down, face to -z; a 180 degree turn about X, so no mirroring).
			pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
			pose.translate(look.adjust(slot, CosmeticsService.Adjust.Axis.X), look.adjust(slot),
					look.adjust(slot, CosmeticsService.Adjust.Axis.Z));
			m.render(pose, buffers, light, t, walk01, air01, fit);
		} catch (Throwable ex) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: {} failed to draw", id, ex);
			}
		} finally {
			pose.popPose();
		}
	}

	// ------------------------------------------------- walking / airborne

	/** Sprint speed in blocks per tick: walkSpeed01 = horizontal speed / this. */
	private static final float SPRINT_BLOCKS_PER_TICK = 0.2806f;
	/** airborne01 eases 0 -> 1 over this long (FORMAT.md). */
	private static final float AIR_EASE_SEC = 0.25f;

	static final class Feel {
		float walk, air;
		long lastNs;
		long seenNs;
	}

	private static final Map<Integer, Feel> FEELS = new HashMap<>();
	private static long lastSweepNs = 0;

	/** Smoothed walk / air amounts for `p`, advanced by wall-clock time so they are frame-rate independent. */
	static Feel feelOf(AbstractClientPlayer p) {
		long now = System.nanoTime();
		Feel f = FEELS.computeIfAbsent(p.getId(), k -> new Feel());
		float dt = f.lastNs == 0 ? 0f : Mth.clamp((now - f.lastNs) / 1e9f, 0f, 0.25f);
		f.lastNs = now;
		f.seenNs = now;
		double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
		float speed01 = Mth.clamp((float) Math.sqrt(dx * dx + dz * dz) / SPRINT_BLOCKS_PER_TICK, 0f, 1f);
		// ~0.12 s to follow the per-tick speed, so a stop or start doesn't snap.
		f.walk += (speed01 - f.walk) * Math.min(1f, dt / 0.12f);
		float airStep = dt / AIR_EASE_SEC;
		f.air = p.onGround() ? Math.max(0f, f.air - airStep) : Math.min(1f, f.air + airStep);
		if (now - lastSweepNs > 10_000_000_000L) {
			lastSweepNs = now;
			for (Iterator<Feel> it = FEELS.values().iterator(); it.hasNext();) {
				if (now - it.next().seenNs > 10_000_000_000L) {
					it.remove();
				}
			}
		}
		return f;
	}
}
