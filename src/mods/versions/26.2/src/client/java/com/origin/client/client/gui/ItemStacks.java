package com.origin.client.client.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * ItemStack construction that can't throw.
 *
 * <p>PER-VERSION DELTA (26.x): an item's data components are bound only once a
 * world's registries are loaded, so {@code new ItemStack(item)} on the title
 * screen throws "Components not bound yet" (found by 26.2 dev shots: the Right
 * Shift screen's Cooldowns preview took the whole HUD editor down). Every Origin
 * call site that may run out of a world builds stacks through {@link #of}, which
 * returns {@link ItemStack#EMPTY} (draws nothing) until components exist.
 */
public final class ItemStacks {
	private static boolean bound = false;

	private ItemStacks() {
	}

	/** A one-item stack, or EMPTY while item components aren't bound (no world yet). */
	public static ItemStack of(Item item) {
		try {
			ItemStack s = new ItemStack(item);
			bound = true;
			return s;
		} catch (Throwable notBound) {
			return ItemStack.EMPTY;
		}
	}

	/** True once item stacks can be built (a world's registries are loaded). */
	public static boolean ready() {
		if (!bound) {
			of(net.minecraft.world.item.Items.STONE);
		}
		return bound;
	}
}
