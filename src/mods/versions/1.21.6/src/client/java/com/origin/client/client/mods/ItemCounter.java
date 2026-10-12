package com.origin.client.client.mods;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
public final class ItemCounter {
	private ItemCounter() {
	}

	/** One counter: its name, its HUD icon, and what matches it. */
	public record Entry(String key, String label, Item icon, Predicate<ItemStack> matches) {
	}

	/** Multi-item picks, pinned first in the picker. key = what's stored. */
	public static final List<Entry> GROUPS = List.of(
			new Entry("Arrows", "All Arrows", Items.ARROW,
					s -> s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)),
			new Entry("Golden Apples", "All Golden Apples", Items.GOLDEN_APPLE,
					s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)),
			new Entry("Splash Potions", "Splash + Lingering Potions", Items.SPLASH_POTION,
					s -> s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)),
			new Entry("Blocks", "All Blocks", Items.COBBLESTONE, s -> s.getItem() instanceof BlockItem),
			new Entry("Food", "All Food", Items.COOKED_BEEF, s -> s.has(DataComponents.FOOD)));

	/** What each group covers, for the picker's tooltip. */
	public static final Map<String, String> GROUP_HINTS = Map.of(
			"Arrows", "Arrows, spectral and tipped arrows",
			"Golden Apples", "Golden and enchanted golden apples",
			"Splash Potions", "Every splash and lingering potion",
			"Blocks", "Every placeable block",
			"Food", "Everything you can eat");

	// The old fixed list's single-item names -> the item they meant.
	private static final Map<String, String> LEGACY = Map.ofEntries(
			Map.entry("Ender Pearls", "minecraft:ender_pearl"),
			Map.entry("Totems", "minecraft:totem_of_undying"),
			Map.entry("Potions", "minecraft:potion"),
			Map.entry("TNT", "minecraft:tnt"),
			Map.entry("Snowballs", "minecraft:snowball"),
			Map.entry("Eggs", "minecraft:egg"),
			Map.entry("Wind Charges", "minecraft:wind_charge"),
			Map.entry("Fireworks", "minecraft:firework_rocket"),
			Map.entry("XP Bottles", "minecraft:experience_bottle"),
			Map.entry("Cobwebs", "minecraft:cobweb"),
			Map.entry("Water Buckets", "minecraft:water_bucket"),
			Map.entry("Lava Buckets", "minecraft:lava_bucket"),
			Map.entry("Obsidian", "minecraft:obsidian"),
			Map.entry("Iron Ingots", "minecraft:iron_ingot"),
			Map.entry("Gold Ingots", "minecraft:gold_ingot"),
			Map.entry("Diamonds", "minecraft:diamond"),
			Map.entry("Emeralds", "minecraft:emerald"));

	public static final String DEFAULTS = "Arrows,Golden Apples,minecraft:ender_pearl";

	// selected() runs every frame (HUD size + draw): parse only when the CSV changes.
	private static String cachedCsv = null;
	private static List<Entry> cached = List.of();

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
		// 1.21.2+: Registry.get(id) returns a holder; getOptional is the plain item.
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null) {
			return null;
		}
		return new Entry(token, new ItemStack(item).getHoverName().getString(), item, s -> s.is(item));
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
