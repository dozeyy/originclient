package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.DefaultPlayerSkin;   // 1.8.9 NAME UNVERIFIED (getDefaultSkin / getSkinType(UUID))
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Set;
import java.util.UUID;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no world, so no player entity exists to hand to
 * GuiInventory.drawEntityOnScreen. Instead this draws the parts that entity
 * render would draw, the same way vanilla does:
 *
 *   - the body: a ModelPlayer (wide or slim to match the skin), textured with
 *     the account's skin;
 *   - the cape: ModelPlayer.renderCape with LayerCape's resting pose, using the
 *     equipped Origin cape (CapeTextures) or else the account's own cape;
 *   - the head / face / back items: CosmeticLayer.renderItems, the same call
 *     (and so the same placement and animation) the in-world layer makes.
 *
 * Also draws the Cosmetics screen's tile thumbnails ({@link #renderThumb}):
 * the same model with just one item on it, zoomed by the caller.
 *
 * 1.8.9: no fake world or entity is needed (a player entity can't be built
 * without a WorldClient, and ModelPlayer.render reads its entity): the model's
 * parts are posed here and each ModelRenderer is drawn directly, which is all
 * ModelPlayer.render does for a grown-up player. The skin, its arm model and
 * the cape come from SkinManager.loadProfileTextures (for your own profile it
 * fetches your signed textures itself), with the account's default skin until
 * the real one arrives. The transforms replay this version's
 * GuiInventory.drawEntityOnScreen + RendererLivingEntity/RenderPlayer steps for
 * step, so this and the in-world preview line up and turn the same way. Units
 * are whatever the current GL matrix is in (the Cosmetics screen draws under its
 * physical-px deck pose). Fails soft: any error turns it off for the session and
 * the caller shows its "join a world" message instead.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;
	/** drawEntityOnScreen's camera tilt for the mouse at y = -14.31: atan(-14.31 / 40) * 20 = -6.9 deg (1.21.1's rotateX(-0.12)). */
	static final float TILT_MOUSE_Y = -14.31f;

	private static ModelPlayer wide, slim;
	private static boolean broken = false;

	// the account's textures, filled in asynchronously by SkinManager.loadProfileTextures
	private static UUID skinOwner;
	private static volatile ResourceLocation skinTex, capeTex;
	private static volatile boolean skinSlim;

	/**
	 * Draws the player centred on (cx, cy), `size` units per block, turned `yaw`
	 * degrees (same sense as the in-world preview), wearing `look`. Render
	 * thread. False if it couldn't draw.
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
		Minecraft mc = Minecraft.getMinecraft();
		// the caller's 2D draws may leave depth testing off; the model needs it for
		// its own overlapping parts (the GUI's depth was cleared before the screen drew)
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		GlStateManager.enableDepth();
		GlStateManager.depthMask(true);
		GlStateManager.pushMatrix();
		boolean lit = false;
		try {
			GameProfile profile = mc.getSession() != null ? mc.getSession().getProfile() : null;
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
				isSlim = "slim".equals(DefaultPlayerSkin.getSkinType(profile.getId()));
			}
			ModelPlayer model = model(isSlim);
			float ticks = (Minecraft.getSystemTime() % 3_600_000L) / 50f;
			setUp(mc, model, ticks);

			// GuiInventory.drawEntityOnScreen (feet at x, y; z in front of the panel;
			// size; camera tilt; its lighting set-up)
			GlStateManager.enableColorMaterial();
			GlStateManager.translate(cx, cy + size * HEIGHT / 2f, 50.0F + size);
			GlStateManager.scale(-size, size, size);
			GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
			GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
			RenderHelper.enableStandardItemLighting();
			lit = true;
			GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
			GlStateManager.rotate(-((float) Math.atan(TILT_MOUSE_Y / 40.0F)) * 20.0F, 1.0F, 0.0F, 0.0F);
			// RendererLivingEntity: body turn, flip, RenderPlayer's 0.9375 scale, feet down
			GlStateManager.rotate(180.0F - yaw, 0.0F, 1.0F, 0.0F);
			GlStateManager.enableRescaleNormal();
			GlStateManager.scale(-1.0F, -1.0F, 1.0F);
			GlStateManager.scale(0.9375F, 0.9375F, 0.9375F);
			GlStateManager.translate(0.0F, -1.5078125F, 0.0F);
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
			GlStateManager.color(1f, 1f, 1f, 1f);
			GlStateManager.enableAlpha();

			mc.getTextureManager().bindTexture(skin);
			float sc = 0.0625F;
			model.bipedHead.render(sc);
			model.bipedBody.render(sc);
			model.bipedRightArm.render(sc);
			model.bipedLeftArm.render(sc);
			model.bipedRightLeg.render(sc);
			model.bipedLeftLeg.render(sc);
			model.bipedHeadwear.render(sc);
			model.bipedLeftLegwear.render(sc);
			model.bipedRightLegwear.render(sc);
			model.bipedLeftArmwear.render(sc);
			model.bipedRightArmwear.render(sc);
			model.bipedBodyWear.render(sc);

			String capeId = look == null ? null : look.cape();
			ResourceLocation cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape == null && hidden == null && accountCape) {
				cape = capeTex;
			}
			boolean capeHidden = look != null && look.hidesCape();   // wings / a backpack replace it
			if (cape != null && !capeHidden && mc.gameSettings.getModelParts().contains(EnumPlayerModelParts.CAPE)) {
				// LayerCape's pose for a player standing still, with a gentle sway.
				mc.getTextureManager().bindTexture(cape);
				GlStateManager.pushMatrix();
				GlStateManager.translate(0.0F, 0.0F, 0.125F + (look == null ? 0f : look.capeOffset()) / 16f);
				if (capeId != null) {
					GlStateManager.translate(0f, -look.adjust(Cosmetics.Slot.CAPE) / 16f, 0f);   // the player's height tweak (model y is down)
				}
				GlStateManager.rotate(6f + 2.5f * (float) Math.sin(ticks * 0.06f), 1.0F, 0.0F, 0.0F);
				GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
				model.renderCape(sc);
				GlStateManager.popMatrix();
			}

			if (look != null) {
				// the skin texture is rebound by each item's own draw (ModelCosmetic binds its atlas)
				CosmeticLayer.renderItems(model, look, ticks / 20f, 0f, 0f, false);
			}
			return true;
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: menu preview failed; showing the message instead", t);
			return false;
		} finally {
			GlStateManager.popMatrix();
			// drawEntityOnScreen's own clean-up
			if (lit) {
				RenderHelper.disableStandardItemLighting();
			}
			GlStateManager.disableRescaleNormal();
			GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
			GlStateManager.disableTexture2D();
			GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
			GlStateManager.color(1f, 1f, 1f, 1f);
			if (!depth) {
				GlStateManager.disableDepth();
			}
		}
	}

	/**
	 * Asks SkinManager for the account's skin + cape once per account. For your
	 * own profile, loadProfileTextures fetches the signed textures itself (on its
	 * own thread); the callback lands on the client thread once each texture is
	 * registered. Until then the default skin shows.
	 */
	private static void requestSkin(Minecraft mc, GameProfile profile) {
		if (profile.getId().equals(skinOwner)) {
			return;
		}
		skinOwner = profile.getId();
		skinTex = null;
		capeTex = null;
		skinSlim = false;
		// A callback from an account that is no longer the current one is dropped.
		final UUID requestedFor = skinOwner;
		try {
			mc.getSkinManager().loadProfileTextures(profile, (type, location, texture) -> {
				if (!requestedFor.equals(skinOwner)) {
					return;
				}
				if (type == MinecraftProfileTexture.Type.SKIN) {
					skinSlim = texture != null && "slim".equals(texture.getMetadata("model"));
					skinTex = location;
				} else if (type == MinecraftProfileTexture.Type.CAPE) {
					capeTex = location;
				}
			}, true);
		} catch (Throwable ignored) {
			// stay on the default skin
		}
	}

	private static ModelPlayer model(boolean isSlim) {
		if (isSlim) {
			if (slim == null) {
				slim = new ModelPlayer(0.0F, true);
			}
			return slim;
		}
		if (wide == null) {
			wide = new ModelPlayer(0.0F, false);
		}
		return wide;
	}

	/**
	 * What RenderPlayer.setModelVisibilities + setRotationAngles would set for a
	 * grown-up player standing still: your skin-layer choices from Skin
	 * Customization, and vanilla's idle arm sway. (This model instance is ours
	 * alone, so only the angles it animates are touched; rotation points keep
	 * the constructor's standing values.)
	 */
	private static void setUp(Minecraft mc, ModelPlayer m, float ticks) {
		Set<EnumPlayerModelParts> parts = mc.gameSettings.getModelParts();
		m.bipedHeadwear.showModel = parts.contains(EnumPlayerModelParts.HAT);
		m.bipedBodyWear.showModel = parts.contains(EnumPlayerModelParts.JACKET);
		m.bipedLeftLegwear.showModel = parts.contains(EnumPlayerModelParts.LEFT_PANTS_LEG);
		m.bipedRightLegwear.showModel = parts.contains(EnumPlayerModelParts.RIGHT_PANTS_LEG);
		m.bipedLeftArmwear.showModel = parts.contains(EnumPlayerModelParts.LEFT_SLEEVE);
		m.bipedRightArmwear.showModel = parts.contains(EnumPlayerModelParts.RIGHT_SLEEVE);
		zero(m.bipedHead);
		zero(m.bipedBody);
		zero(m.bipedRightLeg);
		zero(m.bipedLeftLeg);
		// ModelBiped's idle arm sway
		m.bipedRightArm.rotateAngleY = 0f;
		m.bipedLeftArm.rotateAngleY = 0f;
		m.bipedRightArm.rotateAngleZ = (float) Math.cos(ticks * 0.09f) * 0.05f + 0.05f;
		m.bipedLeftArm.rotateAngleZ = -m.bipedRightArm.rotateAngleZ;
		m.bipedRightArm.rotateAngleX = (float) Math.sin(ticks * 0.067f) * 0.05f;
		m.bipedLeftArm.rotateAngleX = -m.bipedRightArm.rotateAngleX;
		// ModelPlayer copies each limb's angles onto its overlay
		copy(m.bipedHead, m.bipedHeadwear);
		copy(m.bipedBody, m.bipedBodyWear);
		copy(m.bipedRightArm, m.bipedRightArmwear);
		copy(m.bipedLeftArm, m.bipedLeftArmwear);
		copy(m.bipedRightLeg, m.bipedRightLegwear);
		copy(m.bipedLeftLeg, m.bipedLeftLegwear);
	}

	private static void zero(ModelRenderer r) {
		r.rotateAngleX = 0f;
		r.rotateAngleY = 0f;
		r.rotateAngleZ = 0f;
	}

	/** ModelBase.copyModelAngles, spelled out on ModelRenderer's public fields. */
	private static void copy(ModelRenderer from, ModelRenderer to) {
		to.rotateAngleX = from.rotateAngleX;
		to.rotateAngleY = from.rotateAngleY;
		to.rotateAngleZ = from.rotateAngleZ;
		to.rotationPointX = from.rotationPointX;
		to.rotationPointY = from.rotationPointY;
		to.rotationPointZ = from.rotationPointZ;
	}
}
