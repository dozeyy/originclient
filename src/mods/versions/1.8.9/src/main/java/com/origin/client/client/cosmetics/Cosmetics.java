package com.origin.client.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * What the mod knows about cosmetics, as DATA (cosmetics v2, cosmetics/FORMAT.md):
 * the list of items comes from assets/originclient/cosmetics/catalog.json and
 * each item's art from cosmetics/&lt;id&gt;/item.json + texture.png, both read by
 * {@link CosmeticCatalog}. Nothing here names an item: adding one is an art-
 * pipeline run (node tools/cosmetics/build.mjs), never a code change.
 *
 * The ids are the contract shared with the store website, the store service
 * (services/cosmetics) and the generated catalog - ids are forever.
 *
 * This class keeps the {@link Item} (the "meta" every item.json / catalog.json
 * entry carries), the {@link Slot} enum, and the static facade the rest of the
 * mod already calls ({@link #get}, {@link #all}, {@link #isPlusOnly}).
 */
public final class Cosmetics {
	private Cosmetics() {
	}

	/** One thing a player can wear per slot. THEME is local-only (it recolours your own menus). */
	public enum Slot {
		CAPE("cape"), HEAD("head"), FACE("face"), BACK("back"), THEME("theme");

		/** The key this slot has in the service's JSON and in item.json ("slot"). */
		public final String key;

		Slot(String key) {
			this.key = key;
		}

		public static Slot fromKey(String key) {
			if (key != null) {
				for (Slot s : values()) {
					if (s.key.equals(key)) {
						return s;
					}
				}
			}
			return null;
		}

		/** FORMAT.md: kind -> slot (cape; wings, pack -> back; mask, bandana -> face; hat, halo, crown -> head; theme). */
		public static Slot ofKind(String kind) {
			if (kind == null) {
				return null;
			}
			// 1.8.9: Java 8 has no switch expressions, a statement switch on the string
			switch (kind) {
				case "cape":
					return CAPE;
				case "wings":
				case "pack":
					return BACK;
				case "mask":
				case "bandana":
					return FACE;
				case "hat":
				case "halo":
				case "crown":
					return HEAD;
				case "theme":
					return THEME;
				default:
					return null;
			}
		}
	}

	/**
	 * The item meta (FORMAT.md "Item meta"). Fields the service/store need
	 * (cents, onSale, blurb) are kept so the screen can show them; the mod draws
	 * from {@link #slot}, {@link #hidesCape} and {@link #capeOffset}.
	 *
	 * 1.8.9: Java 8 has no records, a final value class with the record's accessors.
	 */
	public static final class Item {
		private final String id, name, kind, blurb;
		private final Slot slot;
		private final int cents;
		private final boolean animated, plusOnly, onSale, hidesCape;
		private final float capeOffset;

		public Item(String id, String name, String kind, Slot slot, int cents, String blurb, boolean animated,
				boolean plusOnly, boolean onSale, boolean hidesCape, float capeOffset) {
			this.id = id;
			this.name = name;
			this.kind = kind;
			this.slot = slot;
			this.cents = cents;
			this.blurb = blurb;
			this.animated = animated;
			this.plusOnly = plusOnly;
			this.onSale = onSale;
			this.hidesCape = hidesCape;
			this.capeOffset = capeOffset;
		}

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		public String kind() {
			return kind;
		}

		public Slot slot() {
			return slot;
		}

		public int cents() {
			return cents;
		}

		public String blurb() {
			return blurb;
		}

		public boolean animated() {
			return animated;
		}

		public boolean plusOnly() {
			return plusOnly;
		}

		public boolean onSale() {
			return onSale;
		}

		public boolean hidesCape() {
			return hidesCape;
		}

		public float capeOffset() {
			return capeOffset;
		}

		/** Parses one meta object; null if it has no usable id/kind (that item is then absent). Fail-soft. */
		static Item fromJson(JsonObject o) {
			try {
				String id = str(o, "id", null);
				if (id == null || !id.matches("[a-z0-9-]+")) {
					return null;
				}
				String kind = str(o, "kind", null);
				if (kind == null) {
					int dash = id.indexOf('-');
					kind = dash > 0 ? id.substring(0, dash) : null;
				}
				Slot slot = Slot.fromKey(str(o, "slot", null));
				if (slot == null) {
					slot = Slot.ofKind(kind);
				}
				if (slot == null) {
					return null;
				}
				return new Item(id, str(o, "name", id), kind != null ? kind : slot.key.toLowerCase(Locale.ROOT),
						slot, (int) num(o, "cents", 0), str(o, "blurb", ""), bool(o, "animated", false),
						bool(o, "plusOnly", false), bool(o, "onSale", true), bool(o, "hidesCape", false),
						(float) num(o, "capeOffset", 0));
			} catch (Exception e) {
				return null;
			}
		}
	}

	// ---- tiny fail-soft JSON readers, shared by the cosmetics parsers ----

	static String str(JsonObject o, String key, String def) {
		try {
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	static double num(JsonObject o, String key, double def) {
		try {
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	static boolean bool(JsonObject o, String key, boolean def) {
		try {
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
		} catch (Exception ex) {
			return def;
		}
	}

	// ---- the facade ----

	/** Every item in the catalog, in shop order (empty if the catalog is missing or broken). */
	public static List<Item> all() {
		return CosmeticCatalog.items();
	}

	/** The item for an id, or null for anything this build can't draw (e.g. an item added to the store after this release): such ids are ignored. */
	public static Item get(String id) {
		return CosmeticCatalog.item(id);
	}

	/** Items that move on their own (website + in game). */
	public static boolean isAnimated(String id) {
		Item i = get(id);
		return i != null && i.animated();
	}

	/** Members-only items (never sold): the service grants them while the player is an Origin+ member. */
	public static boolean isPlusOnly(String id) {
		Item i = get(id);
		return i != null && i.plusOnly();
	}

	public static final String STORE_URL = "https://originclient.org/store.html";

	/** Where the mod learns the delivery service's address ({"api": "..."}).
	 *  An empty api means cosmetics are switched off; changing it needs no mod
	 *  release. */
	public static final String SERVICE_POINTER = "https://originclient.org/cosmetics/service.json";
}
