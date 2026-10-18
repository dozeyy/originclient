package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.UUID;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no level, so no player entity exists to hand to
 * InventoryScreen.renderEntityInInventory. Instead this draws the parts that
 * entity render would draw, the same way vanilla does:
 *
 *   - the body: a PlayerModel baked from Minecraft's own model layers
 *     (wide or slim to match the skin), textured with the account's skin;
 *   - the cape: PlayerModel.renderCloak with CapeLayer's resting pose, using
 *     the equipped Origin cape (CapeTextures) or else the account's own cape;
 *   - the head / face / back items: CosmeticLayer.renderItems, the same call
 *     (and so the same placement and animation) the in-world layer makes.
 *
 * Also draws the Cosmetics screen's tile thumbnails ({@link #renderThumb}):
 * the same model with just one item on it, zoomed by the caller.
 *
 * 1.17.1 (no PlayerSkin before 1.20.2): the skin, its arm model and the cape
 * come from SkinManager.registerSkins -- the title screen's account chip uses the
 * same call -- with the account's default skin until the real one arrives. The
 * transforms replay this version's renderEntityInInventory + LivingEntityRenderer
 * step for step (RenderSystem's model-view stack carries the screen position,
 * feet at (x, feetY), GUI px), so this and the in-world preview line up and turn
 * the same way. Fails soft: any error turns it off for the session and the
 * caller shows its "join a world" message instead.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** Full-bright packed light, as renderEntityInInventory uses. */
	private static final int LIGHT = 15728880;
	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;

	private static PlayerModel<AbstractClientPlayer> wide, slim;
	private static boolean broken = false;

	// the account's textures, filled in asynchronously by SkinManager.registerSkins
	private static UUID skinOwner;
	private static volatile ResourceLocation skinTex, capeTex;
	private static volatile boolean skinSlim;

	/**
	 * Draws the player centred on (cx, cy) in GUI px, `size` GUI px per block,
	 * turned `yaw` degrees (same sense as the in-world preview), wearing `look`.
	 * Render thread. False if it couldn't draw.
	 */
	public static boolean render(float cx, float cy, float size, float yaw, CosmeticsService.Look look) {
		return render(cx, cy, size, yaw, look, true);
	}

	/**
	 * A thumbnail: like {@link #render} but with no cape from the account (only
	 * what `look` itself carries) so a tile shows nothing but its own item. The
	 * caller zooms by choosing `cy` and `size` (and clips to its tile).
	 */
	public static boolean renderThumb(float cx, float cy, float size, float yaw, CosmeticsService.Look look) {
		return render(cx, cy, size, yaw, look, false);
	}

	private static boolean render(float cx, float cy, float size, float yaw, CosmeticsService.Look look,
			boolean accountCape) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		PoseStack modelView = RenderSystem.getModelViewStack();
		modelView.pushPose();
		boolean lit = false;
		try {
			GameProfile profile = mc.getUser().getGameProfile();
			if (profile == null || profile.getId() == null) {
				return false;
			}
			requestSkin(mc, profile);
			ResourceLocation skin;
			boolean isSlim;
			// Nick Hider's "hide own skin" applies here too, as on your in-world model.
			NickHider.Skin hidden = NickHider.skinFor(profile);
			if (hidden != null) {
				skin = hidden.texture();
				isSlim = "slim".equals(hidden.model());
			} else if (skinTex != null) {
				skin = skinTex;
				isSlim = skinSlim;
			} else {
				skin = DefaultPlayerSkin.getDefaultSkin(profile.getId());
				isSlim = "slim".equals(DefaultPlayerSkin.getSkinModelName(profile.getId()));
			}
			PlayerModel<AbstractClientPlayer> model = model(mc, isSlim);
			float ticks = (Util.getMillis() % 3_600_000L) / 50f;
			setUp(mc, model, ticks);

			// InventoryScreen.renderEntityInInventory (feet at x, y; z 1050; size; camera tilt)
			modelView.translate(cx, cy + size * HEIGHT / 2f, 1050.0);
			modelView.scale(1.0F, 1.0F, -1.0F);
			RenderSystem.applyModelViewMatrix();
			PoseStack pose = new PoseStack();
			pose.translate(0.0, 0.0, 1000.0);
			pose.scale(size, size, size);
			Quaternion turn = Vector3f.ZP.rotation((float) Math.PI);
			turn.mul(Vector3f.XP.rotation(-0.12f));
			pose.mulPose(turn);
			// LivingEntityRenderer: body turn, flip, the player's 0.9375 scale, feet down
			pose.mulPose(Vector3f.YP.rotationDegrees(-yaw));
			pose.scale(-1f, -1f, 1f);
			pose.scale(0.9375f, 0.9375f, 0.9375f);
			pose.translate(0.0, -1.501, 0.0);

			Lighting.setupForEntityInInventory();
			lit = true;
			MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
			// One render type at a time: asking the source for another may close
			// the previous buffer, so each part finishes before the next starts.
			model.renderToBuffer(pose, buffers.getBuffer(model.renderType(skin)), LIGHT, OverlayTexture.NO_OVERLAY,
					1f, 1f, 1f, 1f);

			String capeId = look == null ? null : look.cape();
			ResourceLocation cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape == null && hidden == null && accountCape) {
				cape = capeTex;
			}
			boolean capeHidden = look != null && look.hidesCape();   // wings / a backpack replace it
			if (cape != null && !capeHidden && mc.options.isModelPartEnabled(PlayerModelPart.CAPE)) {
				// CapeLayer's pose for a player standing still, with a gentle sway.
				pose.pushPose();
				pose.translate(0.0, 0.0, 0.125 + (look == null ? 0f : look.capeOffset()) / 16f);
				pose.mulPose(Vector3f.XP.rotationDegrees(6f + 2.5f * (float) Math.sin(ticks * 0.06f)));
				pose.mulPose(Vector3f.YP.rotationDegrees(180f));
				model.renderCloak(pose, buffers.getBuffer(RenderType.entitySolid(cape)), LIGHT, OverlayTexture.NO_OVERLAY);
				pose.popPose();
			}

			if (look != null) {
				CosmeticLayer.renderItems(model, pose, buffers, LIGHT, look, ticks / 20f, 0f, 0f, false);
			}
			buffers.endBatch();
			return true;
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: menu preview failed; showing the message instead", t);
			return false;
		} finally {
			modelView.popPose();
			RenderSystem.applyModelViewMatrix();
			if (lit) {
				Lighting.setupFor3DItems();
			}
		}
	}

	/**
	 * Asks SkinManager for the account's skin + cape once per account (the
	 * resolved session properties carry the signed textures for your own
	 * profile). The callback lands on the render thread once each texture is
	 * registered; until then the default skin shows.
	 */
	private static void requestSkin(Minecraft mc, GameProfile profile) {
		if (profile.getId().equals(skinOwner)) {
			return;
		}
		skinOwner = profile.getId();
		skinTex = null;
		capeTex = null;
		skinSlim = false;
		try {
			mc.getSkinManager().registerSkins(profile, (type, location, texture) -> {
				if (type == MinecraftProfileTexture.Type.SKIN) {
					skinSlim = "slim".equals(texture.getMetadata("model"));
					skinTex = location;
				} else if (type == MinecraftProfileTexture.Type.CAPE) {
					capeTex = location;
				}
			}, true);
		} catch (Throwable ignored) {
			// stay on the default skin
		}
	}

	private static PlayerModel<AbstractClientPlayer> model(Minecraft mc, boolean isSlim) {
		if (isSlim) {
			if (slim == null) {
				slim = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
			}
			return slim;
		}
		if (wide == null) {
			wide = new PlayerModel<>(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
		}
		return wide;
	}

	/**
	 * What PlayerRenderer.setModelProperties + setupAnim would set for a
	 * grown-up player standing still: your skin-layer choices from Skin
	 * Customization, and vanilla's idle arm sway.
	 */
	private static void setUp(Minecraft mc, PlayerModel<AbstractClientPlayer> m, float ticks) {
		m.young = false;
		m.riding = false;
		m.crouching = false;
		m.attackTime = 0f;
		m.setAllVisible(true);
		m.hat.visible = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
		m.jacket.visible = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
		m.leftPants.visible = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
		m.rightPants.visible = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
		m.leftSleeve.visible = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
		m.rightSleeve.visible = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
		// AnimationUtils.bobArms
		m.rightArm.zRot = (float) Math.cos(ticks * 0.09f) * 0.05f + 0.05f;
		m.leftArm.zRot = -m.rightArm.zRot;
		m.rightArm.xRot = (float) Math.sin(ticks * 0.067f) * 0.05f;
		m.leftArm.xRot = -m.rightArm.xRot;
		m.rightSleeve.copyFrom(m.rightArm);
		m.leftSleeve.copyFrom(m.leftArm);
	}
}
