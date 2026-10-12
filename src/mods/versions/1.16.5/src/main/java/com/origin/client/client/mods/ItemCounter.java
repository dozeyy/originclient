package com.origin.client.client.mods;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
// Counting walks the local player's own inventory (main + hotbar + armour +
// offhand) -- purely client-side, the same data the inventory screen shows.
//
// 1.16.5: stacks are matched by getItem() == (ItemStack.is(Item) is not on every
// pre-1.20 version), food by Item.isEdible() (the FOOD data component is 1.20.5+),
// and the item registry is Registry.ITEM. An old "Wind Charges" pick (that item
// arrived in 1.21) resolves to nothing and is skipped.
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

		public Entry(String key, String label, Item icon, Predicate<ItemStack> matches) {
			this.key = key;
			this.label = label;
			this.icon = icon;
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
					&& java.util.Objects.equals(this.matches, other.matches);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(key, label, icon, matches);
		}
	}

	/** Multi-item picks, pinned first in the picker. key = what's stored. */
	public static final List<Entry> GROUPS = java.util.Arrays.asList(
			new Entry("Arrows", "All Arrows", Items.ARROW,
					s -> s.getItem() == Items.ARROW || s.getItem() == Items.SPECTRAL_ARROW || s.getItem() == Items.TIPPED_ARROW),
			new Entry("Golden Apples", "All Golden Apples", Items.GOLDEN_APPLE,
					s -> s.getItem() == Items.GOLDEN_APPLE || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE),
			new Entry("Splash Potions", "Splash + Lingering Potions", Items.SPLASH_POTION,
					s -> s.getItem() == Items.SPLASH_POTION || s.getItem() == Items.LINGERING_POTION),
			new Entry("Blocks", "All Blocks", Items.COBBLESTONE, s -> s.getItem() instanceof BlockItem),
			new Entry("Food", "All Food", Items.COOKED_BEEF, s -> s.getItem().isEdible()));

	/** What each group covers, for the picker's tooltip. */
	/** What each group covers, for the picker's tooltip. */
	public static final Map<String, String> GROUP_HINTS = new java.util.HashMap<>();
	// The old fixed list's single-item names -> the item they meant.
	private static final Map<String, String> LEGACY = new java.util.HashMap<>();

	static {
		GROUP_HINTS.put("Arrows", "Arrows, spectral and tipped arrows");
		GROUP_HINTS.put("Golden Apples", "Golden and enchanted golden apples");
		GROUP_HINTS.put("Splash Potions", "Every splash and lingering potion");
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
					out.add(LEGACY.getOrDefault(t, t));
				}
			}
		}
		return new ArrayList<>(out);
	}

	/** The token's counter, or null if it names nothing (e.g. an item a mod removed). */
	public static Entry resolve(String token) {
		for (Entry g : GROUPS) {
			if (g.key().equals(token)) {
				return g;
			}
		}
		ResourceLocation id = ResourceLocation.tryParse(token);
		if (id == null || !Registry.ITEM.containsKey(id)) {
			return null;
		}
		Item item = Registry.ITEM.get(id);
		return new Entry(token, new ItemStack(item).getHoverName().getString(), item, s -> s.getItem() == item);
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

	/** Total matching items across the whole inventory, armour and offhand included. */
	public static int count(Inventory inv, Entry e) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && e.matches().test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}
}
