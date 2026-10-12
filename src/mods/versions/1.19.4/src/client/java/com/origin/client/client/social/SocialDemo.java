package com.origin.client.client.social;

import com.origin.client.client.social.SocialState.Friend;
import com.origin.client.client.social.SocialState.Me;
import com.origin.client.client.social.SocialState.Member;
import com.origin.client.client.social.SocialState.MemberState;
import com.origin.client.client.social.SocialState.Message;
import com.origin.client.client.social.SocialState.Party;
import com.origin.client.client.social.SocialState.PartyInvite;
import com.origin.client.client.social.SocialState.PartyMessage;
import com.origin.client.client.social.SocialState.Ping;
import com.origin.client.client.social.SocialState.Request;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * DEV SHOTS ONLY (OriginDevShots.enabled(), like CosmeticsScreen's demo): the
 * offline dev account can't sign in to the service, so the harness fills the
 * social client with made-up friends, a chat and a party to put the Friends
 * window, the Party HUD and a teammate marker on camera. Never runs in play:
 * {@link #install} is only reached from OriginDevShots / OriginDevWorld.
 */
public final class SocialDemo {
	private SocialDemo() {
	}

	public static final String KESTREL = "6f1c2a9b4e7d4c0a8b3e5d2f1a9c7e41";
	private static final String EMBER = "0b7e4d2c9a1f4e6b8c3d5a7f2e9b1c63";
	private static final String MOSS = "a3d9c1e7b5f24a8c9e0b6d4f1c7a2e85";
	private static final String TIDE = "c8e2a6f4d0b14c9e7a5f3b1d8e6c4a27";
	private static final String LUMEN = "e5b1d7f3a9c24e6b0d8f2a4c6e1b9d38";
	private static final String RAVEN = "1d9f5b3e7a0c4f2d8b6e4a1c9f3d7b52";
	private static final String QUARTZ = "9c4a2e8f6b1d4c7e3a5f9b0d2e8c6a14";
	private static final String SABLE = "4e8c6a2f0d9b4e1c7a3f5d8b2c6e9a70";

	/** Back to an empty (signed-in, friendless) client. */
	public static void clear() {
		if (SocialClient.DEMO) {
			SocialClient.demoInstall(SocialState.EMPTY, Map.of(), List.of(), Map.of());
		}
	}

	/** The demo state; `positions` places the party around the player (world set). */
	public static void install(boolean positions) {
		if (!SocialClient.DEMO) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		String me = mc.getUser() != null && mc.getUser().getGameProfile().getId() != null
				? mc.getUser().getGameProfile().getId().toString().replace("-", "") : "00000000000000000000000000000000";
		String myName = mc.getUser() != null ? mc.getUser().getName() : "You";
		long now = System.currentTimeMillis();
		// this game's version, so the demo friends read "Join" like on 1.21.1
		String v = Social.version();

		List<Friend> friends = List.of(
				new Friend(EMBER, "Ember_Fox", true, "game", "server", "play.originsmp.net", v, now),
				new Friend(KESTREL, "Kestrel", true, "game", "server", "mc.hypixel.net", v, now),
				new Friend(MOSS, "MossyCobble", true, "launcher", "menu", null, v, now),
				new Friend(TIDE, "Tidewalker", true, "game", "singleplayer", null, "1.20.4", now),
				new Friend(LUMEN, "Lumen", false, null, null, null, null, now - 3_600_000),
				new Friend(RAVEN, "Ravenmoor", false, null, null, null, null, now - 86_400_000));

		MemberState emberAt = null, mossAt = null;
		String server = "play.originsmp.net";
		String dim = "minecraft:overworld";
		if (positions && mc.player != null && mc.level != null) {
			// in front of the camera, so the world shot sees the marker and the beam
			Vec3 eye = mc.player.getEyePosition();
			Vec3 look = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
			Vec3 side = new Vec3(-look.z, 0, look.x);
			Vec3 a = eye.add(look.scale(18)).add(side.scale(-5));
			Vec3 b = eye.add(look.scale(34)).add(side.scale(9));
			dim = mc.level.dimension().location().toString();
			emberAt = new MemberState(server, dim, a.x, mc.player.getY(), a.z, 17, 20);
			mossAt = new MemberState(server, dim, b.x, mc.player.getY() + 2, b.z, 9, 20);
		}
		Party party = new Party("demo", me, List.of(
				new Member(me, myName, true, null, now),
				new Member(EMBER, "Ember_Fox", true, emberAt, now),
				new Member(MOSS, "MossyCobble", true, mossAt, now),
				new Member(LUMEN, "Lumen", false, null, 0)));

		List<Ping> pings = List.of();
		if (positions && mc.player != null) {
			Vec3 eye = mc.player.getEyePosition();
			Vec3 look = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
			Vec3 p = eye.add(look.scale(26));
			pings = List.of(new Ping("demo-ping", EMBER, "Ember_Fox", server, dim,
					Math.floor(p.x), Math.floor(mc.player.getY()) - 1, Math.floor(p.z), "Diamond Ore", now + 60_000));
		}

		SocialState s = new SocialState(new Me(me, myName, false), friends,
				List.of(new Request(QUARTZ, "Quartzling", now - 120_000)),
				List.of(new Request(SABLE, "SableWing", now - 600_000)),
				List.of(), party, pings,
				List.of(new PartyInvite("demo-2", RAVEN, "Ravenmoor", now - 30_000)), null);

		Map<String, List<Message>> chats = Map.of(KESTREL, List.of(
				new Message("1", KESTREL, me, "you on tonight?", now - 600_000, false),
				new Message("2", me, KESTREL, "yeah, after dinner. bed wars?", now - 560_000, false),
				new Message("3", KESTREL, me, "doubles, I'll carry", now - 540_000, false),
				new Message("4", me, KESTREL, "sure you will", now - 500_000, false),
				new Message("5", KESTREL, me, "I'm in a lobby on hypixel now, join when you're ready and bring the good pickaxe", now - 60_000, false),
				new Message("6", KESTREL, me, "and say hi to Ember", now - 30_000, false)));
		List<PartyMessage> partyChat = List.of(
				new PartyMessage("7", EMBER, "Ember_Fox", "found the stronghold, follow my marker", now - 90_000, false),
				new PartyMessage("8", me, myName, "on my way", now - 80_000, false),
				new PartyMessage("9", MOSS, "MossyCobble", "bringing eyes of ender", now - 40_000, false));
		SocialClient.demoInstall(s, chats, partyChat, Map.of(KESTREL, 2));
	}
}
