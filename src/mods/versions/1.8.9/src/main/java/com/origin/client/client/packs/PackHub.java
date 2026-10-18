package com.origin.client.client.packs;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.ResourcePackRepository;

import java.nio.file.Path;
import java.util.List;

// The Resource Pack Hub's shared state: one index, one installer, one catalog
// and one library per game session, plus the Minecraft facts the pure classes
// need (pack folder, game version, pack format). Everything Minecraft-specific
// that is not drawing lives here so the pure classes stay unit-testable.
// 1.8.9 port of 1.21.1's PackHub: Forge's config dir instead of Fabric's, a
// fixed version + pack format (1.8.9 has no version API), and a vanilla reload
// listener instead of Fabric's ResourceManagerHelper.
public final class PackHub {
	private PackHub() {
	}

	/** 1.8.9: Modrinth's game-version tag for this module (the brief's "1.8.9"). */
	private static final String GAME_VERSION = "1.8.9";
	/** 1.8.9: resource pack_format 1 (1.6.1-1.8.9); GuiScreenResourcePacks flags any other as incompatible. */
	private static final int PACK_FORMAT = 1;

	private static PackIndex index;
	private static PackInstaller installer;
	private static PackCatalog catalog;
	private static PackLibrary library;

	public static synchronized PackIndex index() {
		if (index == null) {
			index = new PackIndex(OriginClient.configDir().resolve("originclient-packhub.json"));
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
			catalog = new PackCatalog(OriginClient.configDir().resolve("originclient-packhub-cache"), gameVersion());
		}
		return catalog;
	}

	public static synchronized PackLibrary library() {
		if (library == null) {
			library = new PackLibrary(index(), gameVersion());
		}
		return library;
	}

	/**
	 * resourcepacks/ of THIS account's game folder. 1.8.9: Minecraft builds its
	 * repository on new File(mcDataDir, "resourcepacks"), so this is the same
	 * folder without reaching into the repository.
	 */
	public static Path packDir() {
		return OriginClient.gameDir().resolve("resourcepacks");
	}

	public static String gameVersion() {
		return GAME_VERSION;
	}

	public static int gameFormat() {
		return PACK_FORMAT;
	}

	/**
	 * Client init: finish uninstalls that had to wait (Windows held a file that
	 * Minecraft had open) - once now, and after every resource reload, which is
	 * when Minecraft lets go of packs it unloaded.
	 */
	public static void register() {
		try {
			index().flushDeletes(packDir());
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("Origin pack hub: startup cleanup failed", t);
		}
		try {
			// 1.8.9: the client resource manager is a SimpleReloadableResourceManager;
			// registerReloadListener also runs the listener once right away (harmless).
			((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager()).registerReloadListener(
					new IResourceManagerReloadListener() {
						@Override
						public void onResourceManagerReload(IResourceManager manager) {
							flushAfterReload();
						}
					});
		} catch (Throwable t) {
			// without it, queued uninstalls still finish at the next game start
			OriginClient.LOGGER.warn("Origin pack hub: reload hook unavailable", t);
		}
	}

	private static void flushAfterReload() {
		try {
			// a pack the player re-enabled since uninstalling it is kept, not deleted
			PackIndex idx = index();
			for (String name : selectedTopFirst()) {
				if (idx.isPendingDelete(name)) {
					idx.cancelDelete(name);
				}
			}
			if (idx.flushDeletes(packDir()) > 0) {
				library().rescan(packDir(), false);
			}
		} catch (Throwable t) {
			OriginClient.LOGGER.debug("Origin pack hub: cleanup after reload failed", t);
		}
	}

	/** Enabled pack file names, top priority first (the repository keeps them bottom first). */
	static List<String> selectedTopFirst() {
		List<String> out = new java.util.ArrayList<>();
		List<ResourcePackRepository.Entry> sel = Minecraft.getMinecraft().getResourcePackRepository().getRepositoryEntries();
		for (int i = sel.size() - 1; i >= 0; i--) {
			out.add(sel.get(i).getResourcePackName());
		}
		return out;
	}

	/**
	 * Makes `filesTopFirst` the enabled resource packs, exactly as the Resource
	 * Packs screen's Done does on 1.8.9: repository selection, options.txt lists
	 * (incompatible ones recorded so the warning stays), save, one resource
	 * reload. Returns false when nothing changed (no reload). Render thread.
	 */
	static boolean applySelection(List<String> filesTopFirst) {
		Minecraft mc = Minecraft.getMinecraft();
		ResourcePackRepository repo = mc.getResourcePackRepository();
		repo.updateRepositoryEntriesAll();   // 1.8.9 NAME UNVERIFIED (packhub.txt): rescans resourcepacks/
		java.util.Map<String, ResourcePackRepository.Entry> byName = new java.util.HashMap<>();
		for (ResourcePackRepository.Entry e : repo.getRepositoryEntriesAll()) {   // 1.8.9 NAME UNVERIFIED
			byName.put(e.getResourcePackName(), e);
		}
		List<String> current = selectedTopFirst();
		// every 1.8.9 selection entry is a resourcepacks/ file (the default pack is
		// never listed, server packs live apart), so all slots are managed + unpinned
		List<PackOrder.Slot> slots = new java.util.ArrayList<>();
		for (String n : current) {
			slots.add(new PackOrder.Slot("file/" + n, false));
		}
		List<String> files = new java.util.ArrayList<>();
		for (String f : filesTopFirst) {
			if (byName.containsKey(f)) {
				files.add("file/" + f);
			}
		}
		List<String> next = new java.util.ArrayList<>();
		for (String id : PackOrder.apply(slots, files)) {
			next.add(id.substring(5));
		}
		if (next.equals(current)) {
			return false;
		}
		List<ResourcePackRepository.Entry> bottomFirst = new java.util.ArrayList<>();
		for (int i = next.size() - 1; i >= 0; i--) {
			ResourcePackRepository.Entry e = byName.get(next.get(i));
			if (e != null) {
				bottomFirst.add(e);
			}
		}
		repo.setRepositories(bottomFirst);   // 1.8.9 NAME UNVERIFIED (packhub.txt)
		mc.gameSettings.resourcePacks.clear();
		mc.gameSettings.incompatibleResourcePacks.clear();
		for (ResourcePackRepository.Entry e : bottomFirst) {
			mc.gameSettings.resourcePacks.add(e.getResourcePackName());
			if (e.func_183027_f() != PACK_FORMAT) {   // the entry's pack_format (verified, unnamed in MCP)
				mc.gameSettings.incompatibleResourcePacks.add(e.getResourcePackName());
			}
		}
		mc.gameSettings.saveOptions();
		mc.refreshResources();
		return true;
	}

	/**
	 * Closes the zip Minecraft holds for a disabled pack, so Windows lets the hub
	 * delete it now instead of at the next start. Only after applySelection's
	 * reload took it out of use. 1.8.9 has Entry.closeResourcePack (verified).
	 */
	static void release(String name) {
		try {
			for (ResourcePackRepository.Entry e : Minecraft.getMinecraft().getResourcePackRepository().getRepositoryEntriesAll()) {
				if (name.equals(e.getResourcePackName())) {
					e.closeResourcePack();
				}
			}
		} catch (Throwable ignored) {
			// still locked: the delete is queued and finishes after a reload / next start
		}
	}
}
