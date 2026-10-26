package com.origin.client.client.worldedit;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.StepAside;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// WorldEdit CUI (Lunar parity, batch 4): shows the player's WorldEdit selection
// in the world. This class is the PROTOCOL half -- the handshake, the plugin
// channel, and turning the server's text events into CuiRegion data. Drawing
// lives in CuiRenderer.
//
// The protocol (reference client: github.com/EngineHub/WorldEditCUI, tag
// 1.21.1+01, and its shared payload github.com/EngineHub/worldeditcui-protocol,
// commit "Update for 1.21.1"):
//   * channel `worldedit:cui`; each payload is ONE raw UTF-8 string, no length
//     prefix: `[+]<event>|<arg>|<arg>...` -- a leading `+` marks a "multi"
//     region (WorldGuard/CraftBook region previews) instead of the player's own
//     selection.
//   * the client says hello with `v|4` (protocol version 4). WorldEdit sends
//     nothing until it has seen that, then immediately re-sends the current
//     selection. Like WorldEditCUI we send it on join/world change and again 10
//     ticks later (a Bukkit server can register its channel a moment after join).
//   * events: s (new selection: cuboid / polygon2d / ellipsoid / cylinder /
//     polyhedron / clear), p (3D point / polyhedron vertex), p2 (2D polygon
//     corner), mm (polygon/cylinder Y range), e (ellipsoid centre=0 / radii=1),
//     cyl (cylinder centre + radii), poly (polyhedron face), col (multi palette),
//     grid (multi grid spacing [+ "cull"]), u (update; no-op).
//
// The only packet this ever sends is that `v|4` hello, and only while the mod is
// on -- it is the whole job of the mod. Servers without WorldEdit discard an
// unknown plugin-channel payload silently (vanilla does exactly that).
//
// Coexistence (never broken):
//   * the real WorldEditCUI mod installed -> we step aside (StepAside "worldeditcui"
//     row): dormant, the card says "Replaced by WorldEditCUI"; it owns the channel
//     and the drawing, and two overlays on one selection would just fight. Only a
//     player who forces Origin's back on gets both.
//   * anything else already owns the channel's client receiver -> log once and
//     stay dormant. (WorldEdit's shared CUI protocol library, the 1.21.1 build's
//     other branch, only exists from the 1.20.5 payload era; WorldEdit for 1.20.x
//     registers `worldedit:cui` on the SERVER side only, so it never collides.)
//
// ERA (pre-1.20.5, source-verified against Fabric API 0.83.0+1.20 /
// 0.91.6+1.20.2 / 0.97.3+1.20.4): no CustomPacketPayload/StreamCodec/
// PayloadTypeRegistry. Plugin channels are raw FriendlyByteBuf bodies on an
// Identifier: ClientPlayNetworking.registerGlobalReceiver(channel,
// (client, handler, buf, sender) -> ...) and send(channel, buf). The wire bytes
// are identical (one UTF-8 string, no length prefix).
public final class WorldEditCui {
	public static final String ID = "worldeditcui";

	private static final String PROTOCOL_VERSION = "4";
	// Bukkit can announce its plugin channels a few ticks after join; WorldEditCUI
	// re-sends the hello this many ticks later for that reason.
	private static final int DELAYED_HELLO_TICKS = 10;

	// Selection state. Touched only on the client thread (packet handlers are
	// hopped onto it), so no locking.
	static CuiRegion selection;
	static final Map<UUID, CuiRegion> multi = new LinkedHashMap<>();
	private static CuiRegion activeMulti;

	private static Sender sender;
	private static boolean wasOn;
	private static ClientLevel lastLevel;
	private static LocalPlayer lastPlayer;
	private static int delayedHello;

	private WorldEditCui() {
	}

