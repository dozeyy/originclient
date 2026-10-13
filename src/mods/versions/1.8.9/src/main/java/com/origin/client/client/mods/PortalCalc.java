package com.origin.client.client.mods;

import com.origin.client.client.util.KeyCodes;
import com.origin.client.client.waypoints.Waypoints;
import net.minecraft.block.BlockPortal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.border.WorldBorder;

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
 *    it leads to, using the game's own rules (below), and whether the way back
 *    returns to it.
 *
 * 1.8.9 port -- the rules are 1.8.9's OWN (the pre-1.16 Teleporter), not the
 * 1.16+ PortalForcer ones the other versions use:
 *  - ServerConfigurationManager.transferEntityToWorld: the Overworld leads to the
 *    Nether and back (the End is out of scope); X/Z are scaled by 8 (1/8 into the
 *    Nether), clamped into the world border inset by 16 blocks, then CAST to int
 *    (truncated toward zero, not floored) and clamped to +-29999872. Y is kept.
 *  - Every dimension shares the Overworld's border unscaled (WorldServerMulti
 *    copies it; the client receives that one border), so the client's own border
 *    is the target's too.
 *  - Teleporter.placeInExistingPortal: every column within 128 blocks in X and Z
 *    (in BOTH directions -- 1.8.9 has no 16-block Nether radius), the lowest
 *    portal block of each column, nearest by whole-block squared distance to the
 *    target; ties keep the first found (smaller X, then smaller Z). No border
 *    check on the search.
 *  - Teleporter.makePortal: with no portal found, a new one is built near the
 *    target, its floor between 1 and the dimension's actual height - 5 (the
 *    readout clamps the matching Y to that range; 128 in the Nether, 256 here).
 *
 * Client-side only: it reads blocks the client already has and sends nothing.
 * Ticked from HudHooks (ClientTickEvent END): Forge has no per-feature tick
 * registration like Fabric's ClientTickEvents, so init() is a no-op kept for the
 * 1.16.5 API.
 */
public final class PortalCalc {
	public static final String ID = "portalcalc";

	// Teleporter's search half-width (both directions on 1.8.9).
	private static final int SEARCH_RADIUS = 128;
	// "Standing in or next to a portal": portal blocks this close to your feet.
	private static final int NEAR_H = 2, NEAR_DOWN = 1, NEAR_UP = 2;
	// A portal is at most 21 x 21 portal blocks (BlockPortal.Size's limits).
	private static final int MAX_PORTAL_BLOCKS = 21 * 21;
	// Remembered portals this close (horizontally) are re-checked and forgotten if broken.
	private static final int PRUNE_RANGE = 48;
	// Arriving through a portal puts you inside one: no auto waypoint for that.
	private static final long ARRIVAL_GRACE_MS = 10_000;
	// transferEntityToWorld's absolute clamp and border inset.
	private static final int WORLD_LIMIT = 29999872;
	private static final double BORDER_INSET = 16.0;

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

	/** 1.16.5 hooked its own client tick here. 1.8.9: HudHooks calls {@link #clientTick}. */
	public static void init() {
	}

	/** Once per client tick (HudHooks, ClientTickEvent END). Fail-soft. */
	public static void clientTick(Minecraft mc) {
		try {
			tick(mc);
		} catch (Throwable t) {
			view = null; // the readout just disappears for this tick
		}
	}

	// ---- per tick ---------------------------------------------------------

	private static void tick(Minecraft mc) {
		WorldClient level = mc.theWorld;
		EntityPlayerSP player = mc.thePlayer;
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
		String dim = Waypoints.dimName(player.dimension);
		if (!dim.equals(lastDim)) {
			lastDim = dim;
			dimChangedAt = System.currentTimeMillis();
			nearPortal = null; // it was in the dimension we just left
			PortalMemory.flush();
		}

		boolean down = mc.currentScreen == null && KeyCodes.isDown(Mods.keyCode(ID, "markKey"));
		Side side = Side.of(level, player.dimension);
		if (down && !keyWasDown) {
			markMatchingSpot(mc, player, side, true);
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
			nearPortal = scanNearby(mc, level, player, dim);
		}
		view = buildView(player, side, dim);
	}

	// The portal you are in or next to (refreshed every 4 ticks), and whether
	// you are inside it rather than beside it.
	private static PortalMemory.Portal nearPortal = null;
	private static boolean insidePortal = false;

	// ---- the other dimension ---------------------------------------------

