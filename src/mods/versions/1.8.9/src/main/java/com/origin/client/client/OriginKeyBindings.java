package com.origin.client.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

// Origin's entries in Controls, same lang keys / defaults / category as every
// version. 1.8.9: KeyBinding(description, LWJGL2 code, category) registered
// through Forge's ClientRegistry; KEY_NONE (0) = unbound. KeyMapping's
// consumeClick() is isPressed() here, isDown() is isKeyDown().
public final class OriginKeyBindings {
	private static final String CATEGORY = "key.categories.originclient";

	public static KeyBinding openModMenu;
	public static KeyBinding zoom;
	public static KeyBinding freelook;
	public static KeyBinding copyCoords;
	public static KeyBinding waypointMenu;
	public static KeyBinding waypointCreate;
	public static KeyBinding waypointToggle;
	public static KeyBinding partyPing;

	private OriginKeyBindings() {
	}

	public static void register() {
		// Right Shift: the Origin menu. Consumed by ModMenuHooks.
		openModMenu = reg("key.originclient.open_mod_menu", Keyboard.KEY_RSHIFT);
		zoom = reg("key.originclient.zoom", Keyboard.KEY_C);
		freelook = reg("key.originclient.freelook", Keyboard.KEY_NONE);
		copyCoords = reg("key.originclient.copy_coords", Keyboard.KEY_NONE);
		// Waypoints — unbound by default (set in Controls). Menu opens the manager;
		// Create opens it on a fresh form; Toggle flips the whole system on/off.
		waypointMenu = reg("key.originclient.waypoint_menu", Keyboard.KEY_NONE);
		waypointCreate = reg("key.originclient.waypoint_create", Keyboard.KEY_NONE);
		waypointToggle = reg("key.originclient.waypoint_toggle", Keyboard.KEY_NONE);
		// Friends & Party: ping the block you look at for your party (docs/SOCIAL.md F).
		// Unbound by default, like the Waypoints keys; handled in social/Social.
		partyPing = reg("key.originclient.party_ping", Keyboard.KEY_NONE);
	}

	private static KeyBinding reg(String id, int lwjglKey) {
		KeyBinding k = new KeyBinding(id, lwjglKey, CATEGORY);
		try {
			ClientRegistry.registerKeyBinding(k);
		} catch (Throwable t) {
			// an unregistered binding still exists (never pressed): callers stay null-safe
			com.origin.client.OriginClient.LOGGER.warn("Origin key " + id + " failed to register", t);
		}
		return k;
	}
}
