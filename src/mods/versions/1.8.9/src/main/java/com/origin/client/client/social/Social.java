package com.origin.client.client.social;

import com.origin.client.OriginClient;
import com.origin.client.client.OriginKeyBindings;
import com.origin.client.client.gui.FriendsScreen;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.opengl.Display;

import java.util.Locale;

/**
 * The client-thread side of Origin friends / parties: reports presence to
 * SocialClient every tick, turns its events into toasts and party-chat lines,
 * and owns the actions that touch the game itself (joining a friend's server,
 * pinging the looked-at block, the client-only party chat command). The
 * "Friends & Party" mod (id {@link #MOD}) switches the in-world features and
 * pop-ups; the Friends window itself always works.
 *
 * 1.8.9: the hooks are Forge events in hooks/SocialHooks (client tick, render
 * tick for toasts, screen mouse input for toast clicks, RenderWorldLastEvent
 * for ping beams); /originpc is a Forge client command
 * (ClientCommandHandler, which GuiChat asks before anything is sent, so it
 * never reaches the server). 1.8.9 chat has no RGB, so the party colour is
 * shown as the nearest chat colour.
 */
public final class Social {
	private Social() {
	}

	public static final String MOD = "friends";
	/** The client-only party chat command (docs/SOCIAL.md). */
	public static final String COMMAND = "originpc";
	/** 1.8.9: this module only ever runs on one game version. */
	private static final String VERSION = "1.8.9";
	private static final double PING_RANGE = 128.0;
	private static long lastPingAt = 0;

	/** Called once from client init; every hook is fail-soft. */
	public static void init() {
		SocialClient.start();
		try {
			ClientCommandHandler.instance.registerCommand(new PartyCommand());
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: /" + COMMAND + " command failed to register", t);
		}
	}

	/**
	 * The world pass (ping beams). 1.8.9: from SocialHooks' RenderWorldLastEvent,
	 * where the GL matrix is the camera's and world coordinates are drawn
	 * relative to the camera position (RenderManager's renderPos).
	 */
	public static void renderWorld(float partialTicks) {
		try {
			PartyWorld.renderWorld(partialTicks);
		} catch (Throwable t) {
			// pings just don't draw this frame
		}
	}

	/** A "Friends & Party" switch: the mod is on AND its own row is on. */
	public static boolean on(String key) {
		return Mods.on(MOD) && Mods.bool(MOD, key);
	}

	public static String version() {
		return VERSION;
	}

	/**
	 * The dimension as the service's id string (the form every other version
	 * reports). 1.8.9 dimensions are plain ints: 0 / -1 / 1, others "dim:<n>".
	 */
	public static String dim(Minecraft mc) {
		int d = mc.thePlayer != null ? mc.thePlayer.dimension : 0;
		switch (d) {
			case 0:
				return "minecraft:overworld";
			case -1:
				return "minecraft:the_nether";
			case 1:
				return "minecraft:the_end";
			default:
				return "dim:" + d;
		}
	}

