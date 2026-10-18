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
}
