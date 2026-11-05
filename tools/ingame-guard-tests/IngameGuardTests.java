// Pure-Java proofs for the in-game guards (bug run 3): WorldEdit CUI numbers a server
// sends (CuiNumbers), the damaged-config set-aside (ConfigFiles), Hypixel detection
// (HypixelDetect) and the per-version singleplayer key Portal Calc now shares with
// waypoints (WaypointScope), and waypoints per Hypixel game and map (HypixelLocation,
// WaypointScope.hypixel, JG-8). Run by run.py against the shared copies.
import com.origin.client.client.ConfigFiles;
import com.origin.client.client.mods.HypixelDetect;
import com.origin.client.client.mods.HypixelLocation;
import com.origin.client.client.waypoints.WaypointScope;
import com.origin.client.client.worldedit.CuiNumbers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class IngameGuardTests {
	static int fails = 0;

	static void eq(String what, Object want, Object got) {
		boolean ok = want == null ? got == null : want.equals(got);
		if (!ok) {
			fails++;
		}
		System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  want=" + want + " got=" + got));
	}

	static boolean throwsNfe(Runnable r) {
		try {
			r.run();
			return false;
		} catch (NumberFormatException e) {
			return true;
		}
	}

	/** The renderer's ring loop, as written in every CuiRenderer: how many rings it draws. */
	static long rings(int lo, int hi) {
		int step = CuiNumbers.ringStep((long) hi - lo, 128);
		long n = 0;
		for (long y = lo + 1L; y <= hi; y += step) {
			n++;
			if (n > 1_000_000) {
				return -1;   // would have spun forever
			}
		}
		return n;
	}

	/** The ellipsoid latitude loop: rings for a radius parsed from the server. */
	static long latitudes(double r) {
		int yr = (int) Math.floor(r);
		int step = CuiNumbers.ringStep(2L * yr, 128);
		long n = 0;
		for (long k = -(long) yr; k < yr; k += step) {
			n++;
			if (n > 1_000_000) {
				return -1;
			}
		}
		return n;
	}

	public static void main(String[] a) throws Exception {
		// ---- GS-2: CUI numbers ----
		eq("int max clamps to the world", 30_000_000, CuiNumbers.parseInt("2147483647"));
		eq("int min clamps to the world", -30_000_000, CuiNumbers.parseInt("-2147483648"));
		eq("int via float like WorldEditCUI", 12, CuiNumbers.parseInt("12.0"));
		eq("int 1e40 clamps", 30_000_000, CuiNumbers.parseInt("1e40"));
		eq("int NaN drops the line", true, throwsNfe(() -> CuiNumbers.parseInt("NaN")));
		eq("double 2e9 clamps", 30_000_000.0, CuiNumbers.parseDouble("2e9"));
		eq("double -Infinity clamps", -30_000_000.0, CuiNumbers.parseDouble("-Infinity"));
		eq("double NaN drops the line", true, throwsNfe(() -> CuiNumbers.parseDouble("NaN")));
		eq("double ordinary value kept", 4.5, CuiNumbers.parseDouble("4.5"));
		eq("ringStep of 0 is 1", 1, CuiNumbers.ringStep(0, 128));
		eq("ringStep of a negative span is 1", 1, CuiNumbers.ringStep(-5, 128));
		eq("ringStep of 256 is 2", 2, CuiNumbers.ringStep(256, 128));
		// The reported freezes, through the loop shapes the renderer uses now.
		eq("mm|1|2147483647 (raw ints) ends", true, rings(1, Integer.MAX_VALUE) > 0 && rings(1, Integer.MAX_VALUE) <= 129);
		eq("full int range ends", true, rings(Integer.MIN_VALUE, Integer.MAX_VALUE) > 0 && rings(Integer.MIN_VALUE, Integer.MAX_VALUE) <= 129);
		eq("clamped range ends", true, rings(CuiNumbers.parseInt("-2147483648"), CuiNumbers.parseInt("2147483647")) <= 129);
		eq("ellipsoid radius 2e9 (raw) ends", true, latitudes(2e9) > 0 && latitudes(2e9) <= 129);
		eq("ellipsoid radius 2e9 (clamped) ends", true, latitudes(CuiNumbers.parseDouble("2e9")) <= 129);
		eq("ellipsoid radius 1e300 (raw) ends", true, latitudes(1e300) > 0 && latitudes(1e300) <= 129);
		eq("small ellipsoid keeps one ring per block", 10L, latitudes(5));

		// ---- GS-8: damaged configs are set aside, never lost ----
		Path dir = Files.createTempDirectory("guard-");
		Path cfg = dir.resolve("originclient-mods.json");
		Files.write(cfg, "{ \"mods\": { broken".getBytes(StandardCharsets.UTF_8));
		Path backup = ConfigFiles.setAside(cfg);
		eq("set aside to .corrupt", dir.resolve("originclient-mods.json.corrupt"), backup);
		eq("original gone (next save starts clean)", false, Files.exists(cfg));
		eq("backup keeps the bytes", "{ \"mods\": { broken", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
		eq(".corrupt never matches Settings Sync's originclient*.json",
				false, backup.getFileName().toString().matches("^originclient[A-Za-z0-9_.\\-]*\\.json$"));
		Files.write(cfg, "second".getBytes(StandardCharsets.UTF_8));
		ConfigFiles.setAside(cfg);
		eq("a newer damaged file replaces the older backup", "second", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
		eq("missing file: nothing to do", null, ConfigFiles.setAside(dir.resolve("nope.json")));
		eq("null path: nothing to do", null, ConfigFiles.setAside(null));

		// ---- GS-9: Hypixel by address or brand ----
		eq("typed address", true, HypixelDetect.isHypixel("mc.hypixel.net", null));
		eq("address with port and case", true, HypixelDetect.isHypixel(" MC.Hypixel.NET:25565 ", null));
		eq("trailing dot", true, HypixelDetect.isHypixel("hypixel.net.", null));
		eq("lookalike host is not Hypixel", false, HypixelDetect.isHypixel("nothypixel.net", null));
		eq("numeric IP + Hypixel brand", true, HypixelDetect.isHypixel("209.222.115.10", "Hypixel BungeeCord (2024.1.1) <- Slime"));
		eq("own alias + brand any case", true, HypixelDetect.isHypixel("mynetwork.example", "hypixel bungeecord"));
		eq("other server, other brand", false, HypixelDetect.isHypixel("play.example.com", "Paper"));
		eq("nothing known", false, HypixelDetect.isHypixel(null, null));

		// ---- GS-3: Portal Calc's singleplayer key is the waypoint key (per version) ----
		String k121 = WaypointScope.singleplayer("New World", "1.21.1");
		String k120 = WaypointScope.singleplayer("New World", "1.20.1");
		eq("versioned sp key", "sp:1.21.1:New World", k121);
		eq("same-named worlds in two versions differ", false, k121.equals(k120));
		eq("migrates from the old portal key", "sp:New World", WaypointScope.unversioned(k121, "1.21.1"));
		eq("servers are not migrated", null, WaypointScope.unversioned("mp:play.example.com", "1.21.1"));

		// ---- JG-8: waypoints per Hypixel game and map ----
		eq("bed wars game", "bedwars/lighthouse", HypixelLocation.parse(
				"{\"server\":\"mini12A\",\"gametype\":\"BEDWARS\",\"mode\":\"BEDWARS_EIGHT_ONE\",\"map\":\"Lighthouse\"}"));
		eq("bed wars lobby", "bedwars/lobby", HypixelLocation.parse(
				"{\"server\":\"lobby7\",\"gametype\":\"BEDWARS\",\"lobbyname\":\"bedwarslobby51\"}"));
		eq("skyblock hub", "skyblock/hub", HypixelLocation.parse(
				" {\"server\": \"mini1B\", \"gametype\": \"SKYBLOCK\", \"mode\": \"hub\", \"map\": \"Hub\"} "));
		eq("mode when no map", "skyblock/dynamic", HypixelLocation.parse(
				"{\"server\":\"mini9\",\"gametype\":\"SKYBLOCK\",\"mode\":\"dynamic\"}"));
		eq("limbo", "limbo", HypixelLocation.parse("{\"server\":\"limbo\"}"));
		eq("plain chat is not a reply", null, HypixelLocation.parse("[MVP+] Someone: {\"server\":\"x\"}"));
		eq("json without server", null, HypixelLocation.parse("{\"gametype\":\"BEDWARS\"}"));
		HypixelLocation.clear();
		eq("no ask right at the switch", false, HypixelLocation.tick(11, true, 1000));
		eq("ask after the delay", true, HypixelLocation.tick(11, true, 1000 + 1500));
		eq("asked once", false, HypixelLocation.tick(11, true, 1000 + 1600));
		eq("our reply is hidden", true, HypixelLocation.take("{\"server\":\"m\",\"gametype\":\"DUELS\",\"map\":\"Arena\"}", 3000));
		eq("place known in that world", "duels/arena", HypixelLocation.place(11));
		eq("not in another world", null, HypixelLocation.place(12));
		eq("a typed /locraw later shows", false, HypixelLocation.take("{\"server\":\"m\",\"gametype\":\"DUELS\",\"map\":\"Arena\"}", 60000));
		eq("new world forgets the place", false, HypixelLocation.tick(12, true, 70000));
		eq("forgotten", null, HypixelLocation.place(12));
		eq("waypoints off: no ask", false, HypixelLocation.tick(12, false, 80000));
		eq("asks once waypoints are on", true, HypixelLocation.tick(12, true, 80050));
		eq("one retry when Hypixel stays quiet", true, HypixelLocation.tick(12, true, 80050 + 5001));
		eq("no second retry", false, HypixelLocation.tick(12, true, 80050 + 20000));
		HypixelLocation.clear();
		String hk = WaypointScope.server("mc.hypixel.net");
		eq("hypixel place key", "mp:mc.hypixel.net/bedwars/lighthouse", WaypointScope.hypixel(hk, "bedwars/lighthouse", "minecraft:overworld"));
		eq("hypixel unknown place: server + dimension", "mp:mc.hypixel.net/minecraft:overworld", WaypointScope.hypixel(hk, null, "minecraft:overworld"));
		eq("skyblock starts from the old list", "mp:mc.hypixel.net", WaypointScope.hypixelPrevious("mp:mc.hypixel.net/skyblock/hub"));
		eq("minigame maps start empty", null, WaypointScope.hypixelPrevious("mp:mc.hypixel.net/bedwars/lighthouse"));
		eq("fallback starts empty", null, WaypointScope.hypixelPrevious("mp:mc.hypixel.net/minecraft:overworld"));
		eq("other servers: no previous", null, WaypointScope.hypixelPrevious("mp:play.example.com"));

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
