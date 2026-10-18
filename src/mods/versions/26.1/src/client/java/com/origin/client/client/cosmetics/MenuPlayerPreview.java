package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   - the head / face / back items: CosmeticLayer draws them, finding what
 *     the state wears through {@link #lookFor} (the state carries no level
 *     entity to look up); CapePhysicsMixin reads the same look to hide the
 *     cape under wings / a backpack or push it back.
 *
 * Also draws the Cosmetics screen's tile thumbnails ({@link #renderThumb}):
 * the same model with just one item on it, zoomed by the caller. Several
 * states can be in flight in one frame (the PIP boxes draw later), so each
 * call takes its own state id and the look is kept per id.
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era): 1.21.1 draws a baked
 * PlayerModel immediately into GuiGraphicsExtractor' buffer source. This era's GUI is
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
	/** Menu states take ids STATE_ID, STATE_ID - 1, ... in a ring of this many (more than a frame ever draws). */
	private static final int ID_RING = 256;
	// Our cape frames only cover the cape, so the elytra slot keeps its own texture.
	private static final Identifier ELYTRA = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");

	private static UUID skinOwner;
	private static Supplier<PlayerSkin> skinSupplier;
	private static boolean broken = false;
	/** What each in-flight menu state wears, by its state id (read by CosmeticLayer / CapePhysicsMixin). */
	private static final Map<Integer, CosmeticsService.Look> MENU_LOOKS = new ConcurrentHashMap<>();
	private static int nextSlot = 0;

	/** The look of a render state that is one of ours, else null (a real player: look it up by entity). */
	public static CosmeticsService.Look lookFor(AvatarRenderState state) {
		if (state == null || state.id > STATE_ID || state.id <= STATE_ID - ID_RING) {
			return null;
		}
		CosmeticsService.Look l = MENU_LOOKS.get(state.id);
		return l != null ? l : CosmeticsService.Look.NONE;
	}

	/**
	 * Submits the player into the GUI-px box (x0,y0)-(x1,y1), its middle at
	 * (cx, cy) GUI px, `size` GUI px per block, turned `yaw` degrees (same sense
	 * as the in-world preview), wearing `look`. Render thread. False if it
	 * couldn't.
	 */
	public static boolean render(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float cx, float cy, float size,
			float yaw, CosmeticsService.Look look) {
		return render(g, x0, y0, x1, y1, cx, cy, size, yaw, look, true);
	}

	/**
	 * A thumbnail: like {@link #render} but with no cape from the account (only
	 * what `look` itself carries) so a tile shows nothing but its own item. The
	 * caller zooms by choosing `cy` and `size` inside its tile's box.
	 */
	public static boolean renderThumb(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float cx, float cy,
			float size, float yaw, CosmeticsService.Look look) {
		return render(g, x0, y0, x1, y1, cx, cy, size, yaw, look, false);
	}

	private static boolean render(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float cx, float cy,
			float size, float yaw, CosmeticsService.Look look, boolean accountCape) {
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
			} else if (!accountCape && skin.cape() != null) {
				skin = new PlayerSkin(skin.body(), null, skin.elytra(), skin.model(), skin.secure());
			}
			int stateId = STATE_ID - nextSlot;
			nextSlot = (nextSlot + 1) % ID_RING;
			MENU_LOOKS.put(stateId, look == null ? CosmeticsService.Look.NONE : look);

			float ticks = (Util.getMillis() % 3_600_000L) / 50f;
			AvatarRenderState st = new AvatarRenderState();
			st.entityType = net.minecraft.world.entity.EntityType.PLAYER;
			st.id = stateId;
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
			// wings / a backpack replace the cape
			st.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE) && (look == null || !look.hidesCape());
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
			g.entity(st, size, offset, pose, camera, x0, y0, x1, y1);
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
