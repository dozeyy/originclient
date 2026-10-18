package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import com.origin.client.OriginClient;
import com.origin.client.client.gui.Gfx;
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
 * there is no level, so no player entity exists to hand to the inventory
 * entity preview. Instead this draws the parts that entity render would draw,
 * the same way vanilla does:
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
 * Transforms mirror the inventory entity preview + LivingEntityRenderer step
 * for step, so this and the in-world preview line up and turn the same way.
 * Fails soft: any error turns it off for the session and the caller shows its
 * "join a world" message instead.
 *
 * Pre-1.20.2 port: there is no PlayerSkin. The skin, its arm model ("slim" in
 * the texture metadata) and the account's own cape come from this era's
 * SkinManager.registerSkins (default skin at once, the real textures swapped
 * in async when they arrive -- the title screen's account chip works the same
 * way); the model draws with renderToBuffer's four colour floats.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** Full-bright packed light, as the inventory entity preview uses. */
	private static final int LIGHT = 15728880;
	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;

	private static PlayerModel<AbstractClientPlayer> wide, slim;
	private static UUID skinOwner;
	// filled by registerSkins' callback (render thread); null = not arrived (yet)
	private static volatile ResourceLocation skinTex, capeTex;
	private static volatile boolean skinSlim;
	private static boolean broken = false;

	/**
	 * Draws the player centred on (cx, cy) in the CURRENT pose's units, `size`
	 * units per block, turned `yaw` degrees (same sense as the in-world
	 * preview), wearing `look`. Render thread. False if it couldn't draw.
	 */
	public static boolean render(Gfx g, float cx, float cy, float size, float yaw, CosmeticsService.Look look) {
		return render(g, cx, cy, size, yaw, look, true);
	}

	/**
	 * A thumbnail: like {@link #render} but with no cape from the account (only
	 * what `look` itself carries) so a tile shows nothing but its own item. The
	 * caller zooms by choosing `cy` and `size` (and clips to its tile).
	 */
	public static boolean renderThumb(Gfx g, float cx, float cy, float size, float yaw,
			CosmeticsService.Look look) {
		return render(g, cx, cy, size, yaw, look, false);
	}

	private static boolean render(Gfx g, float cx, float cy, float size, float yaw,
			CosmeticsService.Look look, boolean accountCape) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		PoseStack pose = g.pose();
		pose.pushPose();
		boolean lit = false;
		try {
			GameProfile profile = mc.getUser() == null ? null : mc.getUser().getGameProfile();
			if (profile == null || profile.getId() == null) {
				return false;
			}
			requestSkins(mc, profile);
			// Nick Hider's "hide own skin" applies here too, as on your in-world model.
			ResourceLocation hidden = NickHider.skinFor(profile);
			ResourceLocation skin;
			boolean isSlim;
			if (hidden != null) {
				skin = hidden;
				isSlim = "slim".equals(NickHider.modelFor(profile));
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

			// The inventory entity preview (x, y, 50; scale; offset; pose)
			pose.translate(cx, cy, 50.0f);
			pose.scale(size, size, -size);
			pose.translate(0f, HEIGHT / 2f, 0f);
			Quaternion tilt = Vector3f.ZP.rotation((float) Math.PI);   // pre-JOML: Z(pi) then X(-0.12)
			tilt.mul(Vector3f.XP.rotation(-0.12f));
			pose.mulPose(tilt);
			// LivingEntityRenderer: body turn, flip, the player's 0.9375 scale, feet down
			pose.mulPose(Vector3f.YP.rotationDegrees(-yaw));
			pose.scale(-1f, -1f, 1f);
			pose.scale(0.9375f, 0.9375f, 0.9375f);
			pose.translate(0f, -1.501f, 0f);

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
				pose.translate(0f, 0f, 0.125f + (look == null ? 0f : look.capeOffset()) / 16f);
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
			pose.popPose();
			if (lit) {
				Lighting.setupFor3DItems();
			}
		}
	}

	/**
	 * Asks SkinManager for your skin + cape once per account (the profile carries
	 * no textures itself; registerSkins fills them from the session, off-thread,
	 * and calls back on the render thread). Until then the default skin draws.
	 */
	private static void requestSkins(Minecraft mc, GameProfile profile) {
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
			// stay on the default skin, no cape
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
