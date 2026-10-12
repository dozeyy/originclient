package com.origin.client.client.mods;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Lunar-parity mods' version shim (1.19.3). Gfx centralizes the GUI drawing
 * differences between eras; this class does the same for everything else those
 * mods touch whose shape moved between 1.18.2 and 1.21.1 -- chat sending, the
 * int-slot scoreboard, rotations, world text, resource reads and
 * the accessibility options. Every module has its own copy with the SAME method
 * set, so the mods themselves are one source across the pre-1.20 modules and
 * only this file (plus a few mixins) differs per version.
 *
 * 1.19.3 shapes (decompiled 1.19.3 client): ClientPacketListener.sendChat /
 * sendCommand (1.19.3+), Component.literal, Scoreboard.getDisplayObjective(int)
 * + getPlayerScores(Objective) (DisplaySlot/ScoreHolder are 1.20.2+), JOML math +
 * com.mojang.math.Axis (1.19.3+), Font.drawInBatch(..., boolean seeThrough, ...)
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

	/** A chat line, as if typed (signed like typed chat on this version). */
	public static void sendChat(Minecraft mc, String msg) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendChat(msg);
		}
	}

	/** A command WITHOUT its leading slash, as if typed. */
	public static void sendCommand(Minecraft mc, String command) {
		if (mc.getConnection() != null) {
			mc.getConnection().sendCommand(command);
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
			out.add(PlayerTeam.formatNameForTeam(sb.getPlayersTeam(owner), Component.literal(owner)));
		}
		return out;
	}

	/** A player's score in an objective, or null when they have none (never creates one). */
	public static Integer score(Scoreboard sb, String name, Objective o) {
		return sb.hasPlayerScore(name, o) ? sb.getOrCreatePlayerScore(name, o).getScore() : null;
	}

	// ---- pose rotations (com.mojang.math.Axis from 1.19.3) ----

	public static void rotX(PoseStack pose, float degrees) {
		pose.mulPose(Axis.XP.rotationDegrees(degrees));
	}

	public static void rotY(PoseStack pose, float degrees) {
		pose.mulPose(Axis.YP.rotationDegrees(degrees));
	}

	public static void rotZ(PoseStack pose, float degrees) {
		pose.mulPose(Axis.ZP.rotationDegrees(degrees));
	}

	public static void rotYRad(PoseStack pose, float radians) {
		pose.mulPose(Axis.YP.rotation(radians));
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
		Matrix4f m = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
		return new float[]{m.m30(), m.m31()};
	}

	/**
	 * World point (camera-relative) -> GUI pixels, through the world pass's view
	 * pose and RenderSystem's projection. null when behind the camera. On this
	 * era the world pass's PoseStack carries the camera rotation itself, so the
	 * view is that pose alone (never times the model-view -- see TeamView).
	 */
	public static float[] projectToGui(PoseStack view, double x, double y, double z) {
		Vector4f v = new Vector4f((float) x, (float) y, (float) z, 1f);
		new Matrix4f(view.last().pose()).transform(v);
		new Matrix4f(RenderSystem.getProjectionMatrix()).transform(v);
		if (v.w <= 0.05f) {
			return null;
		}
		var w = Minecraft.getInstance().getWindow();
		return new float[]{(v.x / v.w * 0.5f + 0.5f) * w.getGuiScaledWidth(),
				(0.5f - v.y / v.w * 0.5f) * w.getGuiScaledHeight()};
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
		BufferUploader.drawWithShader(bb.end());
	}

	// ---- world text (nametag-style) ----

	/** Font.drawInBatch with the see-through flag spelled the era's way. */
	public static void drawWorldText(Font font, Component text, float x, float y, int color, boolean shadow,
									 PoseStack pose, MultiBufferSource buffers, boolean seeThrough, int bg, int light) {
		font.drawInBatch(text, x, y, color, shadow, pose.last().pose(), buffers, seeThrough, bg, light);
	}

	// ---- options ----

	public static boolean reducedDebugInfo(Options o) {
		return o.reducedDebugInfo().get();
	}

	public static AttackIndicatorStatus attackIndicator(Options o) {
		return o.attackIndicator().get();
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
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
		if (res.isEmpty()) {
			return null;
		}
		return new Opened(res.get().open(), res.get().sourcePackId());
	}

	/** A pack's root file (pack.png), or null. Caller closes the stream. */
	public static InputStream packRootFile(net.minecraft.server.packs.PackResources res, String name) throws Exception {
		var supplier = res.getRootResource(name);
		return supplier == null ? null : supplier.get();
	}
}
