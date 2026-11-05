// Standalone proof for per-world waypoints (pure logic: key derivation, file naming,
// legacy migration). No Gradle, no Minecraft:
//   javac -d <out> src/mods/shared/src/client/java/com/origin/client/client/waypoints/WaypointScope.java tools/waypoint-scope-check/WaypointScopeCheck.java
//   java -cp <out> WaypointScopeCheck
import com.origin.client.client.waypoints.WaypointScope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class WaypointScopeCheck {
	static int fails = 0;

	static void eq(String what, Object want, Object got) {
		boolean ok = want == null ? got == null : want.equals(got);
		if (!ok) {
			fails++;
		}
		System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : "  want=" + want + " got=" + got));
	}

	public static void main(String[] a) throws Exception {
		// keys: singleplayer by save folder
		eq("sp key", "sp:New World", WaypointScope.singleplayer("New World"));
		eq("sp blank", "sp:unknown", WaypointScope.singleplayer("  "));
		// servers: case, default port, trailing dot, ports kept, IPv6, LAN
		eq("mp plain", "mp:play.example.com", WaypointScope.server("play.example.com"));
		eq("mp case+default port", "mp:play.example.com", WaypointScope.server(" Play.Example.COM:25565 "));
		eq("mp trailing dot", "mp:play.example.com", WaypointScope.server("play.example.com."));
		eq("mp custom port", "mp:play.example.com:25570", WaypointScope.server("play.example.com:25570"));
		eq("mp different port != default", false, WaypointScope.server("a.com:25570").equals(WaypointScope.server("a.com")));
		eq("mp ipv6 bracket", "mp:2001:db8::1:25570", WaypointScope.server("[2001:DB8::1]:25570"));
		eq("mp ipv6 bare", "mp:2001:db8::1", WaypointScope.server("2001:db8::1"));
		eq("mp public ip", "mp:8.8.8.8", WaypointScope.server("8.8.8.8:25565"));
		eq("lan 192.168 any port", "lan:192.168.1.5", WaypointScope.server("192.168.1.5:54321"));
		eq("lan 172.16", "lan:172.16.0.9", WaypointScope.server("172.16.0.9:1"));
		eq("not lan 172.32", "mp:172.32.0.9", WaypointScope.server("172.32.0.9"));
		eq("lan localhost", "lan:localhost", WaypointScope.server("localhost:50123"));
		eq("blank server", "mp:unknown", WaypointScope.server(null));
		eq("sp != mp namespace", false, WaypointScope.singleplayer("x").equals(WaypointScope.server("x")));

		// file names: safe, stable, distinct for look-alikes
		String f1 = WaypointScope.fileName("sp:a b");
		String f2 = WaypointScope.fileName("sp:a_b");
		eq("file prefix+ext", true, f1.startsWith("originclient-waypoints-") && f1.endsWith(".json"));
		eq("file safe chars", true, f1.matches("[A-Za-z0-9._-]+"));
		eq("file look-alikes differ", false, f1.equals(f2));
		eq("file stable", f1, WaypointScope.fileName("sp:a b"));
		eq("file hostile name safe", true, WaypointScope.fileName("sp:..\\..\\x/y:z*").matches("[A-Za-z0-9._-]+"));
		eq("file length bounded", true, WaypointScope.fileName(new String(new char[500]).replace('\0', 'q')).length() < 100);
		eq("file never the legacy name", false, f1.equals(WaypointScope.LEGACY_FILE));

		// migration
		Path dir = Files.createTempDirectory("wpscope");
		Path legacy = dir.resolve(WaypointScope.LEGACY_FILE);
		Path backup = dir.resolve(WaypointScope.LEGACY_BACKUP);
		Path world1 = dir.resolve(WaypointScope.fileName("sp:World1"));
		Path world2 = dir.resolve(WaypointScope.fileName("sp:World2"));
		eq("no legacy -> nothing", false, WaypointScope.adoptLegacy(dir, world1));
		Files.write(legacy, "[ {\"name\":\"Base\"} ]".getBytes(StandardCharsets.UTF_8));
		eq("first world adopts", true, WaypointScope.adoptLegacy(dir, world1));
		eq("world1 has the list", "[ {\"name\":\"Base\"} ]", new String(Files.readAllBytes(world1), StandardCharsets.UTF_8));
		eq("legacy parked as backup", true, Files.exists(backup) && !Files.exists(legacy));
		eq("second world gets nothing", false, WaypointScope.adoptLegacy(dir, world2));
		eq("world2 file absent", false, Files.exists(world2));
		// Settings Sync restoring the old file must not re-adopt
		Files.write(legacy, "[ {\"name\":\"Restored\"} ]".getBytes(StandardCharsets.UTF_8));
		eq("re-appearing legacy ignored", false, WaypointScope.adoptLegacy(dir, world2));
		eq("world2 still absent", false, Files.exists(world2));
		// crash between copy and park: scope file exists, legacy still there, no backup
		Files.delete(backup);
		Files.delete(legacy);
		Path world3 = dir.resolve(WaypointScope.fileName("sp:World3"));
		Files.write(legacy, "[1]".getBytes(StandardCharsets.UTF_8));
		Files.write(world3, "[\"own\"]".getBytes(StandardCharsets.UTF_8));
		eq("existing scope file not overwritten", false, WaypointScope.adoptLegacy(dir, world3));
		eq("scope file kept", "[\"own\"]", new String(Files.readAllBytes(world3), StandardCharsets.UTF_8));
		eq("legacy still parked after retry", true, Files.exists(backup) && !Files.exists(legacy));

		// singleplayer keys carry the Minecraft version (same-named saves in two versions
		// are different worlds), and a world copies its old unversioned list once
		eq("sp versioned key", "sp:1.21.1:New World", WaypointScope.singleplayer("New World", "1.21.1"));
		eq("sp versions differ", false,
				WaypointScope.singleplayer("New World", "1.21.1").equals(WaypointScope.singleplayer("New World", "1.20.1")));
		eq("sp blank version = old key", "sp:New World", WaypointScope.singleplayer("New World", " "));
		eq("sp versioned blank folder", "sp:1.20:unknown", WaypointScope.singleplayer("", "1.20"));
		eq("unversioned of own key", "sp:New World", WaypointScope.unversioned("sp:1.21.1:New World", "1.21.1"));
		eq("unversioned: folder with colon", "sp:a:b", WaypointScope.unversioned("sp:26.3:a:b", "26.3"));
		eq("unversioned: other version", null, WaypointScope.unversioned("sp:1.20:New World", "1.21.1"));
		eq("unversioned: server key", null, WaypointScope.unversioned("mp:example.com", "1.21.1"));
		eq("unversioned: no version", null, WaypointScope.unversioned("sp:New World", ""));

		Path oldSp = dir.resolve(WaypointScope.fileName("sp:Mig"));
		Path newSp = dir.resolve(WaypointScope.fileName("sp:1.21.1:Mig"));
		eq("nothing to adopt", false, WaypointScope.adoptPrevious(oldSp, newSp));
		Files.write(oldSp, "[\"old\"]".getBytes(StandardCharsets.UTF_8));
		eq("first load adopts old list", true, WaypointScope.adoptPrevious(oldSp, newSp));
		eq("adopted content", "[\"old\"]", new String(Files.readAllBytes(newSp), StandardCharsets.UTF_8));
		eq("old file kept", true, Files.exists(oldSp));
		Files.write(newSp, "[]".getBytes(StandardCharsets.UTF_8));   // player deleted them all
		eq("adopts only once", false, WaypointScope.adoptPrevious(oldSp, newSp));
		eq("emptied list stays empty", "[]", new String(Files.readAllBytes(newSp), StandardCharsets.UTF_8));
		eq("no tmp left", false, Files.exists(newSp.resolveSibling(newSp.getFileName() + ".tmp")));

		// MAP-2: a singleplayer key carries the world seed, so a new world that reuses a
		// deleted world's folder name ("New World") is a different scope
		String seeded = WaypointScope.singleplayer("New World", "1.21.1", 12345L);
		eq("seeded key shape", true, seeded.matches("sp:1\\.21\\.1#[0-9a-f]{8}:New World"));
		eq("seeds differ", false, seeded.equals(WaypointScope.singleplayer("New World", "1.21.1", 54321L)));
		eq("seed is stable", seeded, WaypointScope.singleplayer("New World", "1.21.1", 12345L));
		eq("seed not in key", false, seeded.contains("12345"));
		eq("seeded blank version = old key", "sp:New World", WaypointScope.singleplayer("New World", "", 7L));
		eq("unseeded", "sp:1.21.1:New World", WaypointScope.unseeded(seeded, "1.21.1"));
		eq("unseeded: folder with # and :", "sp:26.3:a#b:c",
				WaypointScope.unseeded(WaypointScope.singleplayer("a#b:c", "26.3", 1L), "26.3"));
		eq("unseeded: versioned key", null, WaypointScope.unseeded("sp:1.21.1:New World", "1.21.1"));
		eq("unseeded: other version", null, WaypointScope.unseeded(seeded, "1.20.1"));
		eq("unseeded: server key", null, WaypointScope.unseeded("mp:example.com", "1.21.1"));
		eq("unversioned: seeded key", null, WaypointScope.unversioned(seeded, "1.21.1"));

		Path mig = Files.createDirectories(dir.resolve("seed"));
		String worldA = WaypointScope.singleplayer("New World", "1.21.1", 1L);
		String worldB = WaypointScope.singleplayer("New World", "1.21.1", 2L);
		Path pre = mig.resolve(WaypointScope.fileName("sp:New World"));
		Path folder = mig.resolve(WaypointScope.fileName("sp:1.21.1:New World"));
		Path fileA = mig.resolve(WaypointScope.fileName(worldA));
		Path fileB = mig.resolve(WaypointScope.fileName(worldB));
		Files.write(pre, "[\"A\"]".getBytes(StandardCharsets.UTF_8));
		WaypointScope.adoptEarlier(mig, worldA, "1.21.1", null);
		eq("world A keeps its pre-version list", "[\"A\"]", new String(Files.readAllBytes(fileA), StandardCharsets.UTF_8));
		eq("folder list kept as backup", true, Files.exists(folder) && Files.exists(pre));
		WaypointScope.adoptEarlier(mig, worldB, "1.21.1", null);
		eq("new world in the reused folder starts empty", false, Files.exists(fileB));
		Files.delete(fileA);   // e.g. a corrupt file parked: the claimant may copy again
		WaypointScope.adoptEarlier(mig, worldA, "1.21.1", null);
		eq("the claimant re-adopts", true, Files.exists(fileA));
		eq("no claim tmp left", 0L, Files.list(mig).filter(p -> p.toString().endsWith(".tmp")).count());

		// MAP-7: a Realm is keyed by its Realms id (name only as a fallback) and starts from its address-keyed list once
		eq("realm key by id", "realm:id:4242", WaypointScope.realm(4242L, "Our Realm"));
		eq("renamed realm keeps its key", WaypointScope.realm(4242L, "Our Realm"), WaypointScope.realm(4242L, "New Name"));
		eq("same-named realms differ", false, WaypointScope.realm(1L, "???").equals(WaypointScope.realm(2L, "???")));
		eq("realm name fallback", "realm:name:Our Realm", WaypointScope.realm(0L, "  Our Realm "));
		eq("realm name fallback is not an id key", false, WaypointScope.realm(0L, "id:5").equals(WaypointScope.realm(5L, null)));
		eq("realm blank", null, WaypointScope.realm(-1L, " "));
		Path addr = mig.resolve(WaypointScope.fileName("mp:1.2.3.4:30000"));
		Path realm = mig.resolve(WaypointScope.fileName("realm:id:4242"));
		Files.write(addr, "[\"R\"]".getBytes(StandardCharsets.UTF_8));
		WaypointScope.adoptEarlier(mig, "realm:id:4242", "1.21.1", "mp:1.2.3.4:30000");
		eq("realm adopts its address list", "[\"R\"]", new String(Files.readAllBytes(realm), StandardCharsets.UTF_8));
		Files.write(realm, "[]".getBytes(StandardCharsets.UTF_8));
		WaypointScope.adoptEarlier(mig, "realm:id:4242", "1.21.1", "mp:5.6.7.8:30001");
		eq("realm adopts only once", "[]", new String(Files.readAllBytes(realm), StandardCharsets.UTF_8));
		// Hypixel SkyBlock still adopts the all-of-Hypixel list
		Files.write(mig.resolve(WaypointScope.fileName("mp:mc.hypixel.net")), "[\"H\"]".getBytes(StandardCharsets.UTF_8));
		WaypointScope.adoptEarlier(mig, "mp:mc.hypixel.net/skyblock/hub", "1.21.1", null);
		eq("skyblock adopts", true, Files.exists(mig.resolve(WaypointScope.fileName("mp:mc.hypixel.net/skyblock/hub"))));

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
