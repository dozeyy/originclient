package com.origin.client.client.mods;

import java.util.Locale;

/**
 * Is this server Hypixel? Hypixel's rules ban some features there (Freelook), and
 * Origin's Hypixel helpers only run there, so the answer must not depend on how the
 * player typed the address.
 *
 * Two signals, either is enough: the address ends in hypixel.net, or the server's
 * brand -- the name every server sends on join, the one F3 shows ("Hypixel
 * BungeeCord ...") -- names Hypixel. The brand still says Hypixel when the player
 * joined through a numeric IP, their own DNS alias or an SRV name. Both are values
 * the client already holds: client-side and read-only, nothing is sent.
 *
 * Pure Java (no Minecraft, Java 8): the same file ships in every module, pre-1.20 and
 * 1.8.9 included, and tools/ingame-guard-tests proves the matching.
 */
public final class HypixelDetect {

	private HypixelDetect() {
	}

	public static boolean isHypixel(String address, String brand) {
		return isHypixelHost(address) || isHypixelBrand(brand);
	}

	/** "mc.hypixel.net", "Hypixel.net:25565", "mc.hypixel.net." -- not "nothypixel.net". */
	public static boolean isHypixelHost(String address) {
		if (address == null) {
			return false;
		}
		String host = address.trim().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon);            // "mc.hypixel.net:25565"
		}
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
	}

	public static boolean isHypixelBrand(String brand) {
		return brand != null && brand.toLowerCase(Locale.ROOT).contains("hypixel");
	}
}
