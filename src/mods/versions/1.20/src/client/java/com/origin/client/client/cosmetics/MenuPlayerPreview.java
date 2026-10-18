package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
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
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
 *   - the head / face / back items: CosmeticLayer.renderItems, the same call
 *     (and so the same placement and animation) the in-world layer makes.
 *
 * Also draws the Cosmetics screen's tile thumbnails ({@link #renderThumb}):
 * the same model with just one item on it, zoomed by the caller.
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
	private static UUID skinOwner;
	/** Your profile with its signed `textures` property, fetched once off-thread. */
	private static CompletableFuture<GameProfile> filled;
	private static boolean broken = false;

	/**
	 * Draws the player centred on (cx, cy) in the CURRENT pose's units, `size`
	 * units per block, turned `yaw` degrees (same sense as the in-world
	 * preview), wearing `look`. Render thread. False if it couldn't draw.
	 */
	public static boolean render(GuiGraphics g, float cx, float cy, float size, float yaw, CosmeticsService.Look look) {
		return render(g, cx, cy, size, yaw, look, true);
	}

	/**
	 * A thumbnail: like {@link #render} but with no cape from the account (only
	 * what `look` itself carries) so a tile shows nothing but its own item. The
	 * caller zooms by choosing `cy` and `size` (and clips to its tile).
	 */
	public static boolean renderThumb(GuiGraphics g, float cx, float cy, float size, float yaw,
			CosmeticsService.Look look) {
		return render(g, cx, cy, size, yaw, look, false);
	}

	private static boolean render(GuiGraphics g, float cx, float cy, float size, float yaw,
			CosmeticsService.Look look, boolean accountCape) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		PoseStack pose = g.pose();
		pose.pushPose();
		boolean lit = false;
		try {
			Skin skin = skin(mc);
			if (skin == null) {
				return false;
			}
			PlayerModel<AbstractClientPlayer> model = model(mc, skin.slim());
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
			if (cape == null && accountCape) {
				cape = skin.capeTexture();
			}
			boolean capeHidden = look != null && look.hidesCape();   // wings / a backpack replace it
			if (cape != null && !capeHidden && mc.options.isModelPartEnabled(PlayerModelPart.CAPE)) {
				// CapeLayer's pose for a player standing still, with a gentle sway.
				pose.pushPose();
				pose.translate(0f, 0f, 0.125f + (look == null ? 0f : look.capeOffset()) / 16f);
				pose.mulPose(Axis.XP.rotationDegrees(6f + 2.5f * (float) Math.sin(ticks * 0.06f)));
				pose.mulPose(Axis.YP.rotationDegrees(180f));
				model.renderCloak(pose, buffers.getBuffer(RenderType.entitySolid(cape)), LIGHT, OverlayTexture.NO_OVERLAY);
				pose.popPose();
			}

			if (look != null) {
				CosmeticLayer.renderItems(model, pose, buffers, LIGHT, look, ticks / 20f, 0f, 0f, false);
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

	/** 1.20 / 1.20.1 has no PlayerSkin record (1.20.2+): its three parts, together. */
	private record Skin(ResourceLocation texture, boolean slim, ResourceLocation capeTexture) {
	}

	/**
	 * Your skin. 1.20 / 1.20.1: the session's profile is only UUID + name, and
	 * SkinManager reads the skin from the profile's signed `textures` property, so
	 * that property is fetched once from the session service OFF the render thread
	 * (a network call); until it arrives -- or if it never does (offline) -- this
	 * shows the default skin for your UUID, exactly as the title screen's head chip.
	 */
	private static Skin skin(Minecraft mc) {
		var user = mc.getUser();
		if (user == null || user.getProfileId() == null) {
			return null;
		}
		GameProfile profile = new GameProfile(user.getProfileId(), user.getName());
		// Nick Hider's "hide own skin" applies here too, as on your in-world model.
		ResourceLocation hidden = NickHider.skinFor(profile);
		if (hidden != null) {
			ResourceLocation[] cape = NickHider.capeFor(profile);
			return new Skin(hidden, "slim".equals(NickHider.modelFor(profile)), cape == null ? null : cape[0]);
		}
		if (filled == null || !profile.getId().equals(skinOwner)) {
			skinOwner = profile.getId();
			filled = CompletableFuture.supplyAsync(
					() -> mc.getMinecraftSessionService().fillProfileProperties(profile, false), Util.backgroundExecutor());
		}
		GameProfile full = filled.getNow(null);
		SkinManager skins = mc.getSkinManager();
		Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> info =
				full == null || filled.isCompletedExceptionally() ? Map.of() : skins.getInsecureSkinInformation(full);
		MinecraftProfileTexture skinTex = info.get(MinecraftProfileTexture.Type.SKIN);
		MinecraftProfileTexture capeTex = info.get(MinecraftProfileTexture.Type.CAPE);
		if (skinTex == null) {
			return new Skin(DefaultPlayerSkin.getDefaultSkin(profile.getId()),
					"slim".equals(DefaultPlayerSkin.getSkinModelName(profile.getId())),
					capeTex == null ? null : skins.registerTexture(capeTex, MinecraftProfileTexture.Type.CAPE));
		}
		return new Skin(skins.registerTexture(skinTex, MinecraftProfileTexture.Type.SKIN),
				"slim".equals(skinTex.getMetadata("model")),
				capeTex == null ? null : skins.registerTexture(capeTex, MinecraftProfileTexture.Type.CAPE));
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
