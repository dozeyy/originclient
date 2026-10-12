package com.origin.client.client.cosmetics;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.NickHider;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The signed-in player drawn WITHOUT a world (Will, 2026-10-12: "in the main
 * menu you should also be able to see your character"). From the title screen
 * there is no level, so no player entity exists to hand to
 * InventoryScreen.renderEntityInInventory.
 *
 * PER-VERSION (1.21.4, covers 1.21.2-1.21.4): entity renderers in this era draw
 * from a render STATE, not the entity, so this fills a PlayerRenderState by hand
 * (your skin from SkinManager -- the same lookup the title screen's head chip
 * uses -- with the equipped Origin cape swapped in, your Skin Customization
 * choices, the turn) and hands it to a PlayerRenderer. Vanilla's own body, cape
 * layer and arm sway then draw it exactly as in the world; the head item comes
 * from CosmeticHeadLayer, which reads it from {@link #headId()} when the state
 * carries {@link #STATE_ID}. The renderers are built from the
 * EntityRendererProvider.Context Fabric hands OriginClientMod's layer callback
 * (refreshed on every resource reload); they are this preview's own instances,
 * so CosmeticHeadLayer is added to them here.
 *
 * Transforms mirror renderEntityInInventory step for step, so this and the
 * in-world preview line up and turn the same way. Fails soft: any error turns
 * it off for the session and the caller shows its "join a world" message instead.
 */
public final class MenuPlayerPreview {
	private MenuPlayerPreview() {
	}

	/** Full-bright packed light, as renderEntityInInventory uses. */
	private static final int LIGHT = 15728880;
	/** A standing player's hitbox height (blocks): centres the model like the entity path. */
	public static final float HEIGHT = 1.8f;
	/** PlayerRenderState.id of the preview: no real entity is ever negative. */
	public static final int STATE_ID = -0x0716;

	private static EntityRendererProvider.Context context;
	private static PlayerRenderer wide, slim;
	private static UUID skinOwner;
	private static Supplier<PlayerSkin> skinSupplier;
	private static volatile String head;
	private static boolean broken = false;

	/** OriginClientMod's layer callback: the context this reload's renderers were built from. */
	public static void onRenderersCreated(EntityRendererProvider.Context ctx) {
		if (ctx != context) {
			context = ctx;
			wide = null;
			slim = null;
		}
	}

	/** The head cosmetic the preview is wearing (CosmeticHeadLayer, for STATE_ID). */
	public static String headId() {
		return head;
	}

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
			PlayerRenderer renderer = renderer(skin.model() == PlayerSkin.Model.SLIM);
			if (renderer == null) {
				return false;
			}
			String capeId = look == null ? null : look.cape();
			ResourceLocation cape = capeId != null ? CapeTextures.current(capeId) : null;
			if (cape != null) {
				// the CosmeticCapeMixin swap, for a skin no player entity carries
				skin = new PlayerSkin(skin.texture(), skin.textureUrl(), cape, skin.elytraTexture(), skin.model(), skin.secure());
			}
			String headId = look == null ? null : look.head();
			head = headId != null && Cosmetics.get(headId) != null ? headId : null;

			PlayerRenderState st = renderer.createRenderState();
			st.id = STATE_ID;
			st.skin = skin;
			st.ageInTicks = (Util.getMillis() % 3_600_000L) / 50f;   // drives the idle arm sway
			st.bodyRot = 180f + yaw;    // LivingEntityRenderer turns by 180 - bodyRot = -yaw
			st.yRot = 0f;               // head straight ahead of the body
			st.xRot = 0f;
			st.showHat = mc.options.isModelPartEnabled(PlayerModelPart.HAT);
			st.showJacket = mc.options.isModelPartEnabled(PlayerModelPart.JACKET);
			st.showLeftPants = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
			st.showRightPants = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
			st.showLeftSleeve = mc.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
			st.showRightSleeve = mc.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
			st.showCape = mc.options.isModelPartEnabled(PlayerModelPart.CAPE);

			// InventoryScreen.renderEntityInInventory (x, y, 50; scale; offset; pose)
			pose.translate(cx, cy, 50.0f);
			pose.scale(size, size, -size);
			pose.translate(0f, HEIGHT / 2f, 0f);
			pose.mulPose(new Quaternionf().rotateZ((float) Math.PI).rotateX(-0.12f));

			// The batched GUI beneath goes first (vanilla's preview does the same).
			g.flush();
			Lighting.setupForEntityInInventory();
			lit = true;
			g.drawSpecial(buffers -> renderer.render(st, pose, buffers, LIGHT));
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

	/** This preview's own renderer for the skin's model (wide / slim arms). */
	private static PlayerRenderer renderer(boolean isSlim) {
		EntityRendererProvider.Context ctx = context;
		if (ctx == null) {
			return null;
		}
		if (isSlim) {
			if (slim == null) {
				slim = build(ctx, true);
			}
			return slim;
		}
		if (wide == null) {
			wide = build(ctx, false);
		}
		return wide;
	}

	private static PlayerRenderer build(EntityRendererProvider.Context ctx, boolean isSlim) {
		return new PlayerRenderer(ctx, isSlim) {
			{
				// Fabric's layer callback only runs for the game's own renderers.
				addLayer(new CosmeticHeadLayer(this));
			}
		};
	}

	/** Your skin: the resolved profile (it carries the signed textures), as the title screen does. */
	private static PlayerSkin skin(Minecraft mc) {
		GameProfile profile = mc.getGameProfile();
		if (profile == null || profile.getId() == null) {
			return null;
		}
		if (skinSupplier == null || !profile.getId().equals(skinOwner)) {
			skinOwner = profile.getId();
			skinSupplier = mc.getSkinManager().lookupInsecure(profile);
		}
		// Nick Hider's "hide own skin" applies here too, as on your in-world model.
		PlayerSkin hidden = NickHider.skinFor(profile);
		return hidden != null ? hidden : skinSupplier.get();
	}
}
