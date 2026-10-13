package com.origin.client.client.mods;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// Item Counter's catalogue. The player picks what to count in the shared item
// grid (gui/ItemCounterPickerScreen, the same layout as Item Size): any single
// item, or one of the GROUPS pinned at the top of it -- the picks that cover
// several items at once (every kind of arrow, both golden apples, ...).
//
// Stored in the "items" option as CSV tokens, in the order picked: a group's
// key ("Arrows") or an item's registry id ("minecraft:ender_pearl"). Before
// 2026-10-12 the picker was a fixed list of named groups; the single-item names
// from then are mapped to their item id (LEGACY) so old picks keep counting.
//
// Counting walks the local player's own inventory (main + hotbar + armour) --
// purely client-side, the same data the inventory screen shows.
//
// 1.8.9: the registry is Item.itemRegistry; an empty slot is null (stackSize
// counts); there is no offhand, no spectral/tipped arrow, no lingering potion --
// a splash potion is the one potion item with the splash bit in its metadata
// (ItemPotion.isSplash) -- and the enchanted golden apple is golden_apple:1.
// Settings Sync carries picks made on newer versions, so the few item ids that
// were renamed after 1.8.9 are aliased (MODERN_IDS); picks of items 1.8.9 lacks
// (totems, wind charges, ...) resolve to nothing and are skipped.
public final class ItemCounter {
	private ItemCounter() {
	}

	/** One counter: its name, its HUD icon, and what matches it. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Entry {
		private final String key;
		private final String label;
		private final Item icon;
		private final Predicate<ItemStack> matches;
		private final int iconMeta;

		public Entry(String key, String label, Item icon, Predicate<ItemStack> matches) {
			this(key, label, icon, 0, matches);
		}

		/** 1.8.9: an icon variant by metadata (a splash potion's bottle, not a water bottle). */
		public Entry(String key, String label, Item icon, int iconMeta, Predicate<ItemStack> matches) {
			this.key = key;
			this.label = label;
			this.icon = icon;
			this.iconMeta = iconMeta;
			this.matches = matches;
		}

		public String key() {
			return this.key;
		}

		public String label() {
			return this.label;
		}

		public Item icon() {
			return this.icon;
		}

		/** 1.8.9: the icon as a stack with its metadata (what the HUD draws). */
		public ItemStack iconStack() {
			return new ItemStack(this.icon, 1, this.iconMeta);
		}

