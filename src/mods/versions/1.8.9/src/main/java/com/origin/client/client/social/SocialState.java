package com.origin.client.client.social;

import java.util.Collections;
import java.util.List;

/**
 * One immutable snapshot of everything the last /v1/sync returned
 * (docs/SOCIAL.md is the contract). The network thread builds a new snapshot
 * and swaps it in whole; render code only ever reads, so it never sees a
 * half-updated list. Every uuid is the service's form: undashed, lowercase.
 */
// Java 8 has no records: a final value class with the record's constructor,
// accessors and equals/hashCode.
public final class SocialState {
	private final Me me;
	private final List<Friend> friends;
	private final List<Request> incoming;
	private final List<Request> outgoing;
	private final List<Request> blocked;
	private final Party party;
	private final List<Ping> pings;
	private final List<PartyInvite> invites;
	private final Warp warp;

	public SocialState(Me me, List<Friend> friends, List<Request> incoming, List<Request> outgoing, List<Request> blocked, Party party, List<Ping> pings, List<PartyInvite> invites, Warp warp) {
		this.me = me;
		this.friends = friends;
		this.incoming = incoming;
		this.outgoing = outgoing;
		this.blocked = blocked;
		this.party = party;
		this.pings = pings;
		this.invites = invites;
		this.warp = warp;
	}

	public Me me() {
		return this.me;
	}

	public List<Friend> friends() {
		return this.friends;
	}

	public List<Request> incoming() {
		return this.incoming;
	}

	public List<Request> outgoing() {
		return this.outgoing;
	}

	public List<Request> blocked() {
		return this.blocked;
	}

	public Party party() {
		return this.party;
	}

	public List<Ping> pings() {
		return this.pings;
	}

	public List<PartyInvite> invites() {
		return this.invites;
	}

