package com.origin.client.client.worldedit;

import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;

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
// The protocol (reference client: github.com/EngineHub/WorldEditCUI):
//   * each payload is ONE raw UTF-8 string, no length prefix:
//     `[+]<event>|<arg>|<arg>...` -- a leading `+` marks a "multi" region
//     (WorldGuard/CraftBook region previews) instead of the player's own selection.
//   * the client says hello with `v|4` (protocol version 4). WorldEdit sends
//     nothing until it has seen that, then immediately re-sends the current
//     selection (servers compare `>=` against each shape's protocol version, so
//     4 is fine for WorldEdit 6's protocol 3). Like WorldEditCUI we send it on
//     join/world change and again 10 ticks later (a Bukkit server can register
//     its channel a moment after join).
//   * events: s (new selection: cuboid / polygon2d / ellipsoid / cylinder /
//     polyhedron / clear), p (3D point / polyhedron vertex), p2 (2D polygon
//     corner), mm (polygon/cylinder Y range), e (ellipsoid centre=0 / radii=1),
//     cyl (cylinder centre + radii), poly (polyhedron face), col (multi palette),
//     grid (multi grid spacing [+ "cull"]), u (update; no-op).
//
// The only packets this ever sends are the channel registration and that `v|4`
// hello, and only while the mod is on -- it is the whole job of the mod. Servers
// without WorldEdit discard an unknown plugin-channel payload silently.
//
// 1.8.9 networking: the pre-1.13 channel is named "WECUI" (what WorldEdit 6 for
// 1.8 registers; ViaVersion maps the modern `worldedit:cui` to it for a 1.8
// client on a newer server). Receiving goes through FML: an event-driven channel
// (NetworkRegistry.newEventDrivenChannel) delivers FMLNetworkEvent.ClientCustomPacketEvent
// on the NETTY thread, so the text is copied out and the state change hops to
// the client thread (Minecraft.addScheduledTask). Sending is a plain vanilla
// C17PacketCustomPayload, preceded by a "REGISTER" payload naming WECUI so a
// Bukkit server knows the client listens (harmless if FML already announced it).
//
// Coexistence (never broken):
//   * the real WorldEditCUI mod installed -> we stay dormant; it owns the channel
//     and the drawing, and two overlays on one selection would just fight.
//   * anything else already registered a client channel named WECUI -> log once
//     and stay dormant.
public final class WorldEditCui {
	public static final String ID = "worldeditcui";

	private static final String CHANNEL = "WECUI";
	private static final String PROTOCOL_VERSION = "4";
	// Bukkit can announce its plugin channels a few ticks after join; WorldEditCUI
	// re-sends the hello this many ticks later for that reason.
	private static final int DELAYED_HELLO_TICKS = 10;

	// Selection state. Touched only on the client thread (packet handlers are
	// hopped onto it), so no locking.
	static CuiRegion selection;
	static final Map<UUID, CuiRegion> multi = new LinkedHashMap<>();
	private static CuiRegion activeMulti;

	private static boolean started, active;
	private static FMLEventChannel channel;
	private static boolean wasOn;
	private static WorldClient lastLevel;
	private static EntityPlayerSP lastPlayer;
	private static int delayedHello;

	private WorldEditCui() {
	}

