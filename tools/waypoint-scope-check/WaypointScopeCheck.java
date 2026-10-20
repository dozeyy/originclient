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

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
