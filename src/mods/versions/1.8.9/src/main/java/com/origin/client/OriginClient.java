package com.origin.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Path;

/**
 * Origin Client for Minecraft 1.8.9 (Forge) -- the entry point. Same role and
 * LOGGER as every other version's OriginClient; the Fabric ModInitializer +
 * ClientModInitializer pair becomes one client-only @Mod whose init hands off
 * to OriginClientMod (the hub). Version comes from mcmod.info (useMetadata),
 * which the build expands.
 *
 * Also the 1.8.9 home of the FabricLoader lookups other versions make:
 * {@link #configDir()}, {@link #gameDir()} and {@link #isModLoaded(String)}.
 *
 * Mandate 4: nothing here may stop the game from loading. Every stage catches
 * Throwable; a failed Origin init leaves vanilla 1.8.9 running.
 */
@Mod(modid = OriginClient.MOD_ID, name = "Origin Client", version = "1.8.9", useMetadata = true,
		clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class OriginClient {
	public static final String MOD_ID = "originclient";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		LOGGER.info("Origin Client initializing");
		try {
			com.origin.client.client.OriginClientMod.init();
		} catch (Throwable t) {
			// Vanilla is the fail-soft floor; a crash is not.
			LOGGER.error("Origin Client failed to initialize; running vanilla", t);
		}
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		try {
			com.origin.client.client.OriginClientMod.postInit();
		} catch (Throwable t) {
			LOGGER.error("Origin Client post-init failed", t);
		}
	}

	// ---- FabricLoader stand-ins ----

	/** FabricLoader.getConfigDir(): Forge's config folder (<game dir>/config). */
	public static Path configDir() {
		try {
			File dir = Loader.instance().getConfigDir();
			if (dir != null) {
				return dir.toPath();
			}
		} catch (Throwable ignored) {
			// fall through to the game-dir guess
		}
		return gameDir().resolve("config");
	}

	/** FabricLoader.getGameDir(): the instance folder (Minecraft.mcDataDir). */
	public static Path gameDir() {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc != null && mc.mcDataDir != null) {
				return mc.mcDataDir.toPath();
			}
		} catch (Throwable ignored) {
			// too early: fall back to the working directory, which is the game dir
		}
		return new File(".").getAbsoluteFile().toPath().normalize();
	}

	/** FabricLoader.isModLoaded(id): a Forge mod by its modid. OptiFine is not a Forge
	 *  mod on 1.8.9 -- use {@link #isOptiFineLoaded()} for it. */
	public static boolean isModLoaded(String modId) {
		try {
			return Loader.isModLoaded(modId);
		} catch (Throwable t) {
			return false;
		}
	}

	private static volatile Boolean optifine;

	/** OptiFine is a tweaker/coremod, not a @Mod: Forge's own FMLClientHandler.hasOptifine(),
	 *  else its Config class (the default package on 1.8.9 OptiFine). */
	public static boolean isOptiFineLoaded() {
		Boolean v = optifine;
		if (v == null) {
			boolean found = false;
			try {
				found = net.minecraftforge.fml.client.FMLClientHandler.instance().hasOptifine();
			} catch (Throwable ignored) {
				// fall back to the class probe
			}
			for (String n : new String[]{"Config", "net.optifine.Config", "optifine.OptiFineForgeTweaker"}) {
				if (found) {
					break;
				}
				try {
					Class.forName(n, false, OriginClient.class.getClassLoader());
					found = true;
					break;
				} catch (Throwable ignored) {
					// next candidate
				}
			}
			optifine = v = found;
		}
		return v;
	}
}
