package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.Locale;
import java.util.regex.Pattern;

// Auto GG (Lunar parity; Lunar ships it inside its Hypixel mod): when a game
// ends, say gg in all chat after a short delay, optionally followed by a
// second line. Hide Karma hides Hypixel's karma spam. (Anti GG -- hiding
// other players' gg lines -- is the Hypixel mod's row, as in Lunar.)
//
// "Game ended" is read from the end-of-game summary Hypixel prints. Those
// lines are server (system) messages, centred with leading spaces -- player
// chat is never indented -- so every trigger requires that indent, which keeps
// a player typing "Winner: me" from setting it off. One trigger arms one send;
// further summary lines within COOLDOWN_MS (the rest of the same summary) are
// ignored.
//
// 1.8.9: incoming lines come from ChatEvents (Forge's ClientChatReceivedEvent,
// via ServerHooks), and lines are sent with EntityPlayerSP.sendChatMessage(text),
// where a leading "/" makes it a server command.
public final class AutoGG {
	private AutoGG() {
	}

	private static final String ID = "autogg";
	private static final long COOLDOWN_MS = 10_000;
	private static final long SECOND_LINE_GAP_MS = 1_000;

	// Hypixel end-of-game summary lines (after stripping colour codes).
	private static final Pattern[] TRIGGERS = {
			Pattern.compile("^ +1st Killer - .+"),
			Pattern.compile("^ +1st Place ?[-:] .+"),
			Pattern.compile("^ +Winners?: .+"),
			Pattern.compile("^ +Winner #1 .+"),
			Pattern.compile("^ +Winning Team ?[-:] .+"),
			Pattern.compile("^ +.+ - Damage Dealt - .+"),
			Pattern.compile("^ +(?:.+ )?WINNER!.*"),
			Pattern.compile("^ +Reward Summary *$"),
	};
	private static final Pattern KARMA_LINE = Pattern.compile("^\\+\\d+ Karma!$");

	private static long armedAt = 0;          // when the last trigger fired
	private static long sendFirstAt = 0;      // 0 = nothing pending
	private static long sendSecondAt = 0;

	/** Nothing to register on 1.8.9: ServerHooks (ClientChatReceivedEvent) -> ChatEvents calls in. */
	public static void register() {
	}

	// 1.8.9: getUnformattedText still carries any legacy § codes inside the
	// text (Hypixel's 1.8 lines are full of them), so strip after flattening.
	private static String plain(IChatComponent message) {
		String s = EnumChatFormatting.getTextWithoutFormattingCodes(message.getUnformattedText());
		return s == null ? "" : s;
	}

	/** False hides the (server) message: Hide Karma Messages. */
	static boolean allow(IChatComponent message) {
		try {
			if (!Mods.on(ID) || !Mods.bool(ID, "antiKarma")) {
				return true;
			}
			return !KARMA_LINE.matcher(plain(message).trim()).matches();
		} catch (Throwable t) {
			return true;
		}
	}

	static void onSystemMessage(IChatComponent message) {
		if (!Mods.on(ID)) {
			return;
		}
		String s = plain(message);
		if (s.isEmpty() || s.charAt(0) != ' ') {
			return;   // cheap reject: every trigger is an indented summary line
		}
		long now = System.currentTimeMillis();
		if (now - armedAt < COOLDOWN_MS) {
			return;
		}
		for (Pattern p : TRIGGERS) {
			if (p.matcher(s).matches()) {
				armedAt = now;
				sendFirstAt = now + Math.round(Mods.num(ID, "delay") * 1000);
				sendSecondAt = 0;
				return;
			}
		}
	}

	/** Sends whatever is due. Called once per client tick. */
	public static void tick(Minecraft mc) {
		if (sendFirstAt == 0 && sendSecondAt == 0) {
			return;
		}
		if (!Mods.on(ID) || mc.getNetHandler() == null) {
			sendFirstAt = sendSecondAt = 0;
			return;
		}
		long now = System.currentTimeMillis();
		if (sendFirstAt != 0 && now >= sendFirstAt) {
			sendFirstAt = 0;
			say(mc, Mods.text(ID, "message"));
			if (Mods.bool(ID, "secondMessage")) {
				sendSecondAt = now + SECOND_LINE_GAP_MS;
			}
		}
		if (sendSecondAt != 0 && now >= sendSecondAt) {
			sendSecondAt = 0;
			say(mc, Mods.text(ID, "secondText"));
		}
	}

	// On Hypixel the post-game lobby can still be on team or party chat, so the
	// line goes through /ac (all chat), as every Hypixel Auto GG does.
	private static void say(Minecraft mc, String msg) {
		// Same character rules as typed chat (a stray § would get you kicked).
		msg = ChatAllowedCharacters.filterAllowedCharacters(msg).trim();
		EntityPlayerSP player = mc.thePlayer;
		if (player == null || mc.getNetHandler() == null || msg.isEmpty()) {
			return;
		}
		if (msg.startsWith("/")) {
			player.sendChatMessage(msg);
		} else if (onHypixel(mc)) {
			player.sendChatMessage("/ac " + msg);
		} else {
			player.sendChatMessage(msg);
		}
	}

	static boolean onHypixel(Minecraft mc) {
		ServerData sd = mc.getCurrentServerData();
		if (sd == null || sd.serverIP == null) {
			return false;
		}
		String host = sd.serverIP.toLowerCase(Locale.ROOT);
		int colon = host.indexOf(':');
		if (colon >= 0) {
			host = host.substring(0, colon);
		}
		return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
	}
}