	/** Everything about the target dimension the maths needs, from the current world. */
	private static final class Side {
		final String targetDim;
		final String targetName;
		final double scale;          // current -> target
		final double backScale;      // target -> current
		final int floorMin, floorMax;
		// the shared border, inset 16 (transferEntityToWorld's clamp)
		final double minX, maxX, minZ, maxZ;

		private Side(String targetDim, String targetName, double scale, double backScale, int floorMin, int floorMax,
					 double minX, double maxX, double minZ, double maxZ) {
			this.targetDim = targetDim;
			this.targetName = targetName;
			this.scale = scale;
			this.backScale = backScale;
			this.floorMin = floorMin;
			this.floorMax = floorMax;
			this.minX = minX;
			this.maxX = maxX;
			this.minZ = minZ;
			this.maxZ = maxZ;
		}

		static Side of(WorldClient level, int dimension) {
			boolean inNether = dimension == -1;
			if (!inNether && dimension != 0) {
				return null; // the End (and modded dimensions): out of scope
			}
			// Teleporter.makePortal searches floors up to the target's actual height;
			// a frame needs 4 blocks above its floor. The Nether's is 128, the Overworld's 256.
			int actualHeight = inNether ? 256 : 128;
			int floorMin = 1;
			int floorMax = Math.max(floorMin, actualHeight - 1 - 4);
			WorldBorder b = level.getWorldBorder();   // 1.8.9 NAME UNVERIFIED: WorldBorder.minX/maxX/minZ/maxZ
			return new Side(Waypoints.dimName(inNether ? 0 : -1), inNether ? "Overworld" : "Nether",
					inNether ? 8.0 : 1.0 / 8.0, inNether ? 1.0 / 8.0 : 8.0, floorMin, floorMax,
					b.minX() + BORDER_INSET, b.maxX() - BORDER_INSET, b.minZ() + BORDER_INSET, b.maxZ() - BORDER_INSET);
		}

		/** The game's target column for an entity at (x, z) here (see the class comment). */
		int targetX(double x) {
			return transfer(x * scale, minX, maxX);
		}

		int targetZ(double z) {
			return transfer(z * scale, minZ, maxZ);
		}

		/** The same, from the other dimension back into this one. */
		int backX(double x) {
			return transfer(x * backScale, minX, maxX);
		}

		int backZ(double z) {
			return transfer(z * backScale, minZ, maxZ);
		}

		/** Matching spot as doubles (Decimal Coordinates): scaled, border-clamped. */
		double matchX(double x) {
			return clampD(x * scale, minX, maxX);
		}

		double matchZ(double z) {
			return clampD(z * scale, minZ, maxZ);
		}
	}

	/** transferEntityToWorld: clamp into the inset border, truncate to int, clamp to the world limit. */
	private static int transfer(double v, double min, double max) {
		int i = (int) clampD(v, min, max);
		return MathHelper.clamp_int(i, -WORLD_LIMIT, WORLD_LIMIT);
	}

	private static double clampD(double v, double min, double max) {
		// a border smaller than the inset (min > max) leaves vanilla's clamp at max
		return v < min ? min : (v > max ? max : v);
	}

	// ---- the view ----------------------------------------------------------

	private static View buildView(EntityPlayerSP player, Side side, String dim) {
		double mx = side.matchX(player.posX);
		double mz = side.matchZ(player.posZ);
		double my = Math.max(side.floorMin, Math.min(side.floorMax, player.posY));

		int link = LINK_NONE, lx = 0, ly = 0, lz = 0, px = 0, py = 0, pz = 0;
		PortalMemory.Portal here = nearPortal;
		if (here != null && Mods.bool(ID, "showLinkCheck")) {
			// Inside the portal: your own position is what the game uses. Beside it:
			// the portal block nearest you, which is where you'd step in.
			double sx, sy, sz;
			if (insidePortal) {
				sx = player.posX;
				sy = player.posY;
				sz = player.posZ;
			} else {
				BlockPos f = new BlockPos(player);
				sx = MathHelper.clamp_int(f.getX(), here.minX, here.maxX) + 0.5;
				sy = MathHelper.clamp_int(f.getY(), here.minY, here.maxY);
				sz = MathHelper.clamp_int(f.getZ(), here.minZ, here.maxZ) + 0.5;
			}
			int tx = side.targetX(sx), ty = MathHelper.floor_double(sy), tz = side.targetZ(sz);
			List<PortalMemory.Portal> known = worldKey != null ? PortalMemory.forWorld(worldKey) : java.util.Collections.<PortalMemory.Portal>emptyList();
			px = tx;
			py = Math.max(side.floorMin, Math.min(side.floorMax, ty));
			pz = tz;
			PortalMemory.Portal dest = closest(known, null, side.targetDim, tx, ty, tz);
			if (dest == null) {
				link = LINK_NEW;
			} else {
				lx = dest.labelX();
				ly = dest.minY;
				lz = dest.labelZ();
				// The way back: from the middle of that portal into this dimension.
				int bx = side.backX(lx + 0.5), bz = side.backZ(lz + 0.5);
				PortalMemory.Portal back = closest(known, here, dim, bx, dest.minY, bz);
				link = back != null && back.overlaps(here, 1) ? LINK_OK : LINK_OTHER;
			}
		}
		if (!Mods.bool(ID, "showHud") && link == LINK_NONE) {
			return null;
		}
		return new View(Mods.bool(ID, "showHud") ? side.targetName : null, mx, my, mz, link, lx, ly, lz, px, py, pz);
	}

