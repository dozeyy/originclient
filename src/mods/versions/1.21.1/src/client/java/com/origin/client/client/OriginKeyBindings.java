package com.origin.client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class OriginKeyBindings {
	private static final String CATEGORY = "key.categories.originclient";

	public static KeyMapping openModMenu;
	public static KeyMapping zoom;
	public static KeyMapping freelook;
	public static KeyMapping copyCoords;

	private OriginKeyBindings() {
	}

	public static void register() {
		openModMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.originclient.open_mod_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				CATEGORY));

		zoom = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.originclient.zoom",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				CATEGORY));

		freelook = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.originclient.freelook",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));

		copyCoords = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.originclient.copy_coords",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY));
	}
}