	/** The server you're on as "host[:port]" (lowercase, default port dropped), else null. */
	public static String currentServer(Minecraft mc) {
		if (mc.theWorld == null || mc.isSingleplayer()) {
			return null;
		}
		ServerData sd = mc.getCurrentServerData();
		if (sd == null || sd.serverIP == null || sd.serverIP.trim().isEmpty()) {
			return null;
		}
		return normalize(sd.serverIP);
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

	// ---- tick (SocialHooks: ClientTickEvent END) ----

	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		SocialClient.setFocused(Display.isActive(), now);   // 1.8.9: LWJGL2's window focus
		SocialClient.setPresence(presence(mc));

		while (OriginKeyBindings.partyPing != null && OriginKeyBindings.partyPing.isPressed()) {
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
		if (mc.theWorld == null) {
			return new SocialClient.Presence("menu", null, version, null);
		}
		if (mc.isSingleplayer()) {
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
		if (key == null || mc.thePlayer == null || !SocialClient.state().inParty()) {
			return null;
		}
		net.minecraft.client.entity.EntityPlayerSP p = mc.thePlayer;
		return new SocialClient.PartyStateOut(key, dim(mc), p.posX, p.posY, p.posZ, p.getHealth(), p.getMaxHealth());
	}

	private static void handle(Minecraft mc, SocialClient.Event e) {
		switch (e.kind()) {
			case MESSAGE:
				if (on("toastMessages") && !FriendsScreen.isShowingChat(mc, e.uuid())) {
					SocialToast.message(mc, e.uuid(), e.name(), e.text());
				}
				break;
			case REQUEST:
				if (on("toastRequests")) {
					SocialToast.request(mc, e.uuid(), e.name());
				}
				break;
			case INVITE:
				if (on("toastInvites")) {
					SocialToast.invite(mc, e.uuid(), e.name());
				}
				break;
			case WARP:
				if (on("toastWarps")) {
					SocialToast.warp(mc, e.uuid(), e.name(), e.warp());
				}
				break;
			case PARTY_CHAT:
				if (on("partyChatInChat")) {
					partyLine(mc, e.name(), e.text());
				}
				break;
			default:
				break;
		}
	}

	// ---- party chat in vanilla chat (client-side only) ----

	/** "[Party] <name>: <text>" into the chat box. Never sent to the server. */
	public static void partyLine(Minecraft mc, String name, String text) {
		if (mc.ingameGUI == null) {
			return;
		}
		EnumChatFormatting tint = nearest(Mods.color(MOD, "partyColor") & 0xFFFFFF);
		IChatComponent line = new ChatComponentText("[Party] ").setChatStyle(new ChatStyle().setColor(tint));
		line.appendSibling(new ChatComponentText(name == null ? "?" : name)
				.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.WHITE)));
		line.appendSibling(new ChatComponentText(": " + text)
				.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY)));
		mc.ingameGUI.getChatGUI().printChatMessage(line);
	}

	private static void note(Minecraft mc, String text, boolean red) {
		if (mc.ingameGUI == null) {
			return;
		}
		mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(text)
				.setChatStyle(new ChatStyle().setColor(red ? EnumChatFormatting.RED : EnumChatFormatting.GRAY)));
	}

	/** The above-hotbar line (1.16.5's displayClientMessage(text, true)): 1.8.9's record line. */
	private static void actionBar(Minecraft mc, String text) {
		if (mc.ingameGUI != null) {
			mc.ingameGUI.setRecordPlaying(text, false);
		}
	}

	// The 16 chat colours' RGB, in EnumChatFormatting order (BLACK .. WHITE).
	private static final int[] CHAT_RGB = {
			0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	private static final EnumChatFormatting[] CHAT_COLORS = {
			EnumChatFormatting.BLACK, EnumChatFormatting.DARK_BLUE, EnumChatFormatting.DARK_GREEN,
			EnumChatFormatting.DARK_AQUA, EnumChatFormatting.DARK_RED, EnumChatFormatting.DARK_PURPLE,
			EnumChatFormatting.GOLD, EnumChatFormatting.GRAY, EnumChatFormatting.DARK_GRAY, EnumChatFormatting.BLUE,
			EnumChatFormatting.GREEN, EnumChatFormatting.AQUA, EnumChatFormatting.RED, EnumChatFormatting.LIGHT_PURPLE,
			EnumChatFormatting.YELLOW, EnumChatFormatting.WHITE};

	/** 1.8.9 chat has 16 colours: the closest one to `rgb`. */
	public static EnumChatFormatting nearest(int rgb) {
		int best = CHAT_RGB.length - 1;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < CHAT_RGB.length; i++) {
			long dr = ((rgb >> 16) & 0xFF) - ((CHAT_RGB[i] >> 16) & 0xFF);
			long dg = ((rgb >> 8) & 0xFF) - ((CHAT_RGB[i] >> 8) & 0xFF);
			long db = (rgb & 0xFF) - (CHAT_RGB[i] & 0xFF);
			long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return CHAT_COLORS[best];
	}

	/**
	 * /originpc <text>: party chat from the chat box (Will, 2026-10-12: a name no
	 * server uses -- /pc and /p belong to server plugins). 1.8.9: a Forge client
	 * command -- GuiChat hands every "/..." line to ClientCommandHandler first and
	 * sends nothing when it is handled, the bare "/originpc" included.
	 */
	private static final class PartyCommand extends CommandBase {
		@Override
		public String getCommandName() {
			return COMMAND;
		}

		@Override
		public String getCommandUsage(ICommandSender sender) {
			return "/" + COMMAND + " <message>";
		}

		@Override
		public boolean canCommandSenderUseCommand(ICommandSender sender) {
			return true;   // client-side: no permission level applies
		}

		@Override
		public int getRequiredPermissionLevel() {
			return 0;
		}

		@Override
		public void processCommand(ICommandSender sender, String[] args) {
			try {
				partyCommand(args == null ? "" : String.join(" ", args));
			} catch (Throwable t) {
				// never let a party line break the chat box
			}
		}
	}

	private static void partyCommand(String text) {
		final Minecraft mc = Minecraft.getMinecraft();
		String t = text == null ? "" : text.trim();
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
			partyLine(mc, s.me() != null ? s.me().name() : mc.getSession().getUsername(), t);
		}
		SocialClient.partyChat(t, echo).thenAccept(err -> {
			if (err != null) {
				mc.addScheduledTask(() -> note(mc, err, true));
			}
		});
	}

	/** What the Friends window / commands say while the service isn't ready. */
	public static String statusSentence() {
		switch (SocialClient.status()) {
			case SIGNED_OUT:
				return "Sign in with Microsoft to use friends.";
			case REFUSED:
				return SocialClient.refusal();
			case COMING_SOON:
				return "Friends are coming soon.";
			case UNREACHABLE:
				return "Couldn't reach Origin. Check your connection.";
			case STARTING:
				return "Connecting to Origin...";
			default:
				return "";
		}
	}

	// ---- F: ping the looked-at block ----

	private static void pingLookedAt(final Minecraft mc) {
		if (mc.thePlayer == null || mc.theWorld == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPingAt < 1000) {
			return;
		}
		if (!SocialClient.state().inParty()) {
			actionBar(mc, "Join a party to ping");
			return;
		}
		String server = FairPlay.positionKey(mc);
		if (server == null) {
			actionBar(mc, "Pings work in LAN worlds only (fair play)");
			return;
		}
		if (!on("showPings")) {
			actionBar(mc, "Turn on Pings in Friends & Party");
			return;
		}
		// 1.8.9: Entity.rayTrace(distance, partialTicks) -- blocks only, like 1.16.5's pick(.., false)
		MovingObjectPosition hit = mc.thePlayer.rayTrace(PING_RANGE, 1f);
		if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.getBlockPos() == null) {
			actionBar(mc, "Nothing to ping there");
			return;
		}
		lastPingAt = now;
		BlockPos pos = hit.getBlockPos();
		String label = mc.theWorld.getBlockState(pos).getBlock().getLocalizedName();
		SocialClient.ping(server, dim(mc), pos.getX(), pos.getY(), pos.getZ(), label)
				.thenAccept(err -> {
					if (err != null) {
						mc.addScheduledTask(() -> actionBar(mc, err));
					}
				});
		actionBar(mc, "Pinged " + label);
	}

	// ---- E: joining a friend / the party leader ----

	/** Can this game connect to `server` (a friend on `version`)? Null = yes, else why not. */
	public static String whyCantJoin(String server, String version) {
		if (server == null || server.trim().isEmpty()) {
			return "They're not on a server you can join.";
		}
		if (version != null && !version.equals(version())) {
			return "They're on " + version + ". Join from the Origin launcher.";
		}
		// a name with no spaces will do (ServerAddress.fromString never refuses one)
		if (server.trim().contains(" ")) {
			return "Couldn't read that server address.";
		}
		if (normalize(server).equals(currentServer(Minecraft.getMinecraft()))) {
			return "You're already on that server.";
		}
		return null;
	}

	/** Leaves the current world and connects to `server`. Client thread. */
	public static void join(String server) {
		Minecraft mc = Minecraft.getMinecraft();
		try {
			if (mc.theWorld != null) {
				// 1.8.9: the pause menu's own Disconnect sequence (loadWorld(null) also
				// stops a singleplayer world's integrated server)
				mc.theWorld.sendQuittingDisconnectingPacket();
				mc.loadWorld((WorldClient) null);
			}
			// 1.8.9: ServerData(name, ip, lan); GuiConnecting connects from its constructor
			ServerData data = new ServerData(server, server, false);
			mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, data));
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin: couldn't join " + server, t);
			mc.displayGuiScreen(new GuiMultiplayer(new GuiMainMenu()));
		}
	}

	/** Opens the Friends window on `select` (a friend uuid, {@link FriendsScreen#PARTY}, or null). */
	public static void openFriends(String select) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.currentScreen instanceof FriendsScreen) {
			((FriendsScreen) mc.currentScreen).select(select);
			return;
		}
		mc.displayGuiScreen(new FriendsScreen(mc.currentScreen).select(select));
	}
}
