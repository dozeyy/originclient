package com.origin.client.client.social;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.origin.client.OriginClient;
import com.origin.client.client.OriginKeyBindings;
import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.mods.Mods;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/**
 * The client-thread side of Origin friends / parties: wires the hooks (tick,
 * world render, the client-only /pc command, toast clicks), reports presence
 * to SocialClient every tick, turns its events into toasts and party-chat
 * lines, and owns the actions that touch the game itself (joining a friend's
 * server, pinging the looked-at block). The "Friends & Party" mod (id
 * {@link #MOD}) switches the in-world features and pop-ups; the Friends window
 * itself always works.
 */
public final class Social {
	private Social() {
	}

	public static final String MOD = "friends";
	/** The client-only party chat command (docs/SOCIAL.md). */
	public static final String COMMAND = "originpc";
	private static final double PING_RANGE = 128.0;
	private static long lastPingAt = 0;

	/** Called once from client init; every hook is fail-soft. */
	public static void init() {
		SocialClient.start();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				tick(mc);
			} catch (Throwable t) {
				// a bad tick drops this tick's presence/toasts, never the game
			}
		});
		WorldRenderEvents.LAST.register(ctx -> {
			try {
				com.origin.client.client.waypoints.WaypointHud.capture(ctx);
				PartyWorld.renderWorld(ctx);
			} catch (Throwable t) {
				// pings just don't draw this frame
			}
		});
		// Toasts are clickable wherever the mouse is free (any screen, chat included).
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
				ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
					try {
						return button != 0 || !SocialToast.click(mx, my);
					} catch (Throwable t) {
						return true;
					}
				}));
		try {
			registerPartyCommand();
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: /pc command failed to register", t);
		}
	}

	/** A "Friends & Party" switch: the mod is on AND its own row is on. */
	public static boolean on(String key) {
		return Mods.on(MOD) && Mods.bool(MOD, key);
	}

	public static String version() {
		try {
			return SharedConstants.getCurrentVersion().getName();
		} catch (Throwable t) {
			return "1.21.1";
		}
	}

	/** The server you're on as "host[:port]" (lowercase, default port dropped), else null. */
	public static String currentServer(Minecraft mc) {
		if (mc.level == null || mc.hasSingleplayerServer()) {
			return null;
		}
		ServerData sd = mc.getCurrentServer();
		if (sd == null || mc.isConnectedToRealms() || sd.ip == null || sd.ip.isBlank()) {
			return null;
		}
		return normalize(sd.ip);
	}

	public static String normalize(String server) {
		String s = server.trim().toLowerCase(Locale.ROOT);
		if (s.endsWith(":25565")) {
			s = s.substring(0, s.length() - 6);
		}
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	// ---- tick ----

	private static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		SocialClient.setFocused(mc.isWindowActive(), now);
		SocialClient.setPresence(presence(mc));

		while (OriginKeyBindings.partyPing != null && OriginKeyBindings.partyPing.consumeClick()) {
			pingLookedAt(mc);
		}

		SocialClient.Event e;
		while ((e = SocialClient.EVENTS.poll()) != null) {
			try {
				handle(mc, e);
			} catch (Throwable t) {
				// one bad event never blocks the rest
			}
		}
	}

	private static SocialClient.Presence presence(Minecraft mc) {
		String version = version();
		if (mc.level == null) {
			return new SocialClient.Presence("menu", null, version, null);
		}
		if (mc.hasSingleplayerServer()) {
			return new SocialClient.Presence("singleplayer", null, version, partyState(mc));
		}
		String server = currentServer(mc);
		// A private LAN address means nothing to a friend elsewhere: "Playing online".
		String shown = server != null && FairPlay.isPrivate(FairPlay.host(server)) ? null : server;
		return new SocialClient.Presence("server", shown, version, partyState(mc));
	}

	/** Your position for the party: only in a LAN world (FairPlay), only while in a party. */
	private static SocialClient.PartyStateOut partyState(Minecraft mc) {
		String key = FairPlay.positionKey(mc);
		if (key == null || mc.player == null || !SocialClient.state().inParty()) {
			return null;
		}
		var p = mc.player;
		return new SocialClient.PartyStateOut(key, mc.level.dimension().location().toString(),
				p.getX(), p.getY(), p.getZ(), p.getHealth(), p.getMaxHealth());
	}

	private static void handle(Minecraft mc, SocialClient.Event e) {
		switch (e.kind()) {
			case MESSAGE -> {
				if (on("toastMessages") && !FriendsScreen.isShowingChat(mc, e.uuid())) {
					SocialToast.message(mc, e.uuid(), e.name(), e.text());
				}
			}
			case REQUEST -> {
				if (on("toastRequests")) {
					SocialToast.request(mc, e.uuid(), e.name());
				}
			}
			case INVITE -> {
				if (on("toastInvites")) {
					SocialToast.invite(mc, e.uuid(), e.name());
				}
			}
			case WARP -> {
				if (on("toastWarps")) {
					SocialToast.warp(mc, e.uuid(), e.name(), e.warp());
				}
			}
			case PARTY_CHAT -> {
				if (on("partyChatInChat")) {
					partyLine(mc, e.name(), e.text());
				}
			}
		}
	}

	// ---- party chat in vanilla chat (client-side only) ----

	/** "[Party] <name>: <text>" into the chat box. Never sent to the server. */
	public static void partyLine(Minecraft mc, String name, String text) {
		int tint = Mods.color(MOD, "partyColor") & 0xFFFFFF;
		MutableComponent line = Component.literal("[Party] ")
				.withStyle(s -> s.withColor(TextColor.fromRgb(tint)))
				.append(Component.literal(name == null ? "?" : name).withStyle(s -> s.withColor(TextColor.fromRgb(0xFFFFFF))))
				.append(Component.literal(": " + text).withStyle(s -> s.withColor(TextColor.fromRgb(0xE0E0E0))));
		mc.gui.getChat().addMessage(line);
	}

	private static void note(Minecraft mc, String text, boolean red) {
		mc.gui.getChat().addMessage(Component.literal(text)
				.withStyle(s -> s.withColor(TextColor.fromRgb(red ? 0xFF7066 : 0xB0B0B0))));
	}

	/**
	 * /originpc <text>: party chat from the chat box (Will, 2026-10-12: a name no
	 * server uses -- /pc and /p belong to server plugins). A Fabric CLIENT command,
	 * so it is handled here and never reaches the server. The bare "/originpc" has
	 * its own executor too -- otherwise Fabric would treat it as unknown and
	 * forward it to the server.
	 */
	private static void registerPartyCommand() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal(COMMAND)
						.executes(ctx -> {
							note(Minecraft.getInstance(), "Party chat: /" + COMMAND + " <message>", false);
							return 1;
						})
						.then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
								.executes(ctx -> {
									partyCommand(StringArgumentType.getString(ctx, "message"));
									return 1;
								}))));
	}

	private static void partyCommand(String text) {
		Minecraft mc = Minecraft.getInstance();
		String t = text == null ? "" : text.strip();
		if (t.isEmpty()) {
			note(mc, "Party chat: /" + COMMAND + " <message>", false);
			return;
		}
		if (SocialClient.status() != SocialClient.Status.READY) {
			note(mc, statusSentence(), true);
			return;
		}
		SocialState s = SocialClient.state();
		if (!s.inParty()) {
			note(mc, "You're not in a party. Start one in the Friends window.", true);
			return;
		}
		boolean echo = on("partyChatInChat");
		if (echo) {
			partyLine(mc, s.me() != null ? s.me().name() : mc.getUser().getName(), t);
		}
		SocialClient.partyChat(t, echo).thenAccept(err -> {
			if (err != null) {
				mc.execute(() -> note(mc, err, true));
			}
		});
	}

	/** What the Friends window / commands say while the service isn't ready. */
	public static String statusSentence() {
		return switch (SocialClient.status()) {
			case SIGNED_OUT -> "Sign in with Microsoft to use friends.";
			case COMING_SOON -> "Friends are coming soon.";
			case UNREACHABLE -> "Couldn't reach Origin. Check your connection.";
			case STARTING -> "Connecting to Origin...";
			case READY -> "";
		};
	}

	// ---- F: ping the looked-at block ----

	private static void pingLookedAt(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPingAt < 1000) {
			return;
		}
		if (!SocialClient.state().inParty()) {
			mc.player.displayClientMessage(Component.literal("Join a party to ping"), true);
			return;
		}
		String server = FairPlay.positionKey(mc);
		if (server == null) {
			mc.player.displayClientMessage(Component.literal("Pings work in LAN worlds only (fair play)"), true);
			return;
		}
		if (!on("showPings")) {
			mc.player.displayClientMessage(Component.literal("Turn on Pings in Friends & Party"), true);
			return;
		}
		HitResult hit = mc.player.pick(PING_RANGE, 1f, false);
		if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
			mc.player.displayClientMessage(Component.literal("Nothing to ping there"), true);
			return;
		}
		lastPingAt = now;
		var pos = bh.getBlockPos();
		String label = mc.level.getBlockState(pos).getBlock().getName().getString();
		SocialClient.ping(server, mc.level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), label)
				.thenAccept(err -> {
					if (err != null) {
						mc.execute(() -> {
							if (mc.player != null) {
								mc.player.displayClientMessage(Component.literal(err), true);
							}
						});
					}
				});
		mc.player.displayClientMessage(Component.literal("Pinged " + label), true);
	}

	// ---- E: joining a friend / the party leader ----

	/** Can this game connect to `server` (a friend on `version`)? Null = yes, else why not. */
	public static String whyCantJoin(String server, String version) {
		if (server == null || server.isBlank()) {
			return "They're not on a server you can join.";
		}
		if (version != null && !version.equals(version())) {
			return "They're on " + version + ". Join from the Origin launcher.";
		}
		if (!ServerAddress.isValidAddress(server)) {
			return "Couldn't read that server address.";
		}
		if (normalize(server).equals(currentServer(Minecraft.getInstance()))) {
			return "You're already on that server.";
		}
		return null;
	}

	/** Leaves the current world and connects to `server`. Client thread. */
	public static void join(String server) {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (mc.level != null) {
				boolean local = mc.isLocalServer();
				mc.level.disconnect();
				if (local) {
					mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
				} else {
					mc.clearLevel();
				}
			}
			ServerData data = new ServerData(server, server, false);
			ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc,
					ServerAddress.parseString(server), data, false);
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: couldn't join {}", server, t);
			mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
		}
	}

	/** Opens the Friends window on `select` (a friend uuid, {@link FriendsScreen#PARTY}, or null). */
	public static void openFriends(String select) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof FriendsScreen fs) {
			fs.select(select);
			return;
		}
		mc.setScreen(new FriendsScreen(mc.screen).select(select));
	}
}
