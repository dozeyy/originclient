package com.origin.client.client.mods;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Matrix4f;
import org.joml.Vector4f;

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
 */
public final class TeamView {
	private static final String MOD = "teamview";
	private static final int MAX_TEAM = 8;

	private TeamView() {
	}

	private record Marker(float x, float y, float dist, int color) {
	}

	// Written by the world pass, read by the HUD pass -- both on the render thread.
	private static List<Marker> markers = new ArrayList<>();

	/** World pass (WorldRenderEvents.LAST): project each marked player's head. */
	static void capture(WorldRenderContext ctx) {
		List<Marker> out = new ArrayList<>();
		markers = out;
		Minecraft mc = Minecraft.getInstance();
		if (!Mods.on(MOD) || mc.player == null || mc.level == null || ctx.matrixStack() == null) {
			return;
		}
		boolean teams = Mods.bool(MOD, "scoreboardTeams");
		boolean party = Mods.bool(MOD, "hypixel") && Hypixel.inSafeArea();
		PlayerTeam myTeam = mc.player.getTeam();
		if (myTeam != null && myTeam.getPlayers().size() > MAX_TEAM) {
			myTeam = null;
		}
		Set<String> partyNames = party ? Hypixel.partyMembers() : Set.of();
		if ((!teams || myTeam == null) && partyNames.isEmpty()) {
			return;
		}

		// Same composition WaypointHud uses: ModelViewMat x the pass's pose.
		Matrix4f view = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
		view.mul(ctx.matrixStack().last().pose());
		Matrix4f proj = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
		Vec3 cam = ctx.camera().getPosition();
		float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
		int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
		int teamColor = Mods.color(MOD, "teamColor");
		int partyColor = Mods.color(MOD, "partyColor");

		for (AbstractClientPlayer p : mc.level.players()) {
			if (p == mc.player || p.isSpectator()) {
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
			Vec3 pos = p.getPosition(pt);
			double wx = pos.x - cam.x, wy = pos.y + p.getBbHeight() + 0.75 - cam.y, wz = pos.z - cam.z;
			Vector4f v = new Vector4f((float) wx, (float) wy, (float) wz, 1f);
			view.transform(v);
			proj.transform(v);
			if (v.w <= 0.05f) {
				continue;   // behind the camera
			}
			float sx = (v.x / v.w * 0.5f + 0.5f) * sw;
			float sy = (0.5f - v.y / v.w * 0.5f) * sh;
			if (sx < -20 || sx > sw + 20 || sy < -20 || sy > sh + 20) {
				continue;
			}
			out.add(new Marker(sx, sy, (float) Math.sqrt(wx * wx + wy * wy + wz * wz), color));
		}
	}

	/** HUD pass: a down-pointing marker over each teammate, smaller with distance. */
	static void render(Gfx g) {
		Minecraft mc = Minecraft.getInstance();
		if (!Mods.on(MOD) || mc.options.hideGui || mc.player == null) {
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
