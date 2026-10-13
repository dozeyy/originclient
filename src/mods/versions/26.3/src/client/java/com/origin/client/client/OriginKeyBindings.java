package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class OriginKeyBindings {
	// 1.21.11: KeyMapping categories are typed objects registered by id.
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("originclient", "main"));

	public static KeyMapping openModMenu;
	public static KeyMapping zoom;
	public static KeyMapping freelook;
	public static KeyMapping copyCoords;
	public static KeyMapping waypointMenu;
	public static KeyMapping waypointCreate;
	public static KeyMapping waypointToggle;
	public static KeyMapping partyPing;

	private OriginKeyBindings() {
	}

	public static void register() {
		openModMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.open_mod_menu",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_RSHIFT,
				CATEGORY));

		zoom = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.zoom",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_C,
				CATEGORY));

		freelook = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.freelook",
				InputConstants.Type.KEYBOARD,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));

		copyCoords = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.copy_coords",
				InputConstants.Type.KEYBOARD,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));

		// Waypoints — unbound by default (set in Controls). Menu opens the manager;
		// Toggle flips the whole system on/off. (No quick-create: waypoints are only
		// made through the Create Waypoint menu.)
		waypointMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.waypoint_menu", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		waypointCreate = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.waypoint_create", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
		waypointToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.waypoint_toggle", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

		// Friends & Party: ping the block you look at for your party (docs/SOCIAL.md F).
		// Unbound by default, like the Waypoints keys; handled in social/Social.
		partyPing = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.party_ping", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
	}
}
