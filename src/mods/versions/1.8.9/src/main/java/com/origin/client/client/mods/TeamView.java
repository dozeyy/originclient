package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Team View (Lunar parity): a marker over each teammate's head, drawn on the HUD
 * so it shows through walls -- the same screen-projection approach as the
 * waypoint labels (WaypointHud): the world pass projects each head to gui
 * pixels, the HUD pass draws the markers. Being a HUD draw, world lighting and
 * shaderpacks can't hide or tint it.
 *
 * Who counts:
 *  - Scoreboard Teams: players on YOUR scoreboard team (how every team server,
 *    Hypixel included, groups teammates). Teams bigger than 8 are ignored -- a
 *    server that puts every player on one team (or a rank team) has no teammates
 *    to show.
 *  - Hypixel Parties: your Hypixel party (the roster Hypixel tracks from party
 *    chat lines), but only in a lobby or SkyBlock -- in a game, a party member on
 *    the other team would be an unfair wallhack, exactly why Lunar limits it too.
 *
 * Only players the server already sends to the client can be marked (vanilla
 * entity tracking range); nothing is requested from the server.
 *
 * 1.8.9: no matrix objects. The world pass runs from Forge's RenderWorldLastEvent
 * (ServerHooks), where the GL model-view is the camera transform (rotation, eye
 * height, view bobbing, third-person offset) applied to positions relative to
 * RenderManager.viewerPos -- exactly how every entity was just drawn -- so the
 * live GL MODELVIEW and PROJECTION matrices are read back once per frame and
 * each head is projected with them by hand.
 */
public final class TeamView {
	private static final String MOD = "teamview";
	private static final int MAX_TEAM = 8;

	private TeamView() {
	}

	// Java 8 has no records: a final value class.
	private static final class Marker {
		private final float x;
		private final float y;
		private final float dist;
		private final int color;

		private Marker(float x, float y, float dist, int color) {
			this.x = x;
			this.y = y;
			this.dist = dist;
			this.color = color;
		}

		float x() {
			return x;
		}

		float y() {
			return y;
		}

		float dist() {
			return dist;
		}

		int color() {
			return color;
		}
	}

	// Written by the world pass, read by the HUD pass -- both on the main thread.
	private static List<Marker> markers = new ArrayList<>();
	private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
	private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
	private static final float[] MV = new float[16], PR = new float[16];

	/** World pass (Forge RenderWorldLastEvent): project each marked player's head. */
	public static void capture(float partialTicks) {
		Minecraft mc = Minecraft.getMinecraft();
		if (!Mods.on(MOD) || mc.thePlayer == null || mc.theWorld == null) {
			// Off (the common case): no list to allocate every frame.
			markers = java.util.Collections.<Marker>emptyList();
			return;
		}
		List<Marker> out = new ArrayList<>();
		markers = out;
		boolean teams = Mods.bool(MOD, "scoreboardTeams");
		boolean party = Mods.bool(MOD, "hypixel") && Hypixel.inSafeArea();
		Team team = mc.thePlayer.getTeam();
		ScorePlayerTeam myTeam = team instanceof ScorePlayerTeam ? (ScorePlayerTeam) team : null;
		if (myTeam != null && myTeam.getMembershipCollection().size() > MAX_TEAM) {
			myTeam = null;
		}
		Set<String> partyNames = party ? Hypixel.partyMembers() : java.util.Collections.<String>emptySet();
		if ((!teams || myTeam == null) && partyNames.isEmpty()) {
			return;
		}

		// The camera transform exactly once: the live GL matrices at the end of the
		// world pass (column-major, as GL returns them).
		MODELVIEW.clear();
		PROJECTION.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
		MODELVIEW.get(MV);
		PROJECTION.get(PR);
		RenderManager rm = mc.getRenderManager();
		double camX = rm.viewerPosX, camY = rm.viewerPosY, camZ = rm.viewerPosZ;
		ScaledResolution sr = Gfx.scaled();
		int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
		int teamColor = Mods.color(MOD, "teamColor");
		int partyColor = Mods.color(MOD, "partyColor");

		for (EntityPlayer p : mc.theWorld.playerEntities) {
			if (p == mc.thePlayer || p.isSpectator()) {
				continue;
			}
			int color;
			if (teams && myTeam != null && p.getTeam() == myTeam) {
				color = teamColor;
			} else if (partyNames.contains(p.getGameProfile().getName().toLowerCase(Locale.ROOT))) {
				color = partyColor;
			} else {
				continue;
			}
			double px = p.lastTickPosX + (p.posX - p.lastTickPosX) * partialTicks;
			double py = p.lastTickPosY + (p.posY - p.lastTickPosY) * partialTicks;
			double pz = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * partialTicks;
			float wx = (float) (px - camX), wy = (float) (py + p.height + 0.75 - camY), wz = (float) (pz - camZ);
			// eye = MV * (x, y, z, 1); clip = PR * eye
			float ex = MV[0] * wx + MV[4] * wy + MV[8] * wz + MV[12];
			float ey = MV[1] * wx + MV[5] * wy + MV[9] * wz + MV[13];
			float ez = MV[2] * wx + MV[6] * wy + MV[10] * wz + MV[14];
			float ew = MV[3] * wx + MV[7] * wy + MV[11] * wz + MV[15];
			float cx = PR[0] * ex + PR[4] * ey + PR[8] * ez + PR[12] * ew;
			float cy = PR[1] * ex + PR[5] * ey + PR[9] * ez + PR[13] * ew;
			float cw = PR[3] * ex + PR[7] * ey + PR[11] * ez + PR[15] * ew;
			if (cw <= 0.05f) {
				continue;   // behind the camera
			}
			float sx = (cx / cw * 0.5f + 0.5f) * sw;
			float sy = (0.5f - cy / cw * 0.5f) * sh;
			if (sx < -20 || sx > sw + 20 || sy < -20 || sy > sh + 20) {
				continue;
			}
			out.add(new Marker(sx, sy, (float) Math.sqrt(wx * wx + wy * wy + wz * wz), color));
		}
	}

	/** HUD pass: a down-pointing marker over each teammate, smaller with distance. */
	public static void render(Gfx g) {
		Minecraft mc = Minecraft.getMinecraft();
		if (!Mods.on(MOD) || mc.gameSettings.hideGUI || mc.thePlayer == null) {
			return;
		}
		for (Marker m : markers) {
			// 5 px half-width up close, easing to 3 px by ~64 blocks.
			int r = Math.max(3, Math.round(5f - Math.min(2f, m.dist() / 32f)));
			triangle(g, Math.round(m.x()), Math.round(m.y()), r, m.color());
		}
	}

	// A filled downward triangle with a 1 px dark rim (readable on sky and snow),
	// tip at (cx, cy + r).
	private static void triangle(Gfx g, int cx, int cy, int r, int color) {
		int fill = (color >>> 24) == 0 ? (0xFF000000 | color) : color;
		int rim = 0xC0000000;
		// rim: the same shape two steps bigger, one row higher and one row lower
		for (int i = 0; i <= r + 2; i++) {
			int half = r + 2 - i;
			g.fill(cx - half, cy - r - 1 + i, cx + half + 1, cy - r + i, rim);
		}
		for (int i = 0; i <= r; i++) {
			int half = r - i;
			g.fill(cx - half, cy - r + i, cx + half + 1, cy - r + i + 1, fill);
		}
	}
}
