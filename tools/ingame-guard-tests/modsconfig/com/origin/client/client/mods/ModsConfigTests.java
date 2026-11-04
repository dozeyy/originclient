package com.origin.client.client.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

// The shared ModsConfig against a real config dir (FabricLoader/OriginClient are stubs).
// ModsConfig is all static state, so run.py starts one JVM per scenario:
//   java ... ModsConfigTests <scenario>   with -Dtest.configDir=<fresh temp dir>
// The classpath carries a tiny starter config (zoom fov 30) as the bundled default.
public class ModsConfigTests {
	static int fails = 0;

	static void eq(String what, Object want, Object got) {
		boolean ok = want == null ? got == null : want.equals(got);
		if (!ok) {
			fails++;
		}
		System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  want=" + want + " got=" + got));
	}

	static String val(String mod, String key) {
		java.util.Map<String, JsonElement> m = ModsConfig.VALUES.get(mod);
		JsonElement e = m == null ? null : m.get(key);
		return e == null ? null : e.getAsString();
	}

	public static void main(String[] a) throws Exception {
		Path dir = Paths.get(System.getProperty("test.configDir"));
		Path file = dir.resolve("originclient-mods.json");
		switch (a[0]) {
			case "corrupt": {
				// GS-8: unparseable JSON -> kept as .corrupt, starter config (not bare defaults), no write yet.
				Files.write(file, "{ \"mods\": { \"zoom\": { broken".getBytes(StandardCharsets.UTF_8));
				ModsConfig.ensureLoaded();
				eq("damaged file kept as .corrupt", true, Files.exists(dir.resolve("originclient-mods.json.corrupt")));
				eq("damaged file no longer in place", false, Files.exists(file));
				eq("starter config loaded (zoom fov 30)", "30", val("zoom", "fov"));
				eq("store not empty, so legacy flags are not stamped over it", false, ModsConfig.VALUES.isEmpty());
				break;
			}
			case "wrongshape": {
				// GS-8: readable JSON with bad entries -> only those entries are skipped.
				Files.write(file, ("{\"mods\":{\"zoom\":5,\"fps\":{\"enabled\":true}},"
						+ "\"hud\":{\"bad\":[1,2,\"x\",4],\"short\":[1,2],\"good\":[0,1,2,1.5,1]},"
						+ "\"meta\":7,\"profiles\":{\"p\":{\"mods\":{}}}}").getBytes(StandardCharsets.UTF_8));
				ModsConfig.ensureLoaded();
				eq("good mod entry kept", "true", val("fps", "enabled"));
				eq("bad mod entry skipped", false, ModsConfig.VALUES.containsKey("zoom"));
				eq("good HUD entry kept (5 values)", 5, ModsConfig.HUD.containsKey("good") ? ModsConfig.HUD.get("good").length : -1);
				eq("non-number HUD entry skipped", false, ModsConfig.HUD.containsKey("bad"));
				eq("short HUD entry skipped", false, ModsConfig.HUD.containsKey("short"));
				eq("profiles still read", true, ModsConfig.PROFILES.containsKey("p"));
				eq("file left in place", true, Files.exists(file));
				break;
			}
			case "profile": {
				// GS-5: applying a profile keeps the Hypixel API key.
				Files.write(file, "{\"mods\":{\"zoom\":{\"fov\":50},\"hypixel\":{\"apiKey\":\"K1\"}}}".getBytes(StandardCharsets.UTF_8));
				ModsConfig.ensureLoaded();
				ModsConfig.saveProfile("pvp");
				JsonObject snap = ModsConfig.PROFILES.get("pvp");
				JsonObject hyp = snap.getAsJsonObject("mods").getAsJsonObject("hypixel");
				eq("profile never stores the API key", false, hyp != null && hyp.has("apiKey"));
				ModsConfig.VALUES.get("zoom").put("fov", new JsonPrimitive(70));
				ModsConfig.VALUES.get("hypixel").put("apiKey", new JsonPrimitive("K2"));
				ModsConfig.applyProfile("pvp");
				eq("profile restores its loadout", "50", val("zoom", "fov"));
				// BMOD-6: a profile saved now is marked; one saved before v1.0.89 gets the
				// old-default-keys step when applied.
				eq("new profile carries the default-keys marker", true, snap.has("defaultKeys"));
				eq("marked profile: no key step", 0, Mods.keepOldDefaultKeysCalls);
				eq("API key pasted later survives", "K2", val("hypixel", "apiKey"));
				// A profile saved by an older build still holds a key: the live one wins.
				JsonObject old = JsonParser.parseString("{\"mods\":{\"hypixel\":{\"apiKey\":\"OLD\"}},\"hud\":{}}").getAsJsonObject();
				ModsConfig.PROFILES.put("old", old);
				ModsConfig.applyProfile("old");
				eq("old profile's key never replaces the live one", "K2", val("hypixel", "apiKey"));
				eq("unmarked (pre-v1.0.89) profile: key step runs", 1, Mods.keepOldDefaultKeysCalls);
				ModsConfig.VALUES.put("hypixel", new HashMap<>());
				ModsConfig.applyProfile("old");
				eq("no live key: an old profile's key is not brought back", null, val("hypixel", "apiKey"));
				String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				eq("saved file is valid JSON", true, JsonParser.parseString(saved).isJsonObject());
				break;
			}
			default:
				throw new IllegalArgumentException(a[0]);
		}
		System.exit(fails == 0 ? 0 : 1);
	}
}