		public Predicate<ItemStack> matches() {
			return this.matches;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Entry)) {
				return false;
			}
			Entry other = (Entry) obj;
			return java.util.Objects.equals(this.key, other.key)
					&& java.util.Objects.equals(this.label, other.label)
					&& java.util.Objects.equals(this.icon, other.icon)
					&& this.iconMeta == other.iconMeta
					&& java.util.Objects.equals(this.matches, other.matches);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(key, label, icon, iconMeta, matches);
		}
	}

	/** A splash potion's metadata bit (ItemPotion.isSplash), used for the group's icon. */
	private static final int SPLASH_META = 16384;

	/** Multi-item picks, pinned first in the picker. key = what's stored. */
	public static final List<Entry> GROUPS = java.util.Arrays.asList(
			new Entry("Arrows", "All Arrows", Items.arrow,
					s -> s.getItem() == Items.arrow),
			new Entry("Golden Apples", "All Golden Apples", Items.golden_apple,
					s -> s.getItem() == Items.golden_apple),
			new Entry("Splash Potions", "Splash Potions", Items.potionitem, SPLASH_META,
					s -> s.getItem() instanceof ItemPotion && ItemPotion.isSplash(s.getMetadata())),
			new Entry("Blocks", "All Blocks", Item.getItemFromBlock(net.minecraft.init.Blocks.cobblestone),
					s -> s.getItem() instanceof ItemBlock),
			new Entry("Food", "All Food", Items.cooked_beef, s -> s.getItem() instanceof ItemFood));

	/** What each group covers, for the picker's tooltip. */
	public static final Map<String, String> GROUP_HINTS = new java.util.HashMap<>();
	// The old fixed list's single-item names -> the item they meant.
	private static final Map<String, String> LEGACY = new java.util.HashMap<>();
	// 1.8.9: ids renamed after 1.8.9 (the flattening) -> the 1.8.9 id. Only items
	// a player would plausibly count; the rest simply resolve to nothing.
	private static final Map<String, String> MODERN_IDS = new java.util.HashMap<>();

	static {
		GROUP_HINTS.put("Arrows", "Every arrow (1.8.9 has one kind)");
		GROUP_HINTS.put("Golden Apples", "Golden and enchanted golden apples");
		GROUP_HINTS.put("Splash Potions", "Every splash potion");
		GROUP_HINTS.put("Blocks", "Every placeable block");
		GROUP_HINTS.put("Food", "Everything you can eat");
		LEGACY.put("Ender Pearls", "minecraft:ender_pearl");
		LEGACY.put("Totems", "minecraft:totem_of_undying");
		LEGACY.put("Potions", "minecraft:potion");
		LEGACY.put("TNT", "minecraft:tnt");
		LEGACY.put("Snowballs", "minecraft:snowball");
		LEGACY.put("Eggs", "minecraft:egg");
		LEGACY.put("Wind Charges", "minecraft:wind_charge");
		LEGACY.put("Fireworks", "minecraft:firework_rocket");
		LEGACY.put("XP Bottles", "minecraft:experience_bottle");
		LEGACY.put("Cobwebs", "minecraft:cobweb");
		LEGACY.put("Water Buckets", "minecraft:water_bucket");
		LEGACY.put("Lava Buckets", "minecraft:lava_bucket");
		LEGACY.put("Obsidian", "minecraft:obsidian");
		LEGACY.put("Iron Ingots", "minecraft:iron_ingot");
		LEGACY.put("Gold Ingots", "minecraft:gold_ingot");
		LEGACY.put("Diamonds", "minecraft:diamond");
		LEGACY.put("Emeralds", "minecraft:emerald");
		MODERN_IDS.put("minecraft:firework_rocket", "minecraft:fireworks");
		MODERN_IDS.put("minecraft:cobweb", "minecraft:web");
		MODERN_IDS.put("minecraft:enchanted_golden_apple", "minecraft:golden_apple");
		MODERN_IDS.put("minecraft:splash_potion", "minecraft:potion");
		MODERN_IDS.put("minecraft:oak_planks", "minecraft:planks");
		MODERN_IDS.put("minecraft:white_wool", "minecraft:wool");
	}

	public static final String DEFAULTS = "Arrows,Golden Apples,minecraft:ender_pearl";

	// selected() runs every frame (HUD size + draw): parse only when the CSV changes.
	private static String cachedCsv = null;
	private static List<Entry> cached = java.util.Collections.emptyList();

	/** The stored picks as tokens, legacy names upgraded, duplicates dropped. */
	public static List<String> tokens(String csv) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		if (csv != null) {
			for (String raw : csv.split(",")) {
				String t = raw.trim();
				if (!t.isEmpty()) {
					String v = LEGACY.get(t);
					out.add(v != null ? v : t);
				}
			}
		}
		return new ArrayList<>(out);
	}

	/** The token's counter, or null if it names nothing (e.g. an item 1.8.9 doesn't have). */
	public static Entry resolve(String token) {
		for (Entry g : GROUPS) {
			if (g.key().equals(token)) {
				return g;
			}
		}
		if (token == null || token.isEmpty() || token.indexOf(':') < 0) {
			return null;
		}
		String id = MODERN_IDS.containsKey(token) ? MODERN_IDS.get(token) : token;
		final Item item;
		try {
			item = Item.itemRegistry.getObject(new ResourceLocation(id));
		} catch (Throwable t) {
			return null;
		}
		if (item == null) {
			return null;
		}
		String label;
		try {
			label = new ItemStack(item).getDisplayName();
		} catch (Throwable t) {
			label = id;
		}
		// The stored key stays the token as picked, so the picker's selection matches it.
		return new Entry(token, label, item, s -> s.getItem() == item);
	}

	/** The picks, in the order the player picked them. */
	public static List<Entry> selected() {
		String csv = Mods.mode("itemcounter", "items");
		if (!csv.equals(cachedCsv)) {
			List<Entry> out = new ArrayList<>();
			for (String t : tokens(csv)) {
				Entry e = resolve(t);
				if (e != null) {
					out.add(e);
				}
			}
			cached = out;
			cachedCsv = csv;
		}
		return cached;
	}

	/** Total matching items across the whole inventory, armour included. */
	public static int count(InventoryPlayer inv, Entry e) {
		int n = 0;
		for (int i = 0; i < inv.getSizeInventory(); i++) {
			ItemStack s = inv.getStackInSlot(i);
			if (s != null && s.stackSize > 0 && e.matches().test(s)) {
				n += s.stackSize;
			}
		}
		return n;
	}
}