	/** Called once from WorldHooks' constructor (FML init time). Never throws; idempotent. */
	public static synchronized void init() {
		if (started) {
			return;
		}
		started = true;
		try {
			if (OriginClient.isModLoaded("worldeditcui") || OriginClient.isModLoaded("WorldEditCUI")) {
				OriginClient.LOGGER.info("Origin: WorldEditCUI is installed; Origin's WorldEdit CUI stays off");
				return;
			}
			if (NetworkRegistry.INSTANCE.hasChannel(CHANNEL, Side.CLIENT)) {   // 1.8.9 NAME UNVERIFIED
				OriginClient.LOGGER.warn("Origin: another mod already owns the WECUI channel; Origin's WorldEdit CUI stays off");
				return;
			}
			channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);   // 1.8.9 NAME UNVERIFIED
			channel.register(new Receiver());
			active = true;
		} catch (Throwable t) {
			active = false;
			OriginClient.LOGGER.warn("Origin: WorldEdit CUI could not start; it stays off", t);
		}
	}

	/** FML posts the channel's packets on the channel's own event bus. */
	public static final class Receiver {
		@SubscribeEvent
		public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
			try {
				// 1.8.9 NAME UNVERIFIED: FMLNetworkEvent.packet / FMLProxyPacket.payload()
				final String text = readText(event.packet.payload());
				Minecraft.getMinecraft().addScheduledTask(new Runnable() {
					@Override
					public void run() {
						onMessage(text);
					}
				});
			} catch (Throwable ignored) {
				// a malformed payload is dropped, as WorldEditCUI does
			}
		}
	}

	// ---- channel ----

	private static String readText(ByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.getBytes(buf.readerIndex(), bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static String join(boolean isMulti, String event, List<String> args) {
		StringBuilder sb = new StringBuilder(isMulti ? "+" : "").append(event);
		for (String a : args) {
			sb.append('|').append(a);
		}
		return sb.toString();
	}

	private static void send(String channelName, String text) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.getNetHandler() == null) {
			return;
		}
		PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.UTF_8)));
		mc.getNetHandler().addToSendQueue(new C17PacketCustomPayload(channelName, buf));
	}

	private static void hello() {
		try {
			send("REGISTER", CHANNEL);
			send(CHANNEL, join(false, "v", Arrays.asList(PROTOCOL_VERSION)));
		} catch (Throwable t) {
			// not connected / channel refused: nothing to show, nothing to break
		}
	}

	// ---- lifecycle ----

	// Hello on join, on every world/dimension change (WorldEdit forgets CUI
	// support on respawn/teleport across worlds), and whenever the player turns
	// the mod on mid-session. State is cleared each time; the server re-sends.
	// 1.8.9: ticked from WorldHooks' ClientTickEvent (END).
	public static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		boolean on = Mods.on(ID);
		if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
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
		if (!wasOn || mc.theWorld != lastLevel || mc.thePlayer != lastPlayer) {
			wasOn = true;
			lastLevel = mc.theWorld;
			lastPlayer = mc.thePlayer;
			clear();
			hello();
			delayedHello = DELAYED_HELLO_TICKS;
		}
		if (delayedHello > 0 && --delayedHello == 0) {
			hello();
		}
	}

	/** WorldHooks' RenderWorldLastEvent: draws the selection (CuiRenderer is package-private). */
	public static void renderWorld(float partialTicks) {
		if (active) {
			CuiRenderer.render(partialTicks);
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
			case "p": point(r, isMulti, a); break;
			case "p2": {
				if (r.type.equals(CuiRegion.POLYGON) && a.length >= 3) {
					CuiRegion.put(r.points2d, i(a[0]), new int[]{i(a[1]), i(a[2])});
				}
				break;
			}
			case "mm": {
				if (a.length >= 2 && (r.type.equals(CuiRegion.POLYGON) || r.type.equals(CuiRegion.CYLINDER))) {
					r.minY = i(a[0]);
					r.maxY = i(a[1]);
				}
				break;
			}
			case "e": {
				if (r.type.equals(CuiRegion.ELLIPSOID) && a.length >= 4) {
					if (i(a[0]) == 0) {
						r.centre = new int[]{i(a[1]), i(a[2]), i(a[3])};
					} else if (i(a[0]) == 1) {
						r.radii = new double[]{d(a[1]), d(a[2]), d(a[3])};
					}
				}
				break;
			}
			case "cyl": {
				if (r.type.equals(CuiRegion.CYLINDER) && a.length >= 5) {
					r.centre = new int[]{i(a[0]), i(a[1]), i(a[2])};
					r.radX = d(a[3]);
					r.radZ = d(a[4]);
				}
				break;
			}
			case "poly": {
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
				break;
			}
			case "col": {
				if (isMulti && a.length >= 4) {
					int n = r.paletteSize();
					Integer[] pal = new Integer[n];
					for (int k = 0; k < n; k++) {
						pal[k] = parseRgba(a[k]);
					}
					r.palette = pal;
				}
				break;
			}
			case "grid": {
				if (isMulti && a.length >= 1) {
					r.gridSpacing = d(a[0]);
					r.visibleOnly = a.length > 1 && "cull".equalsIgnoreCase(a[1]);
				}
				break;
			}
			default: {
				// "u" (update) and anything newer than protocol 4: nothing to do
				break;
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
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.playerController != null) {
				reach = mc.playerController.getBlockReachDistance();   // 1.8.9's reach
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
