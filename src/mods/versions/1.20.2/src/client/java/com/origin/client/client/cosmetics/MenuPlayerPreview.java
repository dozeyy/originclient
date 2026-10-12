package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no level, so no player entity exists to hand to
 * InventoryScreen.renderEntityInInventory. Instead this draws the parts that
 * entity render would draw, the same way vanilla does:
 *
 *   - the body: a PlayerModel baked from Minecraft's own model layers
 *     (wide or slim to match the skin), textured with the account's skin from
 *     SkinManager -- the same lookup the title screen's head chip uses;
 *   - the cape: PlayerModel.renderCloak with CapeLayer's resting pose, using
 *     the equipped Origin cape (CapeTextures) or else the account's own cape;
 *   - the head item: HeadModels, placed exactly as CosmeticHeadLayer places it.
 *
 * Transforms mirror renderEntityInInventory + LivingEntityRenderer step for
 * step, so this and the in-world preview line up and turn the same way.
 * Fails soft: any error turns it off for the session and the caller shows its
 * "join a world" message instead.
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

	/**
	 * Draws the player centred on (cx, cy) in the CURRENT pose's units, `size`
	 * units per block, turned `yaw` degrees (same sense as the in-world
	 * preview), wearing `look`. Render thread. False if it couldn't draw.
	 */
	public static boolean render(GuiGraphics g, float cx, float cy, float size, float yaw, CosmeticsService.Look look) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		PoseStack pose = g.pose();
		pose.pushPose();
		boolean lit = false;
		try {
			PlayerSkin skin = skin(mc);
			if (skin == null) {
				return false;
			}
			PlayerModel<AbstractClientPlayer> model = model(mc, skin.model() == PlayerSkin.Model.SLIM);
			float ticks = (Util.getMillis() % 3_600_000L) / 50f;
			setUp(mc, model, ticks);

			// InventoryScreen.renderEntityInInventory (x, y, 50; scale; offset; pose)
			pose.translate(cx, cy, 50.0f);
			pose.scale(size, size, -size);
			pose.translate(0f, HEIGHT / 2f, 0f);
			pose.mulPose(new Quaternionf().rotateZ((float) Math.PI).rotateX(-0.12f));
			// LivingEntityRenderer: body turn, flip, the player's 0.9375 scale, feet down
			pose.mulPose(Axis.YP.rotationDegrees(-yaw));
			pose.scale(-1f, -1f, 1f);
			pose.scale(0.9375f, 0.9375f, 0.9375f);
			pose.translate(0f, -1.501f, 0f);

			Lighting.setupForEntityInInventory();
			lit = true;
			MultiBufferSource.BufferSource buffers = g.bufferSource();
			// One render type at a time: asking the source for another may close
			// the previous buffer, so each part finishes before the next starts.
			model.renderToBuffer(pose, buffers.getBuffer(model.renderType(skin.texture())), LIGHT, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);

			String capeId = look == null ? null : look.cape();
			ResourceLocation cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape == null) {
				cape = skin.capeTexture();
			}
			if (cape != null && mc.options.isModelPartEnabled(PlayerModelPart.CAPE)) {
				// CapeLayer's pose for a player standing still, with a gentle sway.
				pose.pushPose();
				pose.translate(0f, 0f, 0.125f);
				pose.mulPose(Axis.XP.rotationDegrees(6f + 2.5f * (float) Math.sin(ticks * 0.06f)));
				pose.mulPose(Axis.YP.rotationDegrees(180f));
				model.renderCloak(pose, buffers.getBuffer(RenderType.entitySolid(cape)), LIGHT, OverlayTexture.NO_OVERLAY);
				pose.popPose();
			}

			String headId = look == null ? null : look.head();
			if (headId != null && Cosmetics.get(headId) != null) {
				// Exactly CosmeticHeadLayer's placement.
				pose.pushPose();
				model.head.translateAndRotate(pose);
				pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
				pose.translate(0f, -24f, 0f);
				HeadModels.render(headId, pose, buffers, LIGHT, ticks / 20f);
				pose.popPose();
			}
			g.flush();
			return true;
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: menu preview failed; showing the message instead", t);
			return false;
		} finally {
			pose.popPose();
			if (lit) {
				Lighting.setupFor3DItems();
			}
		}
	}

	/**
	 * Your skin: the resolved profile (it carries the signed textures), as the
	 * title screen's head chip does. 1.20.2-1.20.4: SkinManager.getInsecureSkin
	 * returns the PlayerSkin directly (the default until the lookup completes,
	 * then the real one; SkinManager caches it, so asking every frame is cheap).
	 */
	private static PlayerSkin skin(Minecraft mc) {
		GameProfile profile = mc.getGameProfile();
		if (profile == null || profile.getId() == null) {
			return null;
		}
		// Nick Hider's "hide own skin" applies here too, as on your in-world model.
		PlayerSkin hidden = NickHider.skinFor(profile);
		return hidden != null ? hidden : mc.getSkinManager().getInsecureSkin(profile);
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