	public Warp warp() {
		return this.warp;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof SocialState)) {
			return false;
		}
		SocialState other = (SocialState) obj;
		return java.util.Objects.equals(this.me, other.me)
				&& java.util.Objects.equals(this.friends, other.friends)
				&& java.util.Objects.equals(this.incoming, other.incoming)
				&& java.util.Objects.equals(this.outgoing, other.outgoing)
				&& java.util.Objects.equals(this.blocked, other.blocked)
				&& java.util.Objects.equals(this.party, other.party)
				&& java.util.Objects.equals(this.pings, other.pings)
				&& java.util.Objects.equals(this.invites, other.invites)
				&& java.util.Objects.equals(this.warp, other.warp);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(me, friends, incoming, outgoing, blocked, party, pings, invites, warp);
	}

	public static final SocialState EMPTY = new SocialState(null, Collections.<Friend>emptyList(),
			Collections.<Request>emptyList(), Collections.<Request>emptyList(), Collections.<Request>emptyList(),
			null, Collections.<Ping>emptyList(), Collections.<PartyInvite>emptyList(), null);

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Me {
		private final String uuid;
		private final String name;
		private final boolean appearOffline;

		public Me(String uuid, String name, boolean appearOffline) {
			this.uuid = uuid;
			this.name = name;
			this.appearOffline = appearOffline;
		}

		public String uuid() {
			return this.uuid;
		}

		public String name() {
			return this.name;
		}

		public boolean appearOffline() {
			return this.appearOffline;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Me)) {
				return false;
			}
			Me other = (Me) obj;
			return java.util.Objects.equals(this.uuid, other.uuid)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.appearOffline == other.appearOffline;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(uuid, name, appearOffline);
		}
	}

	/** activity: "menu" | "singleplayer" | "server" (null while offline / appearing offline). */
	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Friend {
		private final String uuid;
		private final String name;
		private final boolean online;
		private final String client;
		private final String activity;
		private final String server;
		private final String version;
		private final long lastSeen;

		public Friend(String uuid, String name, boolean online, String client, String activity, String server, String version, long lastSeen) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.client = client;
			this.activity = activity;
			this.server = server;
			this.version = version;
			this.lastSeen = lastSeen;
		}

		public String uuid() {
			return this.uuid;
		}

		public String name() {
			return this.name;
		}

		public boolean online() {
			return this.online;
		}

		public String client() {
			return this.client;
		}

		public String activity() {
			return this.activity;
		}

		public String server() {
			return this.server;
		}

		public String version() {
			return this.version;
		}

		public long lastSeen() {
			return this.lastSeen;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Friend)) {
				return false;
			}
			Friend other = (Friend) obj;
			return java.util.Objects.equals(this.uuid, other.uuid)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.online == other.online
					&& java.util.Objects.equals(this.client, other.client)
					&& java.util.Objects.equals(this.activity, other.activity)
					&& java.util.Objects.equals(this.server, other.server)
					&& java.util.Objects.equals(this.version, other.version)
					&& this.lastSeen == other.lastSeen;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(uuid, name, online, client, activity, server, version, lastSeen);
		}
	}

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Request {
		private final String uuid;
		private final String name;
		private final long at;

		public Request(String uuid, String name, long at) {
			this.uuid = uuid;
			this.name = name;
			this.at = at;
		}

		public String uuid() {
			return this.uuid;
		}

		public String name() {
			return this.name;
		}

		public long at() {
			return this.at;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Request)) {
				return false;
			}
			Request other = (Request) obj;
			return java.util.Objects.equals(this.uuid, other.uuid)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.at == other.at;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(uuid, name, at);
		}
	}

	/** A DM; `pending` = sent from this game and not yet echoed back by the service. */
	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Message {
		private final String id;
		private final String from;
		private final String to;
		private final String text;
		private final long at;
		private final boolean pending;

		public Message(String id, String from, String to, String text, long at, boolean pending) {
			this.id = id;
			this.from = from;
			this.to = to;
			this.text = text;
			this.at = at;
			this.pending = pending;
		}

		public String id() {
			return this.id;
		}

		public String from() {
			return this.from;
		}

		public String to() {
			return this.to;
		}

		public String text() {
			return this.text;
		}

		public long at() {
			return this.at;
		}

		public boolean pending() {
			return this.pending;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Message)) {
				return false;
			}
			Message other = (Message) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.from, other.from)
					&& java.util.Objects.equals(this.to, other.to)
					&& java.util.Objects.equals(this.text, other.text)
					&& this.at == other.at
					&& this.pending == other.pending;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, from, to, text, at, pending);
		}
	}

	/** A party member's live state (only for members on the caller's own server). */
	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class MemberState {
		private final String server;
		private final String dim;
		private final double x;
		private final double y;
		private final double z;
		private final float health;
		private final float maxHealth;

		public MemberState(String server, String dim, double x, double y, double z, float health, float maxHealth) {
			this.server = server;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.health = health;
			this.maxHealth = maxHealth;
		}

		public String server() {
			return this.server;
		}

		public String dim() {
			return this.dim;
		}

		public double x() {
			return this.x;
		}

		public double y() {
			return this.y;
		}

		public double z() {
			return this.z;
		}

		public float health() {
			return this.health;
		}

		public float maxHealth() {
			return this.maxHealth;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof MemberState)) {
				return false;
			}
			MemberState other = (MemberState) obj;
			return java.util.Objects.equals(this.server, other.server)
					&& java.util.Objects.equals(this.dim, other.dim)
					&& Double.compare(this.x, other.x) == 0
					&& Double.compare(this.y, other.y) == 0
					&& Double.compare(this.z, other.z) == 0
					&& Float.compare(this.health, other.health) == 0
					&& Float.compare(this.maxHealth, other.maxHealth) == 0;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(server, dim, x, y, z, health, maxHealth);
		}
	}

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Member {
		private final String uuid;
		private final String name;
		private final boolean online;
		private final MemberState state;
		private final long stateAt;

		public Member(String uuid, String name, boolean online, MemberState state, long stateAt) {
			this.uuid = uuid;
			this.name = name;
			this.online = online;
			this.state = state;
			this.stateAt = stateAt;
		}

		public String uuid() {
			return this.uuid;
		}

		public String name() {
			return this.name;
		}

		public boolean online() {
			return this.online;
		}

		public MemberState state() {
			return this.state;
		}

		public long stateAt() {
			return this.stateAt;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Member)) {
				return false;
			}
			Member other = (Member) obj;
			return java.util.Objects.equals(this.uuid, other.uuid)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.online == other.online
					&& java.util.Objects.equals(this.state, other.state)
					&& this.stateAt == other.stateAt;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(uuid, name, online, state, stateAt);
		}
	}

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Party {
		private final String id;
		private final String leader;
		private final List<Member> members;

		public Party(String id, String leader, List<Member> members) {
			this.id = id;
			this.leader = leader;
			this.members = members;
		}

		public String id() {
			return this.id;
		}

		public String leader() {
			return this.leader;
		}

		public List<Member> members() {
			return this.members;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Party)) {
				return false;
			}
			Party other = (Party) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.leader, other.leader)
					&& java.util.Objects.equals(this.members, other.members);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, leader, members);
		}

		public Member member(String uuid) {
			for (Member m : members) {
				if (m.uuid().equals(uuid)) {
					return m;
				}
			}
			return null;
		}
	
	}

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class PartyMessage {
		private final String id;
		private final String from;
		private final String name;
		private final String text;
		private final long at;
		private final boolean pending;

		public PartyMessage(String id, String from, String name, String text, long at, boolean pending) {
			this.id = id;
			this.from = from;
			this.name = name;
			this.text = text;
			this.at = at;
			this.pending = pending;
		}

		public String id() {
			return this.id;
		}

		public String from() {
			return this.from;
		}

		public String name() {
			return this.name;
		}

		public String text() {
			return this.text;
		}

		public long at() {
			return this.at;
		}

		public boolean pending() {
			return this.pending;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof PartyMessage)) {
				return false;
			}
			PartyMessage other = (PartyMessage) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.from, other.from)
					&& java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.text, other.text)
					&& this.at == other.at
					&& this.pending == other.pending;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, from, name, text, at, pending);
		}
	}

	/** A party ping; `localUntil` is when it disappears by THIS machine's clock. */
	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Ping {
		private final String id;
		private final String from;
		private final String name;
		private final String server;
		private final String dim;
		private final double x;
		private final double y;
		private final double z;
		private final String label;
		private final long localUntil;

		public Ping(String id, String from, String name, String server, String dim, double x, double y, double z, String label, long localUntil) {
			this.id = id;
			this.from = from;
			this.name = name;
			this.server = server;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.label = label;
			this.localUntil = localUntil;
		}

		public String id() {
			return this.id;
		}

		public String from() {
			return this.from;
		}

		public String name() {
			return this.name;
		}

		public String server() {
			return this.server;
		}

		public String dim() {
			return this.dim;
		}

		public double x() {
			return this.x;
		}

		public double y() {
			return this.y;
		}

		public double z() {
			return this.z;
		}

		public String label() {
			return this.label;
		}

		public long localUntil() {
			return this.localUntil;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Ping)) {
				return false;
			}
			Ping other = (Ping) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.from, other.from)
					&& java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.server, other.server)
					&& java.util.Objects.equals(this.dim, other.dim)
					&& Double.compare(this.x, other.x) == 0
					&& Double.compare(this.y, other.y) == 0
					&& Double.compare(this.z, other.z) == 0
					&& java.util.Objects.equals(this.label, other.label)
					&& this.localUntil == other.localUntil;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, from, name, server, dim, x, y, z, label, localUntil);
		}
	}

	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class PartyInvite {
		private final String partyId;
		private final String from;
		private final String name;
		private final long at;

		public PartyInvite(String partyId, String from, String name, long at) {
			this.partyId = partyId;
			this.from = from;
			this.name = name;
			this.at = at;
		}

		public String partyId() {
			return this.partyId;
		}

		public String from() {
			return this.from;
		}

		public String name() {
			return this.name;
		}

		public long at() {
			return this.at;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof PartyInvite)) {
				return false;
			}
			PartyInvite other = (PartyInvite) obj;
			return java.util.Objects.equals(this.partyId, other.partyId)
					&& java.util.Objects.equals(this.from, other.from)
					&& java.util.Objects.equals(this.name, other.name)
					&& this.at == other.at;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(partyId, from, name, at);
		}
	}

	/** The leader (`from`, `name`) asked the party to join them on `server` (Minecraft `version`). */
	// Java 8 has no records: a final value class with the record's constructor,
	// accessors and equals/hashCode.
	public static final class Warp {
		private final String server;
		private final String version;
		private final long at;
		private final String from;
		private final String name;

		public Warp(String server, String version, long at, String from, String name) {
			this.server = server;
			this.version = version;
			this.at = at;
			this.from = from;
			this.name = name;
		}

		public String server() {
			return this.server;
		}

		public String version() {
			return this.version;
		}

		public long at() {
			return this.at;
		}

		public String from() {
			return this.from;
		}

		public String name() {
			return this.name;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Warp)) {
				return false;
			}
			Warp other = (Warp) obj;
			return java.util.Objects.equals(this.server, other.server)
					&& java.util.Objects.equals(this.version, other.version)
					&& this.at == other.at
					&& java.util.Objects.equals(this.from, other.from)
					&& java.util.Objects.equals(this.name, other.name);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(server, version, at, from, name);
		}
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
