package com.origin.client.forge;

import com.origin.client.OriginState;
import com.origin.client.feature.OriginClientEvents;
import com.origin.client.hud.OriginHud;
import com.origin.client.input.OriginKeys;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * FML mod container for Origin on the classic Forge versions. The branded
 * screens come from mixins (registered in {@link MixinLoader}); this wires the
 * config, key bindings, and the feature/HUD event handlers.
 */
@Mod(modid = OriginForgeMod.MODID, name = "Origin Client", version = "0.4.1", clientSideOnly = true)
public class OriginForgeMod {
    public static final String MODID = "originclient";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        OriginState.init(event.getModConfigurationDirectory());
        OriginKeys.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        OriginClientEvents events = new OriginClientEvents();
        OriginHud hud = new OriginHud();
        // FOV + overlay events fire on the Forge bus; TickEvent fires on the FML
        // bus on 1.8.9 — register on both so every handler is reached.
        MinecraftForge.EVENT_BUS.register(events);
        MinecraftForge.EVENT_BUS.register(hud);
        FMLCommonHandler.instance().bus().register(events);
    }
}
