package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no level, so no player entity exists to extract a render state from.
 * Instead this fills in the render state that extraction would have produced
 * for you standing still:
 *
 *   - the body: your skin from SkinManager (the title screen head chip's
 *     lookup), with your Skin Customization layer choices;
 *   - the cape: the equipped Origin cape (CapeTextures), swapped into the
 *     state's PlayerSkin exactly as CosmeticCapeMixin swaps it in game, else
 *     the account's own cape -- vanilla's cape layer draws it, with a gentle
 *     resting sway;
 *   - the head item: CosmeticHeadLayer draws it, finding the wearer through
 *     {@link #headFor} (the state carries no level entity to look up).
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era): 1.21.1 draws a baked
 * PlayerModel immediately into GuiGraphics' buffer source. This era's GUI is
 * deferred and its 3D draws are picture-in-picture, so the hand-filled
 * AvatarRenderState goes through g.submitEntityRenderState -- the same call
 * the in-world preview makes -- and the game's own player renderer (layers,
 * lighting) draws it into the box later in the frame. The pose and camera
 * match the in-world preview, so both turn the same way.
 *
 * Fails soft: any error while building the state turns it off for the session
 * and the caller shows its "join a world" message instead.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** Full-bright packed light, as renderEntityInInventory uses. */
	private static final int LIGHT = 15728880;
	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;
	/** The entity id the menu state carries: never a real entity's (those are >= 0). */
	public static final int STATE_ID = -1_998_877;
	// Our cape frames only cover the cape, so the elytra slot keeps its own texture.
	private static final Identifier ELYTRA = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

	private static UUID skinOwner;
	private static Supplier<PlayerSkin> skinSupplier;
	private static boolean broken = false;
	/** The head cosmetic the menu state wears (read by CosmeticHeadLayer during the frame). */
	private static volatile String menuHead = null;

	/** The head cosmetic for a render state that is the menu preview's, else null. */
	public static String headFor(AvatarRenderState state) {
		return state != null && state.id == STATE_ID ? menuHead : null;
	}

	/**
	 * Submits the player into the GUI-px box (x0,y0)-(x1,y1), its middle at
	 * (cx, cy) GUI px, `size` GUI px per block, turned `yaw` degrees (same sense
	 * as the in-world preview), wearing `look`. Render thread. False if it
	 * couldn't.
	 */
	public static boolean render(GuiGraphics g, int x0, int y0, int x1, int y1, float cx, float cy, float size,
			float yaw, CosmeticsService.Look look) {
		if (broken || x1 <= x0 || y1 <= y0 || size <= 0) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		try {
			PlayerSkin skin = skin(mc);
			if (skin == null) {
				return false;
			}
			String capeId = look == null ? null : look.cape();
			Identifier cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape != null) {
				ClientAsset.Texture elytra = skin.elytra() != null ? skin.elytra()
						: new ClientAsset.ResourceTexture(ELYTRA, ELYTRA);
				skin = new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(cape, cape), elytra,
						skin.model(), skin.secure());
			}
			String headId = look == null ? null : look.head();
			menuHead = headId != null && Cosmetics.get(headId) != null ? headId : null;

			float ticks = (Util.getMillis() % 3_600_000L) / 50f;
			AvatarRenderState st = new AvatarRenderState();
			st.entityType = EntityType.PLAYER;
			st.id = STATE_ID;
			st.skin = skin;
			st.ageInTicks = ticks;           // vanilla's idle arm sway reads this
			st.bodyRot = 180 + yaw;          // what the in-world preview sets on the player
			st.yRot = 0;
			st.xRot = 0;
			st.lightCoords = LIGHT;
			st.boundingBoxWidth = 0.6f;
			st.boundingBoxHeight = HEIGHT;
			st.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
			st.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
			st.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
			st.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
			st.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
			st.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
			st.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE);
			// CapeLayer's resting pose (6 deg + lean/2 + flap) with 1.21.1's gentle sway.
			st.capeFlap = 2.5f * (float) Math.sin(ticks * 0.06f);
			st.capeLean = 0f;
			st.capeLean2 = 0f;

			// The in-world preview's pose and camera; offset puts the model's middle
			// at (cx, cy) instead of the box centre (model units: before the scale).
			Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
			Quaternionf camera = new Quaternionf().rotateX(-0.12f);
			pose.mul(camera);
			float bcx = (x0 + x1) / 2f, bcy = (y0 + y1) / 2f;
			Vector3f offset = new Vector3f((cx - bcx) / size, HEIGHT / 2f + (cy - bcy) / size, 0);
			g.submitEntityRenderState(st, size, offset, pose, camera, x0, y0, x1, y1);
			return true;
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: menu preview failed; showing the message instead", t);
			return false;
		}
	}

	/** Your skin: the resolved profile (it carries the signed textures), as the title screen does. */
	private static PlayerSkin skin(Minecraft mc) {
		GameProfile profile = mc.getGameProfile();
		if (profile == null || profile.id() == null) {
			return null;
		}
		if (skinSupplier == null || !profile.id().equals(skinOwner)) {
			skinOwner = profile.id();
			skinSupplier = mc.getSkinManager().createLookup(profile, false);
		}
		// Nick Hider's "hide own skin" applies here too, as on your in-world model.
		PlayerSkin hidden = NickHider.skinFor(profile);
		return hidden != null ? hidden : skinSupplier.get();
	}
}
