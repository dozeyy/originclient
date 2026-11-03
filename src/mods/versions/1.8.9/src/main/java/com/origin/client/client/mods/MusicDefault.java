package com.origin.client.client.mods;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.settings.GameSettings;

/**
 * 1.8.9 only: Music starts OFF (Will, 2026-11-03: "have music on 1.8.9 always
 * turned to off by default").
 *
 * Once per game folder (each account has its own): the first start with this
 * build sets the Music slider to 0 and saves options.txt, then drops a marker
 * file in that folder's config/. After that the player's own slider is never
 * touched again, so turning music back up sticks. NOT the Mods config's meta
 * block: Settings Sync copies originclient-mods.json (meta included) into every
 * instance, so a flag there would mark a brand-new account's folder as done
 * before its music was ever turned off.
 *
 * Runs from OriginClientMod.init (FMLInitializationEvent). Minecraft.startGame
 * has the GameSettings and SoundHandler by then (javap: mcSoundHandler is set
 * before FMLClientHandler.finishMinecraftLoading) and the MusicTicker has not
 * ticked yet, so the title-screen music never starts. setSoundLevel also goes
 * through SoundHandler -> SoundManager.setSoundCategoryVolume, which stops any
 * MUSIC sound already playing at volume 0, and a later playSound at volume 0
 * is skipped by SoundManager itself ("volume was zero").
 */
public final class MusicDefault {
	private static final String MARKER = "config/originclient-music-off-189.done";

	private MusicDefault() {
	}

	public static void applyOnce(Minecraft mc) {
		try {
			GameSettings settings = mc == null ? null : mc.gameSettings;
			if (settings == null || mc.mcDataDir == null) {
				return;   // try again next start
			}
			File marker = new File(mc.mcDataDir, MARKER);
			if (marker.exists()) {
				return;
			}
			settings.setSoundLevel(SoundCategory.MUSIC, 0.0F);
			settings.saveOptions();
			marker.getParentFile().mkdirs();
			marker.createNewFile();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: couldn't turn music off by default", t);
		}
	}
}