	/**
	 * Teleporter.placeInExistingPortal over remembered portals: each portal's
	 * nearest bottom-row block (1.8.9 only counts the lowest portal block of each
	 * column) inside the 128-block square, nearest by squared block distance; ties
	 * to the smaller X, then Z (the search's own order). `extra` is the portal
	 * you're at (counted even when Remember Portals is off).
	 */
	private static PortalMemory.Portal closest(List<PortalMemory.Portal> known, PortalMemory.Portal extra,
											   String dim, int tx, int ty, int tz) {
		PortalMemory.Portal best = null;
		long bestD = Long.MAX_VALUE;
		int bestX = Integer.MAX_VALUE, bestZ = Integer.MAX_VALUE;
		int n = known.size();
		for (int i = 0; i <= n; i++) {
			PortalMemory.Portal p = i < n ? known.get(i) : extra;
			if (p == null || !dim.equals(p.dimension)) {
				continue;
			}
			int qx = MathHelper.clamp_int(tx, p.minX, p.maxX);
			int qy = p.minY;
			int qz = MathHelper.clamp_int(tz, p.minZ, p.maxZ);
			if (Math.abs(qx - tx) > SEARCH_RADIUS || Math.abs(qz - tz) > SEARCH_RADIUS) {
				continue;
			}
			long dx = qx - tx, dy = qy - ty, dz = qz - tz;
			long d = dx * dx + dy * dy + dz * dz;
			if (d < bestD || d == bestD && (qx < bestX || qx == bestX && qz < bestZ)) {
				best = p;
				bestD = d;
				bestX = qx;
				bestZ = qz;
			}
		}
		return best;
	}

	// ---- portal detection + memory ----------------------------------------

