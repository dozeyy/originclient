package com.origin.client.client.social;

import java.util.List;

/**
 * One immutable snapshot of everything the last /v1/sync returned
 * (docs/SOCIAL.md is the contract). The network thread builds a new snapshot
 * and swaps it in whole; render code only ever reads, so it never sees a
 * half-updated list. Every uuid is the service's form: undashed, lowercase.
 */
public record SocialState(Me me, List<Friend> friends, List<Request> incoming, List<Request> outgoing,
						  List<Request> blocked, Party party, List<Ping> pings, List<PartyInvite> invites, Warp warp) {

	public static final SocialState EMPTY = new SocialState(null, List.of(), List.of(), List.of(), List.of(),
			null, List.of(), List.of(), null);

	public record Me(String uuid, String name, boolean appearOffline) {
	}

	/** activity: "menu" | "singleplayer" | "server" (null while offline / appearing offline). */
	public record Friend(String uuid, String name, boolean online, String client, String activity,
						 String server, String version, long lastSeen) {
	}

	public record Request(String uuid, String name, long at) {
	}

	/** A DM; `pending` = sent from this game and not yet echoed back by the service. */
	public record Message(String id, String from, String to, String text, long at, boolean pending) {
	}

	/** A party member's live state (only for members on the caller's own server). */
	public record MemberState(String server, String dim, double x, double y, double z,
							  float health, float maxHealth) {
	}

	public record Member(String uuid, String name, boolean online, MemberState state, long stateAt) {
	}

	public record Party(String id, String leader, List<Member> members) {
		public Member member(String uuid) {
			for (Member m : members) {
				if (m.uuid().equals(uuid)) {
					return m;
				}
			}
			return null;
		}
	}

	public record PartyMessage(String id, String from, String name, String text, long at, boolean pending) {
	}

	/** A party ping; `localUntil` is when it disappears by THIS machine's clock. */
	public record Ping(String id, String from, String name, String server, String dim,
					   double x, double y, double z, String label, long localUntil) {
	}

	public record PartyInvite(String partyId, String from, String name, long at) {
	}

	/** The leader (`from`, `name`) asked the party to join them on `server` (Minecraft `version`). */
	public record Warp(String server, String version, long at, String from, String name) {
	}

	// ---- small read helpers the screens share ----

	public Friend friend(String uuid) {
		for (Friend f : friends) {
			if (f.uuid().equals(uuid)) {
				return f;
			}
		}
		return null;
	}

	public boolean inParty() {
		return party != null;
	}

	public boolean isLeader() {
		return party != null && me != null && me.uuid().equals(party.leader());
	}

	/** A name for any uuid we know (friend, party member, request), else null. */
	public String nameOf(String uuid) {
		if (uuid == null) {
			return null;
		}
		if (me != null && uuid.equals(me.uuid())) {
			return me.name();
		}
		Friend f = friend(uuid);
		if (f != null) {
			return f.name();
		}
		if (party != null) {
			Member m = party.member(uuid);
			if (m != null) {
				return m.name();
			}
		}
		for (Request r : incoming) {
			if (r.uuid().equals(uuid)) {
				return r.name();
			}
		}
		return null;
	}
}
