package com.origin.client.input;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/** Origin's key bindings, matching the Fabric build's defaults. */
public final class OriginKeys {
    public static final String CATEGORY = "Origin Client";

    public static KeyBinding zoom;
    public static KeyBinding freelook;
    public static KeyBinding menu;

    private OriginKeys() {
    }

    public static void register() {
        zoom = new KeyBinding("Zoom", Keyboard.KEY_C, CATEGORY);
        freelook = new KeyBinding("Freelook", Keyboard.KEY_LMENU, CATEGORY);
        menu = new KeyBinding("Origin Menu", Keyboard.KEY_RSHIFT, CATEGORY);
        ClientRegistry.registerKeyBinding(zoom);
        ClientRegistry.registerKeyBinding(freelook);
        ClientRegistry.registerKeyBinding(menu);
    }
}
