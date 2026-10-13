package com.origin.client.client.cosmetics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every cosmetic the mod can draw. The ids are the contract shared with the
 * store (website/js/store.js ITEMS), the Tebex package commands and the
 * delivery service (services/cosmetics) - see docs/STORE.md. Adding an item:
 * add it here AND its art (capes: tools/cosmetics/export-capes.mjs; head
 * items: {@link HeadModels}).
 */
public final class Cosmetics {
	private Cosmetics() {
	}

	public enum Slot { CAPE, HEAD }

	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Item {
		private final String id;
		private final String name;
		private final Slot slot;

		public Item(String id, String name, Slot slot) {
			this.id = id;
			this.name = name;
			this.slot = slot;
		}

		public String id() {
			return this.id;
		}

		public String name() {
			return this.name;
		}

		public Slot slot() {
			return this.slot;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Item)) {
				return false;
			}
			Item other = (Item) obj;
			return java.util.Objects.equals(this.id, other.id)
					&& java.util.Objects.equals(this.name, other.name)
					&& java.util.Objects.equals(this.slot, other.slot);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(id, name, slot);
		}
	}

	public static final List<Item> ALL = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			new Item("cape-orbit", "Orbit Cape", Slot.CAPE),
			new Item("cape-glitch", "Glitch Cape", Slot.CAPE),
			new Item("cape-inferno", "Inferno Cape", Slot.CAPE),
			new Item("cape-aurora", "Aurora Cape", Slot.CAPE),
			new Item("cape-starfall", "Starfall Cape", Slot.CAPE),
			new Item("cape-origin", "Origin Cape", Slot.CAPE),
			new Item("cape-golden", "Golden Hour Cape", Slot.CAPE),
			new Item("cape-frost", "Frost Cape", Slot.CAPE),
			new Item("cape-void", "Void Cape", Slot.CAPE),
			new Item("halo-orbit", "Orbit Halo", Slot.HEAD),
			new Item("crown-pixel", "Pixel Crown", Slot.HEAD),
			new Item("halo-gold", "Golden Halo", Slot.HEAD)));

	private static final Map<String, Item> BY_ID = new LinkedHashMap<>();

	static {
		for (Item i : ALL) {
			BY_ID.put(i.id(), i);
		}
	}

	/** The item for an id, or null for anything this build can't draw (e.g. an
	 *  item added to the store after this release): such ids are ignored. */
	public static Item get(String id) {
		return id == null ? null : BY_ID.get(id);
	}

	/** Items that move on their own (website + in game); the Animated tab shows these. */
	private static final Set<String> ANIMATED = new java.util.HashSet<>(java.util.Arrays.asList(
			"cape-orbit", "cape-glitch", "cape-inferno", "cape-aurora", "cape-starfall", "halo-orbit"));

	public static boolean isAnimated(String id) {
		return ANIMATED.contains(id);
	}

	public static final String STORE_URL = "https://originclient.org/store.html";

	/** Where the mod learns the delivery service's address ({"api": "..."}).
	 *  An empty api means cosmetics are switched off; changing it needs no mod
	 *  release. */
	public static final String SERVICE_POINTER = "https://originclient.org/cosmetics/service.json";
}
