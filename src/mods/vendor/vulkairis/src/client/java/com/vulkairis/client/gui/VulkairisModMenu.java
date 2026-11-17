package com.vulkairis.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration (GUI plan Phase 10): exposes the vanilla
 * {@link VulkairisConfigScreen} from the Mods list. No Cloth Config required.
 */
public class VulkairisModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VulkairisModMenu::createScreen;
    }

    private static net.minecraft.client.gui.screens.Screen createScreen(
            net.minecraft.client.gui.screens.Screen parent) {
        return new VulkairisConfigScreen(parent, VulkairisGuiModels::forCategory);
    }
}
