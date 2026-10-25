package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws a player's model cosmetics - head (hats, halos, crowns), face (masks,
 * bandanas) and back (wings, backpacks) - on every player model: other
 * players in the world, you in third person, and the inventory / Cosmetics
 * screen previews. ONE layer for all three slots; the geometry is data
 * ({@link ModelCosmetic}).
 *
 * Placement (FORMAT.md): head + face items ride the head part (so they
 * follow yaw, pitch and the crouch), back items ride the body part (body
 * yaw and the crouch lean). Hidden for: invisible and spectating players, F1,
 * and your own player in first person with no screen open.
 *
 * 1.8.9: a LayerRenderer added to both player renderers (RenderManager's skin
 * map: "default" and "slim") with RendererLivingEntity.addLayer, which is
 * public here -- no mixin. {@link #installAll} runs once, from SocialHooks'
 * first client tick (the RenderManager exists by then). There is no elytra on
 * 1.8.9, so back items are never hidden for one.
 */
public final class CosmeticLayer implements LayerRenderer<AbstractClientPlayer> {
	private static boolean warned = false;
	private static boolean installed = false;

	private final RenderPlayer renderer;

	public CosmeticLayer(RenderPlayer renderer) {
		this.renderer = renderer;
	}

	/** Adds the layer to every player renderer, once. Client thread; fail-soft. */
	public static void installAll() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			for (RenderPlayer r : Minecraft.getMinecraft().getRenderManager().getSkinMap().values()) {
				r.addLayer(new CosmeticLayer(r));
			}
		} catch (Throwable t) {
			// no model cosmetics; players still draw
			OriginClient.LOGGER.warn("Origin cosmetics: cosmetic layer not installed", t);
		}
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.begin(com.origin.client.client.BenchProf.S_COSMETIC_LAYER);
		}
		try {
			if (player.isInvisible() || player.isSpectator()) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.gameSettings.hideGUI) {
				return;   // F1
			}
			if (player == mc.thePlayer && mc.currentScreen == null && mc.gameSettings.thirdPersonView == 0) {
				return;   // you can't see your own head items from inside it (a screen = an inventory-style preview)
			}
			CosmeticsService.Look look = CosmeticsService.lookOf(player.getUniqueID());
			if (look.head() == null && look.face() == null && look.back() == null) {
				return;
			}
			Feel feel = feelOf(player);
			renderItems(renderer.getMainModel(), look, ageInTicks / 20f, feel.walk, feel.air, player.isSneaking());
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: cosmetics failed to draw", t);
			}
		} finally {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.end(com.origin.client.client.BenchProf.S_COSMETIC_LAYER);
			}
		}
	}

	@Override
	public boolean shouldCombineTextures() {
		return false;
	}

	/**
	 * Draws the head / face / back items of `look` on `model`, whose parts the
	 * caller has already posed (and whose hat / jacket visibility it has set:
	 * the renderer does from the player's skin options, MenuPlayerPreview from
	 * yours). Shared with MenuPlayerPreview (the title-screen preview has no
	 * entity). Each item is isolated: one failing never stops the others.
	 *
	 * 1.8.9: `sneaking` = ModelBiped.render's whole-model 0.2 block drop, which
	 * the layers don't inherit (LayerCustomHead repeats it the same way).
	 */
	public static void renderItems(ModelPlayer model, CosmeticsService.Look look, float t, float walk01, float air01,
			boolean sneaking) {
		// Sit over the outer skin layer when it is shown, instead of clipping into it (Will, 2026-10-19).
		// 1.8.9: the hat / jacket layers are bipedHeadwear / bipedBodyWear, and their showModel is what
		// RenderPlayer.setModelVisibilities sets from isWearing(HAT / JACKET) (1.21.1's hat / jacket.visible).
		ModelCosmetic.Fit headFit = model.bipedHeadwear.showModel ? ModelCosmetic.Fit.HEAD : null;
		draw(model.bipedHead, look.head(), look, Cosmetics.Slot.HEAD, headFit, t, walk01, air01, sneaking);
		draw(model.bipedHead, look.face(), look, Cosmetics.Slot.FACE, headFit, t, walk01, air01, sneaking);
		draw(model.bipedBody, look.back(), look, Cosmetics.Slot.BACK,
				model.bipedBodyWear.showModel ? ModelCosmetic.Fit.BODY : null, t, walk01, air01, sneaking);
	}

	/** The player's position tweak for `slot` (model px, FORMAT space) moves the item along its bone. */
	private static void draw(ModelRenderer bone, String id, CosmeticsService.Look look, Cosmetics.Slot slot,
			ModelCosmetic.Fit fit, float t, float walk01, float air01, boolean sneaking) {
		if (id == null) {
			return;
		}
		ModelCosmetic m = ModelCosmetic.of(id);
		if (m == null) {
			return;
		}
		GlStateManager.pushMatrix();
		try {
			if (sneaking) {
				GlStateManager.translate(0.0F, 0.2F, 0.0F);
			}
			// 1.8.9: ModelRenderer.postRender is 1.21.1's ModelPart.translateAndRotate
			bone.postRender(0.0625F);
			// FORMAT space -> vanilla model space: 1/16 block per px, y up, face to +z
			// (vanilla is y down, face to -z; a 180 degree turn about X, so no mirroring).
			GlStateManager.scale(1 / 16f, -1 / 16f, -1 / 16f);
			GlStateManager.translate(look.adjust(slot, CosmeticsService.Adjust.Axis.X), look.adjust(slot),
					look.adjust(slot, CosmeticsService.Adjust.Axis.Z));
			m.render(t, walk01, air01, fit);
		} catch (Throwable ex) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: " + id + " failed to draw", ex);
			}
		} finally {
			GlStateManager.popMatrix();
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
		Feel f = FEELS.get(p.getEntityId());
		if (f == null) {
			f = new Feel();
			FEELS.put(p.getEntityId(), f);
		}
		float dt = f.lastNs == 0 ? 0f : Math.max(0f, Math.min(0.25f, (now - f.lastNs) / 1e9f));
		f.lastNs = now;
		f.seenNs = now;
		// 1.8.9: posX - prevPosX is 1.21.1's getX() - xo (last tick's travel)
		double dx = p.posX - p.prevPosX, dz = p.posZ - p.prevPosZ;
		float speed01 = Math.max(0f, Math.min(1f, (float) Math.sqrt(dx * dx + dz * dz) / SPRINT_BLOCKS_PER_TICK));
		// ~0.12 s to follow the per-tick speed, so a stop or start doesn't snap.
		f.walk += (speed01 - f.walk) * Math.min(1f, dt / 0.12f);
		float airStep = dt / AIR_EASE_SEC;
		f.air = p.onGround ? Math.max(0f, f.air - airStep) : Math.min(1f, f.air + airStep);
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
