import com.origin.client.client.shaders.ShaderCompat;
import com.origin.client.client.shaders.ShaderCompat.Pack;
import com.origin.client.client.shaders.ShaderCompat.Verdict;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// Standalone check for the 1.8.9 shader allowlist (ShaderCompat has no Minecraft
// types). Run: powershell -File tools/check-shader-allowlist.ps1
// Exit 0 = every assertion held; 1 = at least one failed (each printed).
public class CheckShaderAllowlist {
	static int failures = 0;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL: " + what);
		}
	}

	static File zip(File dir, String name, String... entryAndText) throws Exception {
		File f = new File(dir, name);
		try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
			for (int i = 0; i < entryAndText.length; i += 2) {
				z.putNextEntry(new ZipEntry(entryAndText[i]));
				z.write(entryAndText[i + 1].getBytes(StandardCharsets.ISO_8859_1));
				z.closeEntry();
			}
		}
		return f;
	}

	public static void main(String[] args) throws Exception {
		String v = "1.8.9";

		// ---- table shape ----
		check(ShaderCompat.ALLOWED.length > 0, "allowlist is not empty");
		Set<String> ids = new HashSet<>(), files = new HashSet<>();
		for (Pack p : ShaderCompat.ALLOWED) {
			check(ids.add(p.id), "unique id " + p.id);
			check(files.add(p.fileName.toLowerCase()), "unique file " + p.fileName);
			check(p.fileName.endsWith(".zip"), p.id + " is a .zip");
			check(p.url.startsWith("https://cdn.modrinth.com/data/"), p.id + " url is the Modrinth CDN");
			check(p.url.contains("/versions/" + p.versionId + "/"), p.id + " url carries its pinned version id");
			check(p.sha256.matches("[0-9a-f]{64}"), p.id + " has a 64-hex SHA-256");
			check(p.size > 0, p.id + " has a size");
			check(ShaderCompat.classify(p.fileName, 0, v) == Verdict.OK, p.id + " classifies OK");
			check(ShaderCompat.warningFor(p.fileName, 0, v) == null, p.id + " raises no warning");
		}

		// ---- what 1.8.9 is offered / what other versions are offered ----
		check(ShaderCompat.offeredFor("1.8.9") == ShaderCompat.ALLOWED, "1.8.9 gets the allowlist");
		check(ShaderCompat.offeredFor("1.21.1").length == 0, "this table offers nothing for 1.21.1");

		// ---- the pack from the 2026-10-20 log, and its relatives, are never installable on 1.8.9 ----
		String[] modern = {"ComplementaryReimagined_r5.9.3.zip", "ComplementaryUnbound_r5.9.3.zip",
				"Spooklementary_v2.0.4.zip", "Photon-Shaders-v1.2.zip", "Solas Shader.zip", "Mellow v3.4.1a.zip",
				"Hysteria-Shaders-Universal-v1.1.0.zip", "PixelCraftShaders_r1.2.zip", "Snowimagined-1.1.zip",
				"DrDestens MinecraftShaders v2.1.5.zip", "Stracciatella Shaders v1.6.4.1 by DrDesten.zip"};
		for (String m : modern) {
			check(!ShaderCompat.isAllowedFile(m, v), m + " is not installable on 1.8.9");
			check(ShaderCompat.classify(m, 0, v) == Verdict.INCOMPATIBLE, m + " is flagged by name");
			check(ShaderCompat.WARNING.equals(ShaderCompat.warningFor(m, 0, v)), m + " gets the warning");
		}
		for (Pack p : ShaderCompat.ALLOWED) {
			String l = p.name.toLowerCase();
			check(!l.contains("complementary") && !l.contains("spooklementary") && !l.contains("mellow"),
					p.id + " is not a modern pack");
		}

		// ---- modern versions are untouched ----
		check(ShaderCompat.isAllowedFile("ComplementaryReimagined_r5.9.3.zip", "1.21.1"), "1.21.1 may still install Complementary");
		check(ShaderCompat.classify("ComplementaryReimagined_r5.9.3.zip", 400, "1.21.1") == Verdict.UNKNOWN, "no verdict on 1.21.1");
		check(ShaderCompat.warningFor("ComplementaryReimagined_r5.9.3.zip", 400, "1.21.1") == null, "no warning on 1.21.1");

		// ---- the user's own packs: warn only with evidence, never guess ----
		check(ShaderCompat.classify("MyHomemadePack.zip", 0, v) == Verdict.UNKNOWN, "unknown pack, unreadable = no warning");
		check(ShaderCompat.classify("MyHomemadePack.zip", 120, v) == Verdict.UNKNOWN, "unknown pack, GLSL 120 = no warning");
		check(ShaderCompat.classify("MyHomemadePack.zip", 130, v) == Verdict.UNKNOWN, "unknown pack, GLSL 130 = no warning");
		check(ShaderCompat.classify("MyHomemadePack.zip", 150, v) == Verdict.INCOMPATIBLE, "unknown pack, GLSL 150 = warn");
		check(ShaderCompat.classify("BSL_v10.1.8.zip", 0, v) == Verdict.UNKNOWN, "a pack we don't list or reject gets no warning");
		check(ShaderCompat.classify(null, 400, v) == Verdict.UNKNOWN, "null name");
		check(ShaderCompat.classify("  ", 400, v) == Verdict.UNKNOWN, "blank name");
		check(ShaderCompat.classify("sildur's vibrant shaders v2.02 LITE", 0, v) == Verdict.OK, "name match ignores .zip and case");

		// ---- reading packs never writes, and finds the highest core #version ----
		File tmp = Files.createTempDirectory("shadercheck").toFile();
		File modernZip = zip(tmp, "modern.zip",
				"shaders/composite.fsh", "#version 120\nvoid main(){}",
				"shaders/final.fsh", "#version 400 compatibility\nvoid main(){}",
				"shaders/lib/inc.fsh", "#version 450\n");                      // sub-folder = include, ignored
		check(ShaderCompat.probeMaxVersion(modernZip) == 400, "zip probe finds 400 (and ignores lib/ includes)");
		File oldZip = zip(tmp, "old.zip",
				"shaders/gbuffers_basic.vsh", "#version 120\n",
				"shaders/physics_ocean.fsh", "#version 330 compatibility\n"); // Physics Mod file, ignored
		check(ShaderCompat.probeMaxVersion(oldZip) == 120, "zip probe ignores physics_ocean*");
		check(ShaderCompat.probeMaxVersion(new File(tmp, "missing.zip")) == 0, "missing pack probes to 0");
		File dir = new File(tmp, "folderpack");
		new File(dir, "shaders").mkdirs();
		Files.write(new File(dir, "shaders/final.fsh").toPath(), "#version 330\n".getBytes(StandardCharsets.ISO_8859_1));
		check(ShaderCompat.probeMaxVersion(dir) == 330, "folder probe finds 330");
		long before = modernZip.length();
		ShaderCompat.probeMaxVersion(modernZip);
		check(modernZip.exists() && modernZip.length() == before, "probe leaves the pack untouched");
		check(ShaderCompat.classify("homemade.zip", ShaderCompat.probeMaxVersion(modernZip), v) == Verdict.INCOMPATIBLE,
				"end to end: an unknown #version 400 pack is flagged");

		if (failures == 0) {
			System.out.println("PASS: shader allowlist checks (" + ShaderCompat.ALLOWED.length + " packs)");
		}
		System.exit(failures == 0 ? 0 : 1);
	}
}
