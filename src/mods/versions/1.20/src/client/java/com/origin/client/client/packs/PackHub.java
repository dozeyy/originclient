package com.origin.client.client.packs;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;
import java.util.Collection;

// The Resource Pack Hub's shared state: one index, one installer, one catalog
// and one library per game session, plus the Minecraft facts the pure classes
// need (pack folder, game version, pack format). Everything Minecraft-specific
// that is not drawing lives here so the pure classes stay unit-testable.
public final class PackHub {
	private PackHub() {
	}

	/** 1.20 / 1.20.1's resource pack_format; used only if the version API ever moves. */
	private static final int FALLBACK_FORMAT = 15;

	private static PackIndex index;
	private static PackInstaller installer;
	private static PackCatalog catalog;
	private static PackLibrary library;

	public static synchronized PackIndex index() {
		if (index == null) {
			index = new PackIndex(FabricLoader.getInstance().getConfigDir().resolve("originclient-packhub.json"));
		}
		return index;
	}

	public static synchronized PackInstaller installer() {
		if (installer == null) {
			installer = new PackInstaller(index());
		}
		return installer;
	}

	public static synchronized PackCatalog catalog() {
		if (catalog == null) {
			catalog = new PackCatalog(FabricLoader.getInstance().getConfigDir().resolve("originclient-packhub-cache"),
					gameVersion());
		}
		return catalog;
	}

	public static synchronized PackLibrary library() {
		if (library == null) {
			library = new PackLibrary(index(), gameVersion());
		}
		return library;
	}

	/** resourcepacks/ of THIS account's game folder (the folder Minecraft scans). */
	public static Path packDir() {
		try {
			Path p = Minecraft.getInstance().getResourcePackDirectory();
			if (p != null) {
				return p;
			}
		} catch (Throwable ignored) {
			// called before Minecraft finished constructing
		}
		return FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
	}

	public static String gameVersion() {
		return SharedConstants.getCurrentVersion().getName();
	}

	public static int gameFormat() {
		try {
			return SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
		} catch (Throwable t) {
			return FALLBACK_FORMAT;
		}
	}

	/**
	 * Client init: finish uninstalls that had to wait (the pack was loaded, so
	 * Windows held the file) - once now, before any pack is opened, and after
	 * every resource reload, which is when Minecraft lets go of packs it unloaded.
	 */
	public static void register() {
		try {
			index().flushDeletes(packDir());
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin pack hub: startup cleanup failed", t);
		}
		try {
			ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
					new SimpleSynchronousResourceReloadListener() {
						@Override
						public ResourceLocation getFabricId() {
							return new ResourceLocation("originclient", "pack_hub_cleanup");
						}

						@Override
						public void onResourceManagerReload(ResourceManager manager) {
							flushAfterReload();
						}
					});
		} catch (Throwable t) {
			// without it, queued uninstalls still finish at the next game start
			com.origin.client.OriginClient.LOGGER.warn("Origin pack hub: reload hook unavailable", t);
		}
	}

	private static void flushAfterReload() {
		try {
			// a pack the player re-enabled since uninstalling it is kept, not deleted
			Collection<String> selected = Minecraft.getInstance().getResourcePackRepository().getSelectedIds();
			PackIndex idx = index();
			for (String id : selected) {
				if (PackOrder.managed(id) && idx.isPendingDelete(id.substring(5))) {
					idx.cancelDelete(id.substring(5));
				}
			}
			if (idx.flushDeletes(packDir()) > 0) {
				library().rescan(packDir(), false);
			}
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.debug("Origin pack hub: cleanup after reload failed", t);
		}
	}
}
