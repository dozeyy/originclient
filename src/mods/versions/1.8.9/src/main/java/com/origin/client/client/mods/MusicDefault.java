package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.settings.GameSettings;

/**
 * 1.8.9 only: Music starts OFF (Will, 2026-11-03: "have music on 1.8.9 always
 * turned to off by default").
 *
 * Once per game folder (each account has its own): the first start with this
 * build sets the Music slider to 0 and saves options.txt, then records it in
 * the Mods config's meta block -- the same store the optionVersion.* one-time
 * migrations use (Mods.upgradeOptions). After that the player's own slider is
 * never touched again, so turning music back up sticks.
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
	private static final String META_KEY = "oneTime.musicOff189";

	private MusicDefault() {
	}

	public static void applyOnce(Minecraft mc) {
		try {
			if (Mods.metaBool(META_KEY, false)) {
				return;
			}
			GameSettings settings = mc == null ? null : mc.gameSettings;
			if (settings == null) {
				return;   // try again next start
			}
			settings.setSoundLevel(SoundCategory.MUSIC, 0.0F);
			settings.saveOptions();
			Mods.setMetaBool(META_KEY, true);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: couldn't turn music off by default", t);
		}
	}
}
