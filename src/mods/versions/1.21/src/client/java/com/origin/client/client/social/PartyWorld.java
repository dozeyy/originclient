package com.origin.client.client.social;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.gui.OriginUi;
import com.origin.client.client.hud.HudElements;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.render.AfterShadersPass;
import com.origin.client.client.render.OriginRenderTypes;
import com.origin.client.client.render.ThickLine;
import com.origin.client.client.social.SocialState.Member;
import com.origin.client.client.social.SocialState.Ping;
import com.origin.client.client.waypoints.WaypointHud;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The party in the world (docs/SOCIAL.md B, C, D, F):
 *  B  party members' nametags tinted ({@link #nametagTint}, read by
 *     NametagStyleMixin) and a marker over each one -- diamond, name, distance
 *     -- drawn as a HUD overlay projected from the world, so it shows through
 *     walls (the waypoint label technique, WaypointHud);
 *  C  the movable "Party" HUD element: name, health, distance, direction;
 *  D  party members as points for the minimap and the locator bar ({@link #mapPoints});
 *  F  pings: a gold beam in the world plus a marker, for 60 s.
 *
 * Every position comes from the party's own sync and is used only in a LAN
 * world the party shares (FairPlay.positionKey, Will's baseline: never on a
 * multiplayer server); a member whose player is loaded here is drawn at the
 * live entity instead (smooth, no 4 s steps). Off a LAN world the Party HUD
 * still lists names + online status.
 */
public final class PartyWorld {
	private PartyWorld() {
	}

	public static final int PING_COLOR = 0xFFE9C46A;

	/** A party member placed in this world. `dim` null = elsewhere on this server. */
	public record Mate(String uuid, String name, String dim, double x, double y, double z,
					   float health, float maxHealth, boolean live) {
	}

	/** Positions may be shown: in a party, in a LAN world (FairPlay). */
	public static boolean positionsHere(Minecraft mc) {
		if (!SocialClient.state().inParty()) {
			return false;
		}
		if (SocialClient.DEMO) {
			return mc.level != null;
		}
		return FairPlay.positionKey(mc) != null;
	}

	/** Same LAN world? (`server` as the member / ping reported it.) */
	private static boolean sameKey(String key, String server) {
		return SocialClient.DEMO || (key != null && server != null && key.equals(Social.normalize(server)));
	}

	public static int color() {
		return 0xFF000000 | (Mods.color(Social.MOD, "partyColor") & 0xFFFFFF);
	}

	/** Every other party member on your server, with the best position we have. */
	public static List<Mate> mates(Minecraft mc, float partial) {
		List<Mate> out = new ArrayList<>();
		SocialState s = SocialClient.state();
		if (s.party() == null || mc.level == null || !positionsHere(mc)) {
			return out;
		}
		String self = s.me() != null ? s.me().uuid() : "";
		String key = FairPlay.positionKey(mc);
		String dim = mc.level.dimension().location().toString();
		for (Member m : s.party().members()) {
			if (m.uuid().equals(self) || m.state() == null) {
				continue;
			}
			var st = m.state();
			if (!sameKey(key, st.server())) {
				continue;
			}
			Player p = player(mc, m.uuid());
			if (p != null) {
				out.add(new Mate(m.uuid(), m.name(), dim, Mth.lerp(partial, p.xo, p.getX()), Mth.lerp(partial, p.yo, p.getY()),
						Mth.lerp(partial, p.zo, p.getZ()), p.getHealth(), p.getMaxHealth(), true));
			} else {
				out.add(new Mate(m.uuid(), m.name(), st.dim(), st.x(), st.y(), st.z(), st.health(), st.maxHealth(), false));
			}
		}
		return out;
	}

	private static Player player(Minecraft mc, String undashed) {
		var id = SocialClient.toUuid(undashed);
		return id == null || mc.level == null ? null : mc.level.getPlayerByUUID(id);
	}

	// ---- B: nametag tint ----

	/** The party colour for a party member's nametag, or null to leave it alone. */
	public static Integer nametagTint(Entity e) {
		if (!(e instanceof Player) || !Social.on("highlightNames")) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		SocialState s = SocialClient.state();
		if (s.party() == null || e == mc.player || !positionsHere(mc)) {
			return null;
		}
		String id = e.getUUID().toString().replace("-", "");
		return s.party().member(id) != null ? color() : null;
	}

	// ---- B + F: the through-wall markers (HUD pass) ----

	/** Called from the HUD dispatcher after the elements, like the waypoint labels. */
	public static void renderMarkers(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !Mods.on(Social.MOD) || !positionsHere(mc)) {
			return;
		}
		Font font = mc.font;
		int sw = g.guiWidth(), sh = g.guiHeight();
		String dim = mc.level.dimension().location().toString();
		Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
		if (Social.on("markers")) {
			int tint = color();
			for (Mate m : mates(mc, 1f)) {
				if (!dim.equals(m.dim())) {
					continue;
				}
				double dist = eye.distanceTo(new Vec3(m.x(), m.y() + 1.0, m.z()));
				if (dist < 4) {
					continue;   // the nametag is right there
				}
				int[] p = WaypointHud.project(m.x(), m.y() + 2.9, m.z(), sw, sh);
				if (p == null) {
					continue;
				}
				label(g, font, p[0], p[1], tint, m.name(), Math.round(dist) + "m", 255);
			}
		}
		if (Social.on("showPings")) {
			long now = System.currentTimeMillis();
			String server = FairPlay.positionKey(mc);
			for (Ping pg : SocialClient.pings()) {
				if (!samePlace(pg, server, dim)) {
					continue;
				}
				double dist = eye.distanceTo(new Vec3(pg.x() + 0.5, pg.y() + 0.5, pg.z() + 0.5));
				int[] p = WaypointHud.project(pg.x() + 0.5, pg.y() + 1.6, pg.z() + 0.5, sw, sh);
				if (p == null) {
					continue;
				}
				int alpha = (int) Mth.clamp((pg.localUntil() - now) / 5000.0 * 255, 40, 255);
				String what = pg.label() == null || pg.label().isEmpty() ? "Ping" : pg.label();
				label(g, font, p[0], p[1], PING_COLOR, what, pg.name() + " · " + Math.round(dist) + "m", alpha);
			}
		}
	}

	private static boolean samePlace(Ping pg, String server, String dim) {
		if (!dim.equals(pg.dim())) {
			return false;
		}
		return sameKey(server, pg.server());
	}

	private static void label(GuiGraphics g, Font font, int x, int y, int color, String top, String bottom, int alpha) {
		WaypointHud.marker(g, x, y, 5, color, alpha);
		int a = alpha << 24;
		int ty = y + 9;
		ty += WaypointHud.pillText(g, font, x, ty, top, 1f, a | (color & 0xFFFFFF), (alpha * 0x60 / 255) << 24);
		WaypointHud.pillText(g, font, x, ty, bottom, 0.75f, a | 0xE0E0E0, (alpha * 0x60 / 255) << 24);
	}

	// ---- F: ping beams (world pass, depth-tested like waypoint beams) ----

	// AfterShadersPass slot; -1 = not registered, so the LAST entry keeps drawing.
	private static int slot = -1;

	/** Registers the after-shaders drawer (from OriginClientMod, in draw order). */
	public static void init() {
		slot = AfterShadersPass.register("party pings", PartyWorld::drawPings);
	}

	/** WorldRenderEvents.LAST entry. Under an Iris shader pack AfterShadersPass
	 *  draws the pillars instead (after the pack's composite, so its clouds
	 *  can't cover them) and this returns at once. */
	public static void renderWorld(WorldRenderContext ctx) {
		if (AfterShadersPass.deferring(slot)) {
			return;
		}
		if (ctx.matrixStack() == null) {
			return;
		}
		drawPings(ctx.matrixStack(), ctx.camera().getPosition(), null);
	}

	// The pillars themselves (the pass's drawer; the LAST path runs the same body).
	// They go through OriginRenderTypes only, so the line buffer is unused.
	private static void drawPings(PoseStack pose, Vec3 c, MultiBufferSource.BufferSource unusedLines) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !Social.on("showPings") || !positionsHere(mc)) {
			return;
		}
		String dim = mc.level.dimension().location().toString();
		String server = FairPlay.positionKey(mc);
		long now = System.currentTimeMillis();
		boolean any = false;
		for (Ping pg : SocialClient.pings()) {
			if (!samePlace(pg, server, dim)) {
				continue;
			}
			double x = pg.x() + 0.5 - c.x, z = pg.z() + 0.5 - c.z;
			double half = Math.max(0.06, Math.sqrt(x * x + z * z) * 0.0014);
			float a = (float) Mth.clamp((pg.localUntil() - now) / 5000.0, 0.15, 0.85);
			ThickLine.vertical(OriginRenderTypes.begin(), pose.last(), x, z,
					pg.y() + 1 - c.y, pg.y() + 64 - c.y, half, 0.914f, 0.769f, 0.416f, a);
			any = true;
		}
		if (any) {
			OriginRenderTypes.draw();
		}
	}

	// ---- D: minimap + locator bar ----

	/** Party members in this dimension as {x, z} (for the minimap / locator bar). */
	public static List<double[]> mapPoints(Minecraft mc) {
		List<double[]> out = new ArrayList<>();
		if (mc.level == null || !Social.on("mapMarkers")) {
			return out;
		}
		String dim = mc.level.dimension().location().toString();
		for (Mate m : mates(mc, 1f)) {
			if (dim.equals(m.dim())) {
				out.add(new double[]{m.x(), m.z()});
			}
		}
		return out;
	}

	// ---- C: the Party HUD element ----

	private static final int LINE = 11, FACE = 8;
	private static final int HEALTH = 0xFFFF5555, DIMMED = 0xFFAAAAAA;

	private record Row(String uuid, String name, String dist, double arrowDeg, String health, boolean far) {
	}

	/** In a party and in a world (or placing it in the HUD editor); off LAN worlds it lists names only. */
	private static boolean hudShown(Minecraft mc) {
		return Social.on("partyHud") && SocialClient.state().inParty() && (HudElements.editorPreview || mc.level != null);
	}

	private static List<Row> rows(Minecraft mc) {
		List<Row> out = new ArrayList<>();
		if (!hudShown(mc) || mc.player == null) {
			return out;
		}
		String dim = mc.level != null ? mc.level.dimension().location().toString() : "";
		float yaw = mc.gameRenderer.getMainCamera().getYRot();
		SocialState s = SocialClient.state();
		String self = s.me() != null ? s.me().uuid() : "";
		List<Mate> mates = positionsHere(mc) ? mates(mc, 1f) : List.of();
		for (Member m : s.party().members()) {
			if (m.uuid().equals(self)) {
				continue;
			}
			Mate mate = null;
			for (Mate x : mates) {
				if (x.uuid().equals(m.uuid())) {
					mate = x;
				}
			}
			if (mate == null) {
				// off a LAN world this is all anyone gets: who's in the party and online
				String where = !m.online() ? "Offline" : positionsHere(mc) ? "Elsewhere" : "Online";
				out.add(new Row(m.uuid(), m.name(), where, Double.NaN, "", !m.online()));
			} else if (!dim.equals(mate.dim())) {
				out.add(new Row(m.uuid(), m.name(), dimName(mate.dim()), Double.NaN, hp(mate.health()), true));
			} else {
				double dx = mate.x() - mc.player.getX(), dz = mate.z() - mc.player.getZ();
				double rel = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
				int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz + Math.pow(mate.y() - mc.player.getY(), 2)));
				out.add(new Row(m.uuid(), m.name(), dist + "m", rel, hp(mate.health()), false));
			}
		}
		return out;
	}

	private static String hp(float health) {
		return health <= 0 ? "" : (int) Math.ceil(health) + "❤";
	}

	private static String dimName(String dim) {
		if (dim == null) {
			return "Elsewhere";
		}
		return switch (dim) {
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "The End";
			case "minecraft:overworld" -> "Overworld";
			default -> {
				String p = dim.substring(dim.indexOf(':') + 1).replace('_', ' ');
				yield p.isEmpty() ? dim : p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1);
			}
		};
	}

	private static final int ARROW_W = 9;

	private static int[] columns(Minecraft mc, List<Row> rows) {
		int name = 0, dist = 0, hp = 0;
		for (Row r : rows) {
			name = Math.max(name, mc.font.width(r.name()));
			dist = Math.max(dist, mc.font.width(r.dist()));
			hp = Math.max(hp, mc.font.width(r.health()));
		}
		return new int[]{name, dist, hp};
	}

	public static int[] hudSize(Minecraft mc) {
		List<Row> rows = rows(mc);
		if (rows.isEmpty()) {
			return new int[]{0, 0};
		}
		int[] c = columns(mc, rows);
		int w = FACE + 3 + c[0] + 8 + ARROW_W + 2 + c[1] + (c[2] > 0 ? 6 + c[2] : 0);
		return new int[]{w, LINE + rows.size() * LINE - 2};
	}

	public static void hudRender(GuiGraphics g, Minecraft mc, int w, int h) {
		List<Row> rows = rows(mc);
		if (rows.isEmpty()) {
			return;
		}
		String mod = Social.MOD;
		boolean bg = Mods.bool(mod, "showBackground"), border = Mods.bool(mod, "border");
		if (bg || border) {
			OriginUi.panel(g, -3, -3, w + 6, h + 6, 5, bg ? OriginColorPicker.liveColor(mod, "bgColor") : 0,
					border ? OriginColorPicker.liveColor(mod, "borderColor") : 0);
		}
		boolean shadow = Mods.bool(mod, "textShadow");
		int text = OriginColorPicker.liveColor(mod, "color");
		int tint = color();
		int[] c = columns(mc, rows);
		g.drawString(mc.font, "Party", 0, 0, tint, shadow);
		int y = LINE;
		for (Row r : rows) {
			int x = 0;
			SocialSkins.face(g, r.uuid(), x, y, FACE);
			x += FACE + 3;
			g.drawString(mc.font, r.name(), x, y, r.far() ? DIMMED : text, shadow);
			x += c[0] + 8;
			if (!Double.isNaN(r.arrowDeg())) {
				arrow(g, x + ARROW_W / 2f, y + 3.5f, (float) r.arrowDeg(), tint);
			}
			x += ARROW_W + 2;
			g.drawString(mc.font, r.dist(), x + c[1] - mc.font.width(r.dist()), y, r.far() ? DIMMED : text, shadow);
			x += c[1];
			if (!r.health().isEmpty()) {
				g.drawString(mc.font, r.health(), w - mc.font.width(r.health()), y, HEALTH, shadow);
			}
			y += LINE;
		}
	}

	/** A small arrow pointing where the mate is (0 = straight ahead, + = to the right). */
	private static void arrow(GuiGraphics g, float cx, float cy, float deg, int color) {
		var pose = g.pose();
		pose.pushPose();
		pose.translate(cx, cy, 0);
		pose.mulPose(Axis.ZP.rotationDegrees(deg));
		// head (rows of 1/3/5/7) over a 1-wide shaft, pointing up before the turn
		g.fill(0, -4, 1, -3, color);
		g.fill(-1, -3, 2, -2, color);
		g.fill(-2, -2, 3, -1, color);
		g.fill(-3, -1, 4, 0, color);
		g.fill(0, 0, 1, 4, color);
		pose.popPose();
	}
}
