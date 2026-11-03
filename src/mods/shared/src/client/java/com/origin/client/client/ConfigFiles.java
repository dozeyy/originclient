package com.origin.client.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What every Origin config does with a file it cannot read.
 *
 * Each loader falls back to defaults on a parse error, and its next save would then
 * overwrite the damaged file -- the only copy of the player's settings -- for good.
 * So the file is first moved aside to "<name>.corrupt" (one per config; a newer one
 * replaces the older). That name never matches Settings Sync's originclient*.json,
 * so the damaged copy stays on this PC and is never pushed to another version.
 *
 * Pure Java 8 (no Minecraft, no Gson): the same file ships in every module, pre-1.20
 * and 1.8.9 included, and tools/ingame-guard-tests proves it.
 */
public final class ConfigFiles {

	public static final String CORRUPT_SUFFIX = ".corrupt";

	private ConfigFiles() {
	}

	/**
	 * Moves a config that failed to load to "<name>.corrupt". Call it after the
	 * reader is closed (Windows cannot move an open file). Never throws.
	 *
	 * @return the backup's path, or null when there was no file or it could not be moved
	 */
	public static Path setAside(Path broken) {
		try {
			if (broken == null || !Files.isRegularFile(broken)) {
				return null;
			}
			Path backup = broken.resolveSibling(broken.getFileName() + CORRUPT_SUFFIX);
			Files.move(broken, backup, StandardCopyOption.REPLACE_EXISTING);
			return backup;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
