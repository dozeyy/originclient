package com.origin.client.client.mods;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Lunar-parity mods' version shim (1.18.2). Gfx centralizes the GUI drawing
 * differences between eras; this class does the same for everything else those
 * mods touch whose shape moved between 1.18.2 and 1.21.1 -- chat sending, the
 * int-slot scoreboard, rotations, world text, resource reads and
 * the accessibility options. Every module has its own copy with the SAME method
 * set, so the mods themselves are one source across the pre-1.20 modules and
 * only this file (plus a few mixins) differs per version.
 *
 * 1.18.2 shapes (decompiled 1.18.1 client + this module's own CI-green code):
 * LocalPlayer.chat(String) for chat AND commands ("/" prefix; no signed chat
 * before 1.19), TextComponent (Component.literal is 1.19), plain-field options
 * (OptionInstance is 1.19), ResourceManager.getResource throws when missing and
 * Resource.getInputStream/getSourceName (Optional + open/sourcePackId are 1.19),
 * BufferBuilder.end() + BufferUploader.end(builder) (drawWithShader is 1.19),
 * Scoreboard.getDisplayObjective(int) + getPlayerScores(Objective), pre-JOML
 * com.mojang.math (Matrix4f / Vector4f / Vector3f.XP axes; JOML + Axis are
 * 1.19.3), PackResources.getRootResource returns the stream itself (IoSupplier
 * is 1.19.3), Font.drawInBatch(..., boolean seeThrough, ...)
 * (DisplayMode is 1.19.4), ResourceManager.getResource -> Optional (1.19+),
 * OptionInstance options (1.19+); no Glint Speed / Damage Tilt / Notification
 * Time sliders yet (1.19.4), the hurt direction is a public field, one glint
 * texture for items and armor, ItemTransforms.TransformType (ItemDisplayContext
 * is 1.19.4), no BlockPos.containing (1.19.4).
 */
public final class Era {
	private Era() {
	}

	// ---- chat out (only ever what the player configured) ----

	/** A chat line, as if typed. */
	public static void sendChat(Minecraft mc, String msg) {
		if (mc.player != null) {
			mc.player.chat(msg);
		}
	}

	/** A command WITHOUT its leading slash, as if typed (pre-1.19 a command is
	 *  just a chat packet starting with "/"). */
	public static void sendCommand(Minecraft mc, String command) {
		if (mc.player != null) {
			mc.player.chat("/" + command);
		}
	}

	// ---- scoreboard (int display slots before 1.20.2) ----

	public static Objective sidebar(Scoreboard sb) {
		return sb.getDisplayObjective(Scoreboard.DISPLAY_SLOT_SIDEBAR);
	}

	public static Objective belowName(Scoreboard sb) {
		return sb.getDisplayObjective(Scoreboard.DISPLAY_SLOT_BELOW_NAME);
	}

	public static Objective tabList(Scoreboard sb) {
		return sb.getDisplayObjective(Scoreboard.DISPLAY_SLOT_LIST);
	}

	/** The sidebar's visible lines, formatted the way vanilla draws them (team
	 *  prefix + entry + suffix); "#"-named entries are hidden, as in vanilla. */
	public static List<Component> sidebarLines(Scoreboard sb, Objective o) {
		List<Component> out = new ArrayList<>();
		for (Score s : sb.getPlayerScores(o)) {
			String owner = s.getOwner();
			if (owner == null || owner.startsWith("#")) {
				continue;
			}
			out.add(PlayerTeam.formatNameForTeam(sb.getPlayersTeam(owner), new TextComponent(owner)));
		}
		return out;
	}

	/** A player's score in an objective, or null when they have none (never creates one). */
	public static Integer score(Scoreboard sb, String name, Objective o) {
		return sb.hasPlayerScore(name, o) ? sb.getOrCreatePlayerScore(name, o).getScore() : null;
	}

	// ---- pose rotations (Vector3f axes before com.mojang.math.Axis, 1.19.3) ----

	public static void rotX(PoseStack pose, float degrees) {
		pose.mulPose(Vector3f.XP.rotationDegrees(degrees));
	}

	public static void rotY(PoseStack pose, float degrees) {
		pose.mulPose(Vector3f.YP.rotationDegrees(degrees));
	}

	public static void rotZ(PoseStack pose, float degrees) {
		pose.mulPose(Vector3f.ZP.rotationDegrees(degrees));
	}

	public static void rotYRad(PoseStack pose, float radians) {
		pose.mulPose(Vector3f.YP.rotation(radians));
	}

	/** The entity-renderer billboard: face the camera (nametag orientation). */
	public static void faceCamera(PoseStack pose) {
		pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
	}

	/** The block a world point is in (BlockPos.containing from 1.19.4). */
	public static net.minecraft.core.BlockPos blockPos(net.minecraft.world.phys.Vec3 v) {
		return new net.minecraft.core.BlockPos(v);
	}

	// ---- matrices ----

	/** Where a pose's local origin lands in GUI pixels: RenderSystem's model-view
	 *  times the pose (older ToastComponents translate the model-view stack, newer
	 *  ones the PoseStack -- this reads both). */
	public static float[] guiOrigin(PoseStack pose) {
		Matrix4f m = RenderSystem.getModelViewMatrix().copy();
		m.multiply(pose.last().pose());
		Vector4f o = new Vector4f(0f, 0f, 0f, 1f);
		o.transform(m);
		return new float[]{o.x(), o.y()};
	}

	/**
	 * World point (camera-relative) -> GUI pixels, through the world pass's view
	 * pose and RenderSystem's projection. null when behind the camera. On this
	 * era the world pass's PoseStack carries the camera rotation itself, so the
	 * view is that pose alone (never times the model-view -- see TeamView).
	 */
	public static float[] projectToGui(PoseStack view, double x, double y, double z) {
		Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1f);
		v.transform(view.last().pose());
		v.transform(RenderSystem.getProjectionMatrix());
		if (v.w() <= 0.05f) {
			return null;
		}
		var w = Minecraft.getInstance().getWindow();
		return new float[]{(v.x() / v.w() * 0.5f + 0.5f) * w.getGuiScaledWidth(),
				(0.5f - v.y() / v.w() * 0.5f) * w.getGuiScaledHeight()};
	}

	// ---- immediate geometry ----

	/** The shared Tesselator builder, begun (pre-1.20.5 there is one builder; only
	 *  one shape may be open at a time, and text drawing uses it too). */
	public static BufferBuilder begin(com.mojang.blaze3d.vertex.VertexFormat.Mode mode,
									  com.mojang.blaze3d.vertex.VertexFormat format) {
		BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
		b.begin(mode, format);
		return b;
	}

	/** Draws (and ends) a builder from begin(), shader already set. An empty one
	 *  just ends -- vanilla's uploader skips the draw. */
	public static void drawBuffer(BufferBuilder bb) {
		bb.end();
		BufferUploader.end(bb);
	}

	// ---- world text (nametag-style) ----

	/** Font.drawInBatch with the see-through flag spelled the era's way. */
	public static void drawWorldText(Font font, Component text, float x, float y, int color, boolean shadow,
									 PoseStack pose, MultiBufferSource buffers, boolean seeThrough, int bg, int light) {
		font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), buffers, seeThrough, bg, light);
	}

	// ---- options ----

	public static boolean reducedDebugInfo(Options o) {
		return o.reducedDebugInfo;
	}

	public static AttackIndicatorStatus attackIndicator(Options o) {
		return o.attackIndicator;
	}

	/** Accessibility "Notification Time" (1.19.4+; 1.0 = vanilla before it existed). */
	public static double toastTimeMultiplier(net.minecraft.client.gui.components.toasts.ToastComponent toasts) {
		return 1.0;
	}

	/** Accessibility "Glint Speed" (1.19.4+; 1.0 = vanilla before it existed). */
	public static double glintSpeed(Options o) {
		return 1.0;
	}

	/** Accessibility "Damage Tilt" (1.19.4+; 1.0 = vanilla before it existed). */
	public static double damageTilt(Options o) {
		return 1.0;
	}

	/** The direction a hurt entity tilts the camera from (a getter from 1.19.4). */
	public static float hurtDir(LivingEntity e) {
		return e.hurtDir;
	}

	// ---- textures ----

	/** The enchantment-glint textures {item models, armor/entity models}: before
	 *  1.19.4 ONE file serves both (ItemRenderer.ENCHANT_GLINT_LOCATION). */
	public static ResourceLocation[] glintTextures() {
		ResourceLocation glint = net.minecraft.client.renderer.entity.ItemRenderer.ENCHANT_GLINT_LOCATION;
		return new ResourceLocation[]{glint, glint};
	}

	/** A baked model's GROUND display transform (ItemDisplayContext from 1.19.4). */
	public static net.minecraft.client.renderer.block.model.ItemTransform groundTransform(
			net.minecraft.client.resources.model.BakedModel model) {
		return model.getTransforms().getTransform(
				net.minecraft.client.renderer.block.model.ItemTransforms.TransformType.GROUND);
	}

	// ---- resources ----

	/** An open stream of the active resource pack's file + the id of the pack it
	 *  came from, or null when no pack has it. Caller closes the stream. */
	public record Opened(InputStream in, String packId) {
	}

	public static Opened openResource(ResourceLocation loc) throws Exception {
		Resource res;
		try {
			res = Minecraft.getInstance().getResourceManager().getResource(loc);
		} catch (java.io.FileNotFoundException missing) {
			return null;
		}
		// Closing the stream is enough: the Resource holds nothing else open.
		return new Opened(res.getInputStream(), res.getSourceName());
	}

	/** A pack's root file (pack.png), or null. Caller closes the stream. */
	public static InputStream packRootFile(net.minecraft.server.packs.PackResources res, String name) throws Exception {
		return res.getRootResource(name);   // throws when the pack has none; the caller treats that as none
	}
}
