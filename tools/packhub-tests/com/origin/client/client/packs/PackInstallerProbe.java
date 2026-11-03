package com.origin.client.client.packs;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

// Test-only door into PackInstaller's package-private install step (no Modrinth
// round-trip: the test hands it a resolved file pointing at a local server).
public final class PackInstallerProbe {
	private PackInstallerProbe() {
	}

	public static PackInstaller.State install(PackIndex idx, ModrinthPacks.VersionFile f, Path dir, int gameFormat,
			boolean modrinthOnly) throws Exception {
		PackInstaller pi = new PackInstaller(idx);
		pi.allowAnyHost = !modrinthOnly;
		return pi.install(f, "Title", "slug", gameFormat, dir, null, new AtomicBoolean(), "test");
	}

	/** A DIRECT pick "test" pinned to (url, sha256, size); anyHost = the test server counts as its pinned host. */
	public static PackInstaller.State installDirect(PackIndex idx, String url, String sha256, long size, String fileName,
			Path dir, boolean anyHost) throws Exception {
		PackInstaller pi = new PackInstaller(idx);
		pi.allowAnyHost = anyHost;
		PackPicks.Pick p = new PackPicks.Pick("test", PackPicks.Source.DIRECT, "Test", "Creator", "", "16x", "Site",
				"https://example.org", "", java.util.List.of(PackPicks.Style.PVP), java.util.List.of("1.21.1"), "", "",
				"", "", "", url, sha256, fileName, size, java.util.List.of("example.org"), java.util.List.of());
		return pi.installDirect(p, 34, dir, new AtomicBoolean());
	}
}
