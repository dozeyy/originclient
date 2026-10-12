package com.origin.client.client.mods;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Item Counter's catalogue: the item groups a player can pick in the mod's
// "Items" multi-select, each with the predicate that decides which stacks count
// toward it and the item drawn as its icon. Counting walks the local player's
// own inventory (main + hotbar + armour + offhand) -- purely client-side, the
// same data the inventory screen shows.
// ERA (pre-1.20.5): no data components -- "Food" is Item.isEdible(); and no
// Wind Charges entry (the item only exists from 1.21).
public final class ItemCounter {
	private ItemCounter() {
	}

	/** One countable group: its menu label, its HUD icon, and what matches it. */
	public record Entry(String label, Item icon, Predicate<ItemStack> matches) {
	}

	private static final List<Entry> ENTRIES = List.of(
			new Entry("Arrows", Items.ARROW,
					s -> s.is(Items.ARROW) || s.is(Items.SPECTRAL_ARROW) || s.is(Items.TIPPED_ARROW)),
			new Entry("Golden Apples", Items.GOLDEN_APPLE,
					s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)),
			new Entry("Ender Pearls", Items.ENDER_PEARL, s -> s.is(Items.ENDER_PEARL)),
			new Entry("Totems", Items.TOTEM_OF_UNDYING, s -> s.is(Items.TOTEM_OF_UNDYING)),
			new Entry("Splash Potions", Items.SPLASH_POTION,
					s -> s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)),
			new Entry("Potions", Items.POTION, s -> s.is(Items.POTION)),
			new Entry("Blocks", Items.COBBLESTONE, s -> s.getItem() instanceof BlockItem),
			new Entry("Food", Items.COOKED_BEEF, s -> s.getItem().isEdible()),
			new Entry("TNT", Items.TNT, s -> s.is(Items.TNT)),
			new Entry("Snowballs", Items.SNOWBALL, s -> s.is(Items.SNOWBALL)),
			new Entry("Eggs", Items.EGG, s -> s.is(Items.EGG)),
			new Entry("Fireworks", Items.FIREWORK_ROCKET, s -> s.is(Items.FIREWORK_ROCKET)),
			new Entry("XP Bottles", Items.EXPERIENCE_BOTTLE, s -> s.is(Items.EXPERIENCE_BOTTLE)),
			new Entry("Cobwebs", Items.COBWEB, s -> s.is(Items.COBWEB)),
			new Entry("Water Buckets", Items.WATER_BUCKET, s -> s.is(Items.WATER_BUCKET)),
			new Entry("Lava Buckets", Items.LAVA_BUCKET, s -> s.is(Items.LAVA_BUCKET)),
			new Entry("Obsidian", Items.OBSIDIAN, s -> s.is(Items.OBSIDIAN)),
			new Entry("Iron Ingots", Items.IRON_INGOT, s -> s.is(Items.IRON_INGOT)),
			new Entry("Gold Ingots", Items.GOLD_INGOT, s -> s.is(Items.GOLD_INGOT)),
			new Entry("Diamonds", Items.DIAMOND, s -> s.is(Items.DIAMOND)),
			new Entry("Emeralds", Items.EMERALD, s -> s.is(Items.EMERALD)));

	/** The multi-select's choice set, in catalogue order. Plain strings only, so
	 *  Mods' static registry can reference it without touching item classes. */
	public static final String[] CHOICES = {
			"Arrows", "Golden Apples", "Ender Pearls", "Totems", "Splash Potions", "Potions",
			"Blocks", "Food", "TNT", "Snowballs", "Eggs", "Fireworks",
			"XP Bottles", "Cobwebs", "Water Buckets", "Lava Buckets", "Obsidian",
			"Iron Ingots", "Gold Ingots", "Diamonds", "Emeralds"};

	/** The groups the player picked, in the order they picked them. */
	public static List<Entry> selected() {
		List<Entry> out = new ArrayList<>();
		String csv = Mods.mode("itemcounter", "items");
		if (csv == null || csv.isEmpty()) {
			return out;
		}
		for (String raw : csv.split(",")) {
			String label = raw.trim();
			for (Entry e : ENTRIES) {
				if (e.label().equals(label)) {
					out.add(e);
					break;
				}
			}
		}
		return out;
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
