package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.waypoints.Waypoints;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Portal Calculator (Origin original, 2026-10-12): "where do I build the
 * matching portal?" and "why did my portal link to the wrong one?".
 *
 *  - Readout: the spot in the other dimension that matches where you stand.
 *  - Mark key: drops a "Portal link" waypoint at that spot (the waypoints system
 *    draws its beam when you get there).
 *  - Memory + link check: every nether portal you stand in or next to is
 *    remembered (PortalMemory); at a portal, the HUD predicts which known portal
 *    it leads to, using vanilla 1.21.1's own rules (below), and whether the way
 *    back returns to it.
 *
 * Vanilla's rules, read from the decompiled 1.21.1 source:
 *  - NetherPortalBlock.getPortalDestination: Overworld and every other dimension
 *    lead to the Nether, the Nether leads to the Overworld. The target spot is
 *    the TARGET world border's clampToBounds(entity X * scale, entity Y, entity
 *    Z * scale), scale = DimensionType.getTeleportationScale (1/8 into the
 *    Nether, 8 out of it). Y is NOT changed for the search.
 *  - PortalForcer.findClosestPortalPosition: every nether-portal BLOCK (POI)
 *    whose |dx| and |dz| to the target are both <= 16 when the target is the
 *    Nether, <= 128 when it is the Overworld (any Y), inside the target border;
 *    the winner is the smallest BlockPos.distSqr (whole-block X/Y/Z), ties to
 *    the lowest Y.
 *  - PortalForcer.createPortal: with no portal found, a new one is built at the
 *    closest free spot within 16 blocks whose floor Y is from minY + 1 to the
 *    logical top - 4 (the fallback clamps to 70 .. logical top - 9). The readout
 *    shows the matching Y clamped to that buildable range.
 *  - The client's world border: the server always sends the OVERWORLD border
 *    (PlayerList.sendLevelInfo) and Level divides its centre by the dimension's
 *    coordinateScale, so each client level's border is exactly the server's for
 *    that dimension. The other dimension's border is the same size with the
 *    centre scaled by curScale / targetScale.
 *
 * Client-side only: it reads blocks the client already has and sends nothing.
 */
public final class PortalCalc {
	public static final String ID = "portalcalc";

	// PortalForcer's search half-widths (NETHER_PORTAL_RADIUS / OVERWORLD_PORTAL_RADIUS).
	private static final int NETHER_RADIUS = 16;
	private static final int OVERWORLD_RADIUS = 128;
	// "Standing in or next to a portal": portal blocks this close to your feet.
	private static final int NEAR_H = 2, NEAR_DOWN = 1, NEAR_UP = 2;
	// A portal is at most 21 x 21 portal blocks (PortalShape.MAX_WIDTH / MAX_HEIGHT).
	private static final int MAX_PORTAL_BLOCKS = 21 * 21;
	// Remembered portals this close (horizontally) are re-checked and forgotten if broken.
	private static final int PRUNE_RANGE = 48;
	// Arriving through a portal puts you inside one: no auto waypoint for that.
	private static final long ARRIVAL_GRACE_MS = 10_000;

	public static final int LINK_NONE = 0, LINK_OK = 1, LINK_OTHER = 2, LINK_NEW = 3;

	/**
	 * What the HUD shows, rebuilt every client tick (the HUD only reads it).
	 * Coordinates of the matching spot stay doubles so Decimal Coordinates can
	 * show them; the link lines use whole blocks.
	 */
	public static final class View {
		public final String targetName;       // "Nether" / "Overworld"
		public final double x, y, z;          // matching spot (Y clamped to the buildable range)
		public final int link;                // LINK_*
		public final int lx, ly, lz;          // the portal it links to (OK / OTHER)
		public final int px, py, pz;          // the portal's own matching spot (OTHER / NEW)

		View(String targetName, double x, double y, double z, int link, int lx, int ly, int lz, int px, int py, int pz) {
			this.targetName = targetName;
			this.x = x;
			this.y = y;
			this.z = z;
			this.link = link;
			this.lx = lx;
			this.ly = ly;
			this.lz = lz;
			this.px = px;
			this.py = py;
			this.pz = pz;
		}
	}

	/** What the HUD editor shows before you're at a portal. */
	public static View sampleView() {
		return new View("Nether", 125, 64, -40, LINK_OK, 124, 64, -41, 125, 64, -40);
	}

	private static volatile View view = null;

