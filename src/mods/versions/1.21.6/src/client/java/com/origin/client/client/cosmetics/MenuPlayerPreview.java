package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no level, so no player entity exists to hand to
 * InventoryScreen.renderEntityInInventory.
 *
 * PER-VERSION (1.21.6-1.21.8): entities in a GUI are a picture-in-picture of
 * an extracted render state (GuiGraphics.submitEntityRenderState, which the
 * boxed renderEntityInInventory calls after extracting). So instead of
 * 1.21.1's hand-drawn model, this fills a PlayerRenderState the way
 * PlayerRenderer.extractRenderState would for a grown-up player standing still
 * and submits it -- vanilla's own PlayerRenderer then draws it, layers and all:
 *
 *   - the body: your skin from SkinManager (wide or slim picks the renderer),
 *     the same lookup the title screen's head chip uses, with Skin
 *     Customization's layer choices;
 *   - the cape: vanilla's CapeLayer, with the equipped Origin cape
 *     (CapeTextures) or else the account's own cape put in the state's skin,
 *     and a gentle sway on capeFlap (CapeLayer's resting 6 degrees + flap);
 *   - the head / face / back items: CosmeticLayer, which recognises this
 *     state by {@link #STATE_ID} and wears {@link #look()} (no entity to look
 *     up); CapePhysicsMixin reads the same look to hide or move back the cape.
 *
 * The look is kept in a static, not cleared after the submit: the picture-in-
 * picture is drawn LATER in the same frame (deferred GUI), when the layer reads
 * it. Only this preview ever carries STATE_ID, so a stale look is never seen.
 *
 * Tile thumbnails ({@link #renderThumb}) are NOT drawn on this version: see there.
 *
 * Same rotation, camera and box placement as the in-world preview, so the two
 * line up and turn the same way. Fails soft: any error turns it off for the
 * session and the caller shows its "join a world" message instead.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;

	/** The id on the submitted state: no entity has it (ids count up from 1). */
	public static final int STATE_ID = Integer.MIN_VALUE + 0x0C05;

	private static volatile CosmeticsService.Look look;
	private static boolean broken = false;

	/** What the menu preview wears (CosmeticLayer + CapePhysicsMixin read it for STATE_ID). */
	public static CosmeticsService.Look look() {
		return look;
	}

	/**
	 * A tile thumbnail (1.21.1: the same model with one item on it, zoomed).
	 * PER-VERSION (1.21.6-1.21.8): always false, so the Cosmetics screen draws
	 * the tile's icon instead. A GUI entity here is a picture-in-picture, and
	 * vanilla keeps ONE PictureInPictureRenderer (one texture) per render-state
	 * class: a grid of player thumbnails plus the big preview in one frame
	 * would all share that texture -- each draw overwriting the last, or a
	 * size change freeing a texture an earlier element still points at. Not
	 * worth the crash risk; a custom PiP renderer per tile would be the fix.
	 */
	public static boolean renderThumb(GuiGraphics g, float cx, float cy, float size, float yaw,
			CosmeticsService.Look wear) {
		return false;
	}

	/**
	 * Submits the player as a picture-in-picture in the GUI-px box (x0..x1,
	 * y0..y1), `size` GUI px per block, its centre `shiftX/shiftY` GUI px from
	 * the box's centre, turned `yaw` degrees (same sense as the in-world
	 * preview), wearing `wear`. Render thread. False if it couldn't draw.
	 */
	public static boolean render(GuiGraphics g, int x0, int y0, int x1, int y1, float size,
			float shiftX, float shiftY, float yaw, CosmeticsService.Look wear) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		try {
			GameProfile profile = mc.getGameProfile();
			if (profile == null || profile.getId() == null) {
				return false;
			}
			// Nick Hider's "hide own skin" applies here too, as on your in-world model.
			PlayerSkin skin = NickHider.skinFor(profile);
			if (skin == null) {
				skin = mc.getSkinManager().getInsecureSkin(profile);
			}
			if (skin == null) {
				return false;
			}
			String capeId = wear == null ? null : wear.cape();
			ResourceLocation cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape != null) {
				// What CosmeticCapeMixin does to a real player's skin.
				skin = new PlayerSkin(skin.texture(), skin.textureUrl(), cape, skin.elytraTexture(), skin.model(), skin.secure());
			}
			boolean capeHidden = wear != null && wear.hidesCape();   // wings / a backpack replace it
			look = wear;

			float ticks = (Util.getMillis() % 3_600_000L) / 50f;
			PlayerRenderState state = new PlayerRenderState();
			state.entityType = EntityType.PLAYER;
			state.id = STATE_ID;
			state.ageInTicks = ticks;
			state.skin = skin;
			state.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
			state.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
			state.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
			state.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
			state.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
			state.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
			state.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE) && !capeHidden;
			// CapeLayer rests at 6 degrees + capeFlap: 1.21.1's gentle sway.
			state.capeFlap = 2.5f * (float) Math.sin(ticks * 0.06f);
			// LivingEntityRenderer turns the body by 180 - bodyRot; the head looks ahead.
			state.bodyRot = 180 + yaw;
			state.yRot = 0;
			state.xRot = 0;

			// InventoryScreen.renderEntityInInventoryFollowsMouse's rotation + camera.
			Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
			Quaternionf camera = new Quaternionf().rotateX(-0.12f);
			rotation.mul(camera);
			Vector3f offset = new Vector3f(shiftX / size, HEIGHT / 2f + shiftY / size, 0);
			g.submitEntityRenderState(state, size, offset, rotation, camera, x0, y0, x1, y1);
			return true;
		} catch (Throwable t) {
			broken = true;
			OriginClient.LOGGER.warn("Origin cosmetics: menu preview failed; showing the message instead", t);
			return false;
		}
	}
}
