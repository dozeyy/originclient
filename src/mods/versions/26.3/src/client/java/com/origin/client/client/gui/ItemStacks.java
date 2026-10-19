package com.origin.client.client.gui;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;

/**
 * ItemStack construction that can't throw.
 *
 * <p>PER-VERSION DELTA (26.x): an item's data components are built from the
 * world's registries (DataComponentInitializers.build(HolderLookup.Provider)) and
 * bound to its holder only once a world loads, so {@code new ItemStack(item)} on
 * the title screen throws "Components not bound yet" (Holder.Reference.components()).
 * Every Origin call site that may run out of a world builds stacks through
 * {@link #of}.
 *
 * <p>Out of a world, {@link #of} returns a DISPLAY stack instead of EMPTY, so
 * previews drawn from the title screen (the Right Shift HUD editor's Cooldowns,
 * Armor, Bedwars, Item Counter widgets) show their item icons like 1.21.1 does.
 * It is built through ItemStack's private (holder, count, PatchedDataComponentMap)
 * constructor, which only stores its arguments (no components() call), over a map
 * holding just what drawing reads: ITEM_MODEL (the item's own id, the vanilla
 * default) and ITEM_NAME. It is for drawing only; game logic never runs out of a
 * world, and in a world {@link #of} returns real stacks again.
 */
public final class ItemStacks {
	private static boolean bound = false;
	private static Constructor<ItemStack> raw;
	private static boolean rawFailed = false;

	private ItemStacks() {
	}

	/** A one-item stack; out of a world, a draw-only display stack (EMPTY only if even that fails). */
	public static ItemStack of(Item item) {
		try {
			ItemStack s = new ItemStack(item);
			bound = true;
			return s;
		} catch (Throwable notBound) {
			return display(item);
		}
	}

	/** True once real item stacks can be built (a world's registries are loaded). */
	public static boolean ready() {
		if (!bound) {
			try {
				new ItemStack(net.minecraft.world.item.Items.STONE);
				bound = true;
			} catch (Throwable notBound) {
				// still on the title screen
			}
		}
		return bound;
	}

	private static ItemStack display(Item item) {
		if (rawFailed) {
			return ItemStack.EMPTY;
		}
		try {
			if (raw == null) {
				// No names in the lookup, only parameter types: works the same in dev and
				// production (26.x is unobfuscated anyway).
				raw = ItemStack.class.getDeclaredConstructor(Holder.class, int.class, PatchedDataComponentMap.class);
				raw.setAccessible(true);
			}
			DataComponentMap proto = DataComponentMap.builder()
					.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(item))
					.set(DataComponents.ITEM_NAME, Component.translatable(item.getDescriptionId()))
					.build();
			return raw.newInstance(item.builtInRegistryHolder(), 1, new PatchedDataComponentMap(proto));
		} catch (Throwable t) {
			rawFailed = true;
			com.origin.client.OriginClient.LOGGER.warn("Display item stacks unavailable; title-screen previews draw no icons", t);
			return ItemStack.EMPTY;
		}
	}
}
