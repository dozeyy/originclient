package com.origin.client.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup work that should not be on a player's boot at all, switched off before
 * any mod initializes (a Fabric preLaunch entrypoint runs before every client
 * entrypoint, so the mods below read their config after this).
 *
 * Mod Menu's update checker: on every boot it SHA-512-hashes every mod jar and
 * asks Modrinth about them, on Util.backgroundExecutor -- the same worker pool
 * the startup resource reload runs on, so it takes CPU from the loading screen
 * (boot-time JFR profile, 2026-10-19). Its result is a "new version" badge for
 * mods Origin bundles and updates itself; a player cannot act on it. Same call
 * as the launcher's IrisConfigSeeder for Iris's checker: set Mod Menu's own off
 * switch (config/modmenu.json "update_checker") rather than patch its code.
 * Mod Menu reads missing keys as defaults, so a file holding only this key is
 * valid; an existing file keeps every other key.
 *
 * Never throws: a failure here only means the check runs, as before.
 * Must not touch a Minecraft class (preLaunch).
 */
public final class BootTuning implements PreLaunchEntrypoint {
	private static final Logger LOG = LoggerFactory.getLogger("Origin");
	private static final String MODMENU_KEY = "update_checker";

	@Override
	public void onPreLaunch() {
		try {
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				modMenuUpdateCheckOff(FabricLoader.getInstance().getConfigDir().resolve("modmenu.json"));
			}
		} catch (Throwable t) {
			LOG.debug("Origin: could not turn Mod Menu's update checker off", t);
		}
	}

	static void modMenuUpdateCheckOff(Path file) throws Exception {
		JsonObject json = new JsonObject();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement e = JsonParser.parseReader(r);
				if (!e.isJsonObject()) {
					return; // not Mod Menu's shape; leave it to Mod Menu
				}
				json = e.getAsJsonObject();
			}
			JsonElement cur = json.get(MODMENU_KEY);
			if (cur != null && cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isBoolean() && !cur.getAsBoolean()) {
				return; // already off: don't rewrite the file every launch
			}
		}
		json.addProperty(MODMENU_KEY, false);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".origin-tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			gson.toJson(json, w);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