	private static int ticks = 0;
	private static boolean inWorld = false;
	private static String lastDim = null;
	private static long dimChangedAt = 0;
	private static boolean keyWasDown = false;
	private static String worldKey = null;

	private PortalCalc() {
	}

	/** The HUD's current content; null = draw nothing (off, End, no world). */
	public static View view() {
		return view;
	}

	/** Hooks its own client tick (called once from OriginClientMod; fail-soft). */
	public static void init() {
		try {
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				try {
					tick(mc);
				} catch (Throwable t) {
					view = null; // the readout just disappears for this tick
				}
			});
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: Portal Calculator could not start; it stays off", t);
		}
	}

	// ---- per tick ---------------------------------------------------------

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			if (inWorld) {
				inWorld = false;
				PortalMemory.flush();
			}
			view = null;
			lastDim = null;
			worldKey = null;
			keyWasDown = false;
			nearPortal = null;
			return;
		}
		if (!inWorld) {
			inWorld = true;
			worldKey = worldKey(mc);
		}
		if (!Mods.on(ID)) {
			view = null;
			keyWasDown = false;
			nearPortal = null;
			return;
		}
		String dim = level.dimension().location().toString();
		if (!dim.equals(lastDim)) {
			lastDim = dim;
			dimChangedAt = System.currentTimeMillis();
			nearPortal = null; // it was in the dimension we just left
			PortalMemory.flush();
		}

		boolean down = mc.screen == null && OriginClientMod.isRawKeyDown(Mods.keyCode(ID, "markKey"));
		Side side = Side.of(level);
		if (down && !keyWasDown) {
			markMatchingSpot(player, side, true);
		}
		keyWasDown = down;

		if (side == null) {
			view = null; // the End (or a modded dimension): nothing to calculate
			return;
		}
		ticks++;
		if (ticks % 20 == 0 && Mods.bool(ID, "rememberPortals")) {
			pruneBroken(level, player, dim);
		}
		if (ticks % 4 == 0) {
			nearPortal = scanNearby(level, player, dim);
		}
		view = buildView(player, side, dim);
	}

	// The portal you are in or next to (refreshed every 4 ticks), and whether
	// you are inside it rather than beside it.
	private static PortalMemory.Portal nearPortal = null;
	private static boolean insidePortal = false;

	// ---- the other dimension ---------------------------------------------

	/** Everything about the target dimension the maths needs, from the current level. */
	private record Side(String targetDim, String targetName, double scale, int floorMin, int floorMax,
						double minX, double maxX, double minZ, double maxZ, int radius,
						double backScale, double curMinX, double curMaxX, double curMinZ, double curMaxZ, int backRadius) {

		static Side of(ClientLevel level) {
			boolean inNether = level.dimension() == Level.NETHER;
			if (!inNether && level.dimension() != Level.OVERWORLD) {
				return null; // vanilla sends the End (and modded worlds) to the Nether; out of scope
			}
			DimensionType cur = level.dimensionType();
			DimensionType tgt = dimType(level, inNether ? BuiltinDimensionTypes.OVERWORLD : BuiltinDimensionTypes.NETHER);
			double curScale = cur.coordinateScale();
			double tgtScale = tgt != null ? tgt.coordinateScale() : (inNether ? 1.0 : 8.0);
			int tMinY = tgt != null ? tgt.minY() : (inNether ? -64 : 0);
			int tHeight = tgt != null ? tgt.height() : (inNether ? 384 : 256);
			int tLogical = tgt != null ? tgt.logicalHeight() : (inNether ? 384 : 128);
			// PortalForcer.createPortal: top = min(maxBuildHeight, minBuildHeight + logicalHeight) - 1,
			// and a frame needs its floor at <= top - 4 and its bottom obsidian at >= minY.
			int top = Math.min(tMinY + tHeight, tMinY + tLogical) - 1;
			int floorMin = tMinY + 1;
			int floorMax = Math.max(floorMin, top - 4);

			WorldBorder b = level.getWorldBorder();
			double size = b.getSize();
			double abs = b.getAbsoluteMaxSize();
			double tcx = b.getCenterX() * curScale / tgtScale;
			double tcz = b.getCenterZ() * curScale / tgtScale;
			return new Side(
					(inNether ? Level.OVERWORLD : Level.NETHER).location().toString(),
					inNether ? "Overworld" : "Nether",
					curScale / tgtScale, floorMin, floorMax,
					Mth.clamp(tcx - size / 2.0, -abs, abs), Mth.clamp(tcx + size / 2.0, -abs, abs),
					Mth.clamp(tcz - size / 2.0, -abs, abs), Mth.clamp(tcz + size / 2.0, -abs, abs),
					inNether ? OVERWORLD_RADIUS : NETHER_RADIUS,
					tgtScale / curScale, b.getMinX(), b.getMaxX(), b.getMinZ(), b.getMaxZ(),
					inNether ? NETHER_RADIUS : OVERWORLD_RADIUS);
		}

		/** Vanilla's target spot for an entity at (x, y, z) here (WorldBorder.clampToBounds). */
		BlockPos target(double x, double y, double z) {
			return BlockPos.containing(Mth.clamp(x * scale, minX, maxX - 1.0), y, Mth.clamp(z * scale, minZ, maxZ - 1.0));
		}

		/** The same, from the other dimension back into this one. */
		BlockPos back(double x, double y, double z) {
			return BlockPos.containing(Mth.clamp(x * backScale, curMinX, curMaxX - 1.0), y,
					Mth.clamp(z * backScale, curMinZ, curMaxZ - 1.0));
		}

		boolean inTargetBorder(int x, int z) {
			return x >= minX && x < maxX && z >= minZ && z < maxZ;
		}

		boolean inCurrentBorder(int x, int z) {
			return x >= curMinX && x < curMaxX && z >= curMinZ && z < curMaxZ;
		}
	}

	private static DimensionType dimType(ClientLevel level, ResourceKey<DimensionType> key) {
		try {
			// dimension_type is a synced registry, so a datapack's heights reach the client.
			// 1.21.2+: registryOrThrow -> lookupOrThrow, Registry.get(key) -> getValue(key).
			return level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getValue(key);
		} catch (Throwable t) {
			return null; // vanilla heights are used instead
		}
	}

	// ---- the view ----------------------------------------------------------

	private static View buildView(LocalPlayer player, Side side, String dim) {
		BlockPos t = side.target(player.getX(), player.getY(), player.getZ());
		double mx = Mth.clamp(player.getX() * side.scale(), side.minX(), side.maxX() - 1.0);
		double mz = Mth.clamp(player.getZ() * side.scale(), side.minZ(), side.maxZ() - 1.0);
		double my = Mth.clamp(player.getY(), side.floorMin(), side.floorMax());

		int link = LINK_NONE, lx = 0, ly = 0, lz = 0, px = 0, py = 0, pz = 0;
		PortalMemory.Portal here = nearPortal;
		if (here != null && Mods.bool(ID, "showLinkCheck")) {
			// Inside the portal: your own position is what vanilla uses. Beside it:
			// the portal block nearest you, which is where you'd step in.
			double sx, sy, sz;
			if (insidePortal) {
				sx = player.getX();
				sy = player.getY();
				sz = player.getZ();
			} else {
				BlockPos f = player.blockPosition();
				sx = Mth.clamp(f.getX(), here.minX, here.maxX) + 0.5;
				sy = Mth.clamp(f.getY(), here.minY, here.maxY);
				sz = Mth.clamp(f.getZ(), here.minZ, here.maxZ) + 0.5;
			}
			BlockPos pt = side.target(sx, sy, sz);
			List<PortalMemory.Portal> known = worldKey != null ? PortalMemory.forWorld(worldKey) : List.of();
			px = pt.getX();
			py = Mth.clamp(pt.getY(), side.floorMin(), side.floorMax());
			pz = pt.getZ();
			PortalMemory.Portal dest = closest(known, null, side.targetDim(), pt, side.radius(), side, true);
			if (dest == null) {
				link = LINK_NEW;
			} else {
				lx = dest.labelX();
				ly = dest.minY;
				lz = dest.labelZ();
				// The way back: from the middle of that portal into this dimension.
				BlockPos bt = side.back(lx + 0.5, dest.minY, lz + 0.5);
				PortalMemory.Portal back = closest(known, here, dim, bt, side.backRadius(), side, false);
				link = back != null && back.overlaps(here, 1) ? LINK_OK : LINK_OTHER;
			}
		}
		if (!Mods.bool(ID, "showHud") && link == LINK_NONE) {
			return null;
		}
		return new View(Mods.bool(ID, "showHud") ? side.targetName() : null, mx, my, mz, link, lx, ly, lz, px, py, pz);
	}

	/**
	 * PortalForcer.findClosestPortalPosition over remembered portals: the nearest
	 * portal block inside the search square and the border, ties to the lowest Y.
	 * The nearest block of a box is the target clamped into it, which also
	 * minimises |dx| and |dz|, so the square test on it is exact. `extra` is the
	 * portal you're at (counted even when Remember Portals is off).
	 */
	private static PortalMemory.Portal closest(List<PortalMemory.Portal> known, PortalMemory.Portal extra,
											   String dim, BlockPos t, int radius, Side side, boolean targetSide) {
		PortalMemory.Portal best = null;
		long bestD = Long.MAX_VALUE;
		int bestY = Integer.MAX_VALUE;
		int n = known.size();
		for (int i = 0; i <= n; i++) {
			PortalMemory.Portal p = i < n ? known.get(i) : extra;
			if (p == null || !dim.equals(p.dimension)) {
				continue;
			}
			int qx = Mth.clamp(t.getX(), p.minX, p.maxX);
			int qy = Mth.clamp(t.getY(), p.minY, p.maxY);
			int qz = Mth.clamp(t.getZ(), p.minZ, p.maxZ);
			if (Math.abs(qx - t.getX()) > radius || Math.abs(qz - t.getZ()) > radius) {
				continue;
			}
			if (targetSide ? !side.inTargetBorder(qx, qz) : !side.inCurrentBorder(qx, qz)) {
				continue;
			}
			long dx = qx - t.getX(), dy = qy - t.getY(), dz = qz - t.getZ();
			long d = dx * dx + dy * dy + dz * dz;
			if (d < bestD || d == bestD && qy < bestY) {
				best = p;
				bestD = d;
				bestY = qy;
			}
		}
		return best;
	}

	// ---- portal detection + memory ----------------------------------------

	/** Finds the portal you're in or next to, remembers it, and fires Auto Waypoint. */
	private static PortalMemory.Portal scanNearby(ClientLevel level, LocalPlayer player, String dim) {
		BlockPos feet = player.blockPosition();
		insidePortal = isPortal(level.getBlockState(feet)) || isPortal(level.getBlockState(feet.above()));
		BlockPos start = null;
		int bestD = Integer.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -NEAR_H; dx <= NEAR_H; dx++) {
			for (int dy = -NEAR_DOWN; dy <= NEAR_UP; dy++) {
				for (int dz = -NEAR_H; dz <= NEAR_H; dz++) {
					m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					if (isPortal(level.getBlockState(m))) {
						int d = dx * dx + dy * dy + dz * dz;
						if (d < bestD) {
							bestD = d;
							start = m.immutable();
						}
					}
				}
			}
		}
		if (start == null) {
			insidePortal = false;
			return null;
		}
		PortalMemory.Portal p = measure(level, start, dim);
		if (!Mods.bool(ID, "rememberPortals") || worldKey == null) {
			return p;
		}
		PortalMemory.Portal stored = PortalMemory.remember(worldKey, p);
		if (insidePortal && !stored.entered) {
			stored.entered = true;
			PortalMemory.touch();
			boolean arriving = System.currentTimeMillis() - dimChangedAt < ARRIVAL_GRACE_MS;
			if (!arriving && Mods.bool(ID, "autoWaypoint")) {
				markMatchingSpot(player, Side.of(level), false);
			}
		}
		return stored;
	}

	private static boolean isPortal(BlockState s) {
		return s.is(Blocks.NETHER_PORTAL);
	}

	/** The box of portal blocks connected to `start` (same axis), capped at a full-size portal. */
	private static PortalMemory.Portal measure(ClientLevel level, BlockPos start, String dim) {
		BlockState first = level.getBlockState(start);
		Direction.Axis axis = first.getValue(NetherPortalBlock.AXIS);
		PortalMemory.Portal p = new PortalMemory.Portal();
		p.dimension = dim;
		p.axis = axis == Direction.Axis.Z ? "z" : "x";
		p.minX = p.maxX = start.getX();
		p.minY = p.maxY = start.getY();
		p.minZ = p.maxZ = start.getZ();
		p.seen = System.currentTimeMillis();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty() && seen.size() <= MAX_PORTAL_BLOCKS) {
			BlockPos c = queue.poll();
			p.minX = Math.min(p.minX, c.getX());
			p.maxX = Math.max(p.maxX, c.getX());
			p.minY = Math.min(p.minY, c.getY());
			p.maxY = Math.max(p.maxY, c.getY());
			p.minZ = Math.min(p.minZ, c.getZ());
			p.maxZ = Math.max(p.maxZ, c.getZ());
			// A portal is flat: it grows along its axis and up/down only.
			BlockPos[] next = axis == Direction.Axis.Z
					? new BlockPos[]{c.north(), c.south(), c.above(), c.below()}
					: new BlockPos[]{c.east(), c.west(), c.above(), c.below()};
			for (BlockPos n : next) {
				if (!seen.contains(n)) {
					BlockState s = level.getBlockState(n);
					if (isPortal(s) && s.getValue(NetherPortalBlock.AXIS) == axis) {
						seen.add(n);
						queue.add(n);
					}
				}
			}
		}
		return p;
	}

	/** Forgets remembered portals near you that are loaded but have no portal blocks left. */
	private static void pruneBroken(ClientLevel level, LocalPlayer player, String dim) {
		if (worldKey == null) {
			return;
		}
		List<PortalMemory.Portal> list = PortalMemory.forWorld(worldKey);
		BlockPos feet = player.blockPosition();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (PortalMemory.Portal p : List.copyOf(list)) {
			if (!dim.equals(p.dimension)
					|| Math.abs(p.labelX() - feet.getX()) > PRUNE_RANGE || Math.abs(p.labelZ() - feet.getZ()) > PRUNE_RANGE
					|| p.minY < level.getMinY() || p.maxY > level.getMaxY()) {   // 1.21.2+: getMaxY() is the inclusive top
				continue;
			}
			// Only judge what the client really has: an unloaded chunk reads as air.
			if (!level.getChunkSource().hasChunk(p.minX >> 4, p.minZ >> 4)
					|| !level.getChunkSource().hasChunk(p.maxX >> 4, p.maxZ >> 4)) {
				continue;
			}
			boolean any = false;
			for (int x = p.minX; x <= p.maxX && !any; x++) {
				for (int y = p.minY; y <= p.maxY && !any; y++) {
					for (int z = p.minZ; z <= p.maxZ && !any; z++) {
						any = isPortal(level.getBlockState(m.set(x, y, z)));
					}
				}
			}
			if (!any) {
				PortalMemory.forget(worldKey, p);
				if (p == nearPortal) {
					nearPortal = null;
				}
			}
		}
	}

	// ---- Mark matching spot -------------------------------------------------

	/**
	 * Drops a "Portal link" waypoint at the matching spot in the other dimension.
	 * `fromKey` = the player pressed the key (they get feedback either way);
	 * Auto Waypoint calls it quietly apart from the confirmation.
	 */
	private static void markMatchingSpot(LocalPlayer player, Side side, boolean fromKey) {
		if (side == null) {
			if (fromKey) {
				player.displayClientMessage(Component.literal("Portal Calculator works in the Overworld and the Nether"), true);
			}
			return;
		}
		int x = Mth.floor(Mth.clamp(player.getX() * side.scale(), side.minX(), side.maxX() - 1.0));
		int z = Mth.floor(Mth.clamp(player.getZ() * side.scale(), side.minZ(), side.maxZ() - 1.0));
		int y = Mth.clamp(Mth.floor(player.getY()), side.floorMin(), side.floorMax());
		String where = side.targetName() + " at " + x + " " + y + " " + z;
		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (w.name.equals(WAYPOINT_NAME) && w.dimension.equals(side.targetDim()) && w.x == x && w.y == y && w.z == z) {
				if (fromKey) {
					player.displayClientMessage(Component.literal("Already marked: " + where), true);
				}
				return;
			}
		}
		Waypoints.create(WAYPOINT_NAME, x, y, z, side.targetDim(), Mods.color(ID, "accentColor") | 0xFF000000);
		String msg = "Portal link marked in the " + where;
		if (!Mods.on("waypoints")) {
			msg += " - turn on Waypoints to see it";
		}
		player.displayClientMessage(Component.literal(msg), true);
	}

	private static final String WAYPOINT_NAME = "Portal link";

	// ---- which world --------------------------------------------------------

	/** "sp:<save folder>" for singleplayer, "mp:<address>" / "realm:<address>" online. */
	private static String worldKey(Minecraft mc) {
		try {
			IntegratedServer sp = mc.getSingleplayerServer();
			if (sp != null) {
				Path dir = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
				return "sp:" + dir.getFileName();
			}
			ServerData sd = mc.getCurrentServer();
			if (sd != null && sd.ip != null) {
				return (sd.isRealm() ? "realm:" : "mp:") + sd.ip.toLowerCase(Locale.ROOT);
			}
		} catch (Throwable ignored) {
			// fall through to the shared bucket
		}
		return "other";
	}
}
