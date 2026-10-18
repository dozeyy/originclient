package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era; the shape of Fabric API's
 * own 1.21.11 FeatureRendererTest): the player renderer is AvatarRenderer and
 * layers get the extracted AvatarRenderState in
 * submit(PoseStack, SubmitNodeCollector, int, S, float, float). The state has
 * no UUID, so the wearer is found by the state's entity id in the client
 * level (the LevelHeadMixin pattern). The Cosmetics screen's no-world preview
 * goes through this same layer (MenuPlayerPreview submits its state to the
 * game's player renderer): its state carries {@link MenuPlayerPreview#STATE_ID}
 * and says what it wears through {@link MenuPlayerPreview#lookFor}. The model
 * root is applied first, as vanilla's own head layers do since 1.21.2.
 * PlayerModel moved to model.player and is no longer generic.
 */
public final class CosmeticLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static boolean warned = false;

	public CosmeticLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		try {
			if (state.isInvisible) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.options.hideGui) {
				return;   // F1
			}
			CosmeticsService.Look look = MenuPlayerPreview.lookFor(state);
			float walk = 0f, air = 0f;
			boolean elytra = false;
			if (look == null) {
				Entity e = mc.level == null ? null : mc.level.getEntity(state.id);
				if (!(e instanceof AbstractClientPlayer player) || player.isSpectator()) {
					return;
				}
				if (player == mc.player && mc.screen == null && mc.options.getCameraType().isFirstPerson()) {
					return;   // you can't see your own head items from inside it (a screen = an inventory-style preview)
				}
				look = CosmeticsService.lookOf(player.getUUID());
				if (look.head() == null && look.face() == null && look.back() == null) {
					return;
				}
				Feel feel = feelOf(player);
				walk = feel.walk;
				air = feel.air;
				elytra = player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
			}
			renderItems(getParentModel(), pose, collector, light, look, state.ageInTicks / 20f, walk, air, elytra);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: cosmetics failed to draw", t);
			}
		}
	}

	/**
	 * Submits the head / face / back items of `look` on `model`, whose parts
	 * the renderer has already posed for this state. Each item is isolated:
	 * one failing never stops the others.
	 */
	static void renderItems(PlayerModel model, PoseStack pose, SubmitNodeCollector collector,
			int light, CosmeticsService.Look look, float t, float walk01, float air01, boolean hideBack) {
		draw(model, model.head, look.head(), look.adjust(Cosmetics.Slot.HEAD), pose, collector, light, t, walk01, air01);
		draw(model, model.head, look.face(), look.adjust(Cosmetics.Slot.FACE), pose, collector, light, t, walk01, air01);
		if (!hideBack) {
			draw(model, model.body, look.back(), look.adjust(Cosmetics.Slot.BACK), pose, collector, light, t, walk01, air01);
		}
	}

	/** `lift` = the player's height tweak for this slot, model px (+ = up, along the bone). */
	private static void draw(PlayerModel model, ModelPart bone, String id, float lift, PoseStack pose, SubmitNodeCollector collector,
			int light, float t, float walk01, float air01) {
		if (id == null) {
			return;
		}
		ModelCosmetic m = ModelCosmetic.of(id);
		if (m == null) {
			return;
		}
		pose.pushPose();
		try {
			model.root().translateAndRotate(pose);
			bone.translateAndRotate(pose);
			// FORMAT space -> vanilla model space: 1/16 block per px, y up, face to +z
			// (vanilla is y down, face to -z; a 180 degree turn about X, so no mirroring).
			pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
			pose.translate(0f, lift, 0f);
			m.render(pose, collector, light, t, walk01, air01);
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