	/** Called once from OriginClientMod.onInitializeClient. Never throws. */
	public static void init() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			// The player's own WorldEditCUI replaces Origin's (StepAside, resolved before this
			// runs). Unless they turned Origin's back on, its channel is never opened at all.
			// Forced on next to the real one: the channel registration below may find the id
			// taken and stay dormant (caught), never a crash. Takes effect from the next start.
			if (StepAside.aside(ID, Mods.forcedOn(ID))) {
				OriginClient.LOGGER.info("Origin: WorldEditCUI is installed; Origin's WorldEdit CUI stays off");
				return;
			}
			sender = registerOwnChannel();
			if (sender == null) {
				return;
			}
			ClientTickEvents.END_CLIENT_TICK.register(WorldEditCui::tick);
			WorldRenderEvents.LAST.register(ctx -> {
				try {
					CuiRenderer.render(ctx);
				} catch (Throwable t) {
					// the overlay must never take the frame down
				}
			});
		} catch (Throwable t) {
			sender = null;
			OriginClient.LOGGER.warn("Origin: WorldEdit CUI could not start; it stays off", t);
		}
	}

	// ---- channel ----

	/** Sends one protocol line to the server. */
	private interface Sender {
		void send(String event, String... args) throws Exception;
	}

	/** The `worldedit:cui` plugin channel: each body is one raw UTF-8 string. */
	private static final ResourceLocation CHANNEL = new ResourceLocation("worldedit", "cui");

	private static Sender registerOwnChannel() {
		// Pre-1.20.5 Fabric runs play-channel receivers on the NETTY thread, so the
		// text is read off the buffer there and the state change hopped onto the
		// client thread. registerGlobalReceiver answers false if another mod already
		// owns the channel's receiver -- then we stay dormant.
		boolean ok = ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, responseSender) -> {
			String text = buf.toString(StandardCharsets.UTF_8);
			buf.skipBytes(buf.readableBytes());
			client.execute(() -> onMessage(text));
		});
		if (!ok) {
			OriginClient.LOGGER.warn("Origin: another mod already owns worldedit:cui; Origin's WorldEdit CUI stays off");
			return null;
		}
		return (event, args) -> {
			FriendlyByteBuf buf = PacketByteBufs.create();
			buf.writeBytes(join(false, event, Arrays.asList(args)).getBytes(StandardCharsets.UTF_8));
			ClientPlayNetworking.send(CHANNEL, buf);
		};
	}

	private static String join(boolean isMulti, String event, List<String> args) {
		StringBuilder sb = new StringBuilder(isMulti ? "+" : "").append(event);
		for (String a : args) {
			sb.append('|').append(a);
		}
		return sb.toString();
	}

	private static void hello() {
		try {
			sender.send("v", PROTOCOL_VERSION);
		} catch (Throwable t) {
			// not connected / channel refused: nothing to show, nothing to break
		}
	}

	// ---- lifecycle ----

	// Hello on join, on every world/dimension change (WorldEdit forgets CUI
	// support on respawn/teleport across worlds), and whenever the player turns
	// the mod on mid-session. State is cleared each time; the server re-sends.
	private static void tick(Minecraft mc) {
		boolean on = Mods.on(ID);
		if (mc.player == null || mc.level == null || mc.getConnection() == null) {
			if (lastLevel != null || selection != null || !multi.isEmpty()) {
				clear();
			}
			lastLevel = null;
			lastPlayer = null;
			wasOn = false;
			delayedHello = 0;
			return;
		}
		if (!on) {
			wasOn = false;
			return;
		}
		if (!wasOn || mc.level != lastLevel || mc.player != lastPlayer) {
			wasOn = true;
			lastLevel = mc.level;
			lastPlayer = mc.player;
			clear();
			hello();
			delayedHello = DELAYED_HELLO_TICKS;
		}
		if (delayedHello > 0 && --delayedHello == 0) {
			hello();
		}
	}

	private static void clear() {
		selection = null;
		multi.clear();
		activeMulti = null;
	}

	// ---- events ----

	static void onMessage(String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		try {
			String[] split = text.split("\\|", -1);
			boolean isMulti = split[0].startsWith("+");
			String event = isMulti ? split[0].substring(1) : split[0];
			String[] a = Arrays.copyOfRange(split, 1, split.length);
			handle(isMulti, event, a);
		} catch (Throwable t) {
			// Bad numbers, missing args, an event for the wrong shape: WorldEditCUI
			// drops those too. One bad line must never cost the selection.
		}
	}

	private static void handle(boolean isMulti, String event, String[] a) {
		if ("s".equals(event)) {
			select(isMulti, a);
			return;
		}
		CuiRegion r = isMulti ? activeMulti : selection;
		if (r == null) {
			return;
		}
		switch (event) {
			case "p" -> point(r, isMulti, a);
			case "p2" -> {
				if (r.type.equals(CuiRegion.POLYGON) && a.length >= 3) {
					CuiRegion.put(r.points2d, i(a[0]), new int[]{i(a[1]), i(a[2])});
				}
			}
			case "mm" -> {
				if (a.length >= 2 && (r.type.equals(CuiRegion.POLYGON) || r.type.equals(CuiRegion.CYLINDER))) {
					r.minY = i(a[0]);
					r.maxY = i(a[1]);
				}
			}
			case "e" -> {
				if (r.type.equals(CuiRegion.ELLIPSOID) && a.length >= 4) {
					if (i(a[0]) == 0) {
						r.centre = new int[]{i(a[1]), i(a[2]), i(a[3])};
					} else if (i(a[0]) == 1) {
						r.radii = new double[]{d(a[1]), d(a[2]), d(a[3])};
					}
				}
			}
			case "cyl" -> {
				if (r.type.equals(CuiRegion.CYLINDER) && a.length >= 5) {
					r.centre = new int[]{i(a[0]), i(a[1]), i(a[2])};
					r.radX = d(a[3]);
					r.radZ = d(a[4]);
				}
			}
			case "poly" -> {
				if (r.type.equals(CuiRegion.POLYHEDRON) && a.length >= 3) {
					int[] ids = new int[a.length];
					for (int k = 0; k < a.length; k++) {
						ids[k] = i(a[k]);
						if (CuiRegion.get(r.points, ids[k]) == null) {
							return;   // face names a vertex we never got; WorldEditCUI drops it too
						}
					}
					r.faces.add(ids);
				}
			}
			case "col" -> {
				if (isMulti && a.length >= 4) {
					int n = r.paletteSize();
					Integer[] pal = new Integer[n];
					for (int k = 0; k < n; k++) {
						pal[k] = parseRgba(a[k]);
					}
					r.palette = pal;
				}
			}
			case "grid" -> {
				if (isMulti && a.length >= 1) {
					r.gridSpacing = d(a[0]);
					r.visibleOnly = a.length > 1 && "cull".equalsIgnoreCase(a[1]);
				}
			}
			default -> {
				// "u" (update) and anything newer than protocol 4: nothing to do
			}
		}
	}

	// `s|<type>` replaces the player's selection; `+s|<type>|<uuid>` adds/replaces
	// one multi region (and makes it the target of the `+` events that follow);
	// `+s|clear|<uuid>` removes one; a bare `+s|clear` removes them all.
	private static void select(boolean isMulti, String[] a) {
		CuiRegion r = a.length > 0 ? CuiRegion.create(a[0]) : null;
		if (!isMulti) {
			selection = r;
			return;
		}
		if (a.length < 2) {
			if (r == null) {
				multi.clear();
				activeMulti = null;
			}
			return;
		}
		UUID id = UUID.fromString(a[1]);
		if (r == null) {
			multi.remove(id);
			activeMulti = null;
		} else {
			multi.put(id, r);
			activeMulti = r;
		}
	}

	// `p|id|x|y|z|area`: a cuboid corner (ids 0/1) or a polyhedron vertex.
	// `+p|id|~|~|~|dist`: a corner that tracks the looked-at block (multi only,
	// as in WorldEditCUI), traced no shorter than the player's reach, max 256.
	private static void point(CuiRegion r, boolean isMulti, String[] a) {
		boolean cuboid = r.type.equals(CuiRegion.CUBOID);
		if ((!cuboid && !r.type.equals(CuiRegion.POLYHEDRON)) || a.length < 4) {
			return;
		}
		int id = i(a[0]);
		if (id < 0 || (cuboid && id > 1) || id > CuiRegion.MAX_ID) {
			return;
		}
		if (isMulti && cuboid && "~".equals(a[1]) && "~".equals(a[2]) && "~".equals(a[3])) {
			double reach = 4.5;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				// No block_interaction_range attribute before 1.20.5: the game mode's pick range.
				reach = mc.gameMode != null ? mc.gameMode.getPickRange() : reach;
			}
			double dist = a.length > 4 ? d(a[4]) : reach;
			CuiRegion.put(r.latches, id, Math.min(Math.max(dist, reach), 256.0));
			CuiRegion.put(r.points, id, null);
			return;
		}
		CuiRegion.put(r.points, id, new double[]{d(a[1]), d(a[2]), d(a[3])});
		if (id < r.latches.size()) {
			r.latches.set(id, null);
		}
	}

	// WorldEditCUI parses ints through a float ("12.0" is a valid int there).
	private static int i(String s) {
		return (int) Float.parseFloat(s);
	}

	private static double d(String s) {
		return Double.parseDouble(s);
	}

	/** `#RRGGBBAA` / `RRGGBB` (the `+col` format) -> ARGB; unparseable -> null = "use the player's colour". */
	private static Integer parseRgba(String s) {
		String h = s.startsWith("#") ? s.substring(1) : s;
		try {
			if (h.length() == 8) {
				long v = Long.parseLong(h, 16);
				return (int) (((v & 0xFF) << 24) | (v >>> 8));
			}
			if (h.length() == 6) {
				return 0xFF000000 | Integer.parseInt(h, 16);
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return null;
	}

	/** Every region to draw this frame: the player's selection, then any multi regions. */
	static List<CuiRegion> regions() {
		List<CuiRegion> out = new ArrayList<>(1 + multi.size());
		if (selection != null) {
			out.add(selection);
		}
		out.addAll(multi.values());
		return out;
	}
}
