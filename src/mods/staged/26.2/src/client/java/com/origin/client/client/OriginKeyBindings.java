package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class OriginKeyBindings {
	// 26.2: KeyMapping categories are Identifier-keyed records registered via
	// KeyMapping.Category.register (was a plain translation-key String).
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("originclient", "main"));

	public static KeyMapping openModMenu;
	public static KeyMapping zoom;
	public static KeyMapping freelook;
	public static KeyMapping copyCoords;

	private OriginKeyBindings() {
	}

	public static void register() {
		openModMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.open_mod_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				CATEGORY));

		zoom = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.zoom",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				CATEGORY));

		freelook = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.freelook",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));

		copyCoords = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.originclient.copy_coords",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));
	}
}