	/** Finds the portal you're in or next to, remembers it, and fires Auto Waypoint. */
	private static PortalMemory.Portal scanNearby(Minecraft mc, WorldClient level, EntityPlayerSP player, String dim) {
		BlockPos feet = new BlockPos(player);
		insidePortal = isPortal(level.getBlockState(feet)) || isPortal(level.getBlockState(feet.up()));
		BlockPos start = null;
		int bestD = Integer.MAX_VALUE;
		for (int dx = -NEAR_H; dx <= NEAR_H; dx++) {
			for (int dy = -NEAR_DOWN; dy <= NEAR_UP; dy++) {
				for (int dz = -NEAR_H; dz <= NEAR_H; dz++) {
					BlockPos m = feet.add(dx, dy, dz);
					if (isPortal(level.getBlockState(m))) {
						int d = dx * dx + dy * dy + dz * dz;
						if (d < bestD) {
							bestD = d;
							start = m;
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
				markMatchingSpot(mc, player, Side.of(level, player.dimension), false);
			}
		}
		return stored;
	}

	private static boolean isPortal(IBlockState s) {
		return s != null && s.getBlock() == Blocks.portal;
	}

	private static EnumFacing.Axis axisOf(IBlockState s) {
		try {
			return s.getValue(BlockPortal.AXIS);
		} catch (Throwable t) {
			return EnumFacing.Axis.X;
		}
	}

	/** The box of portal blocks connected to `start` (same axis), capped at a full-size portal. */
	private static PortalMemory.Portal measure(WorldClient level, BlockPos start, String dim) {
		EnumFacing.Axis axis = axisOf(level.getBlockState(start));
		PortalMemory.Portal p = new PortalMemory.Portal();
		p.dimension = dim;
		p.axis = axis == EnumFacing.Axis.Z ? "z" : "x";
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
			BlockPos[] next = axis == EnumFacing.Axis.Z
					? new BlockPos[]{c.north(), c.south(), c.up(), c.down()}
					: new BlockPos[]{c.east(), c.west(), c.up(), c.down()};
			for (BlockPos n : next) {
				if (!seen.contains(n)) {
					IBlockState s = level.getBlockState(n);
					if (isPortal(s) && axisOf(s) == axis) {
						seen.add(n);
						queue.add(n);
					}
				}
			}
		}
		return p;
	}

	/** Forgets remembered portals near you that are loaded but have no portal blocks left. */
	private static void pruneBroken(WorldClient level, EntityPlayerSP player, String dim) {
		if (worldKey == null) {
			return;
		}
		List<PortalMemory.Portal> list = PortalMemory.forWorld(worldKey);
		BlockPos feet = new BlockPos(player);
		for (PortalMemory.Portal p : new java.util.ArrayList<>(list)) {
			if (!dim.equals(p.dimension)
					|| Math.abs(p.labelX() - feet.getX()) > PRUNE_RANGE || Math.abs(p.labelZ() - feet.getZ()) > PRUNE_RANGE
					|| p.minY < 0 || p.maxY >= 256) {
				continue;
			}
			// Only judge what the client really has: an unloaded chunk reads as air.
			// 1.8.9: isBlockLoaded(pos, false) = the chunk exists and isn't the empty stand-in.
			if (!level.isBlockLoaded(new BlockPos(p.minX, p.minY, p.minZ), false)
					|| !level.isBlockLoaded(new BlockPos(p.maxX, p.maxY, p.maxZ), false)) {
				continue;
			}
			boolean any = false;
			for (int x = p.minX; x <= p.maxX && !any; x++) {
				for (int y = p.minY; y <= p.maxY && !any; y++) {
					for (int z = p.minZ; z <= p.maxZ && !any; z++) {
						any = isPortal(level.getBlockState(new BlockPos(x, y, z)));
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

	/** The action-bar message (1.8.9's displayClientMessage(text, true)). */
	private static void actionBar(Minecraft mc, String msg) {
		if (mc.ingameGUI != null) {
			mc.ingameGUI.setRecordPlaying(new ChatComponentText(msg), false);
		}
	}

	/**
	 * Drops a "Portal link" waypoint at the matching spot in the other dimension.
	 * `fromKey` = the player pressed the key (they get feedback either way);
	 * Auto Waypoint calls it quietly apart from the confirmation.
	 */
	private static void markMatchingSpot(Minecraft mc, EntityPlayerSP player, Side side, boolean fromKey) {
		if (side == null) {
			if (fromKey) {
				actionBar(mc, "Portal Calculator works in the Overworld and the Nether");
			}
			return;
		}
		int x = MathHelper.floor_double(side.matchX(player.posX));
		int z = MathHelper.floor_double(side.matchZ(player.posZ));
		int y = MathHelper.clamp_int(MathHelper.floor_double(player.posY), side.floorMin, side.floorMax);
		String where = side.targetName + " at " + x + " " + y + " " + z;
		for (Waypoints.Waypoint w : Waypoints.all()) {
			if (w.name.equals(WAYPOINT_NAME) && w.dimension.equals(side.targetDim) && w.x == x && w.y == y && w.z == z) {
				if (fromKey) {
					actionBar(mc, "Already marked: " + where);
				}
				return;
			}
		}
		Waypoints.create(WAYPOINT_NAME, x, y, z, side.targetDim, Mods.color(ID, "accentColor") | 0xFF000000);
		String msg = "Portal link marked in the " + where;
		if (!Mods.on("waypoints")) {
			msg += " - turn on Waypoints to see it";
		}
		actionBar(mc, msg);
	}

	private static final String WAYPOINT_NAME = "Portal link";

	// ---- which world --------------------------------------------------------

	/** "sp:<save folder>" for singleplayer, "mp:<address>" online. */
	private static String worldKey(Minecraft mc) {
		try {
			IntegratedServer sp = mc.getIntegratedServer();
			if (sp != null) {
				// 1.8.9: the save's folder name (MinecraftServer.getFolderName) -- the same
				// name the other versions take from the world path's last segment.
				return "sp:" + sp.getFolderName();   // 1.8.9 NAME UNVERIFIED
			}
			ServerData sd = mc.getCurrentServerData();
			if (sd != null && sd.serverIP != null) {
				return "mp:" + sd.serverIP.toLowerCase(Locale.ROOT);
			}
		} catch (Throwable ignored) {
			// fall through to the shared bucket
		}
		return "other";
	}
}
