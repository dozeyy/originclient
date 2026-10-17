package com.origin.client.client.theme;

import com.origin.client.OriginClient;
import com.origin.client.client.cosmetics.CosmeticCatalog;
import com.origin.client.client.cosmetics.CosmeticDef;
import com.origin.client.client.cosmetics.CosmeticsService;
import com.origin.client.client.cosmetics.ThemeColors;
import com.origin.client.client.gui.OriginDeck;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Objects;

/**
 * Applies the equipped THEME cosmetic (FORMAT.md "Themes") to Origin's own menus.
 * Local only: it follows YOUR look ({@link CosmeticsService#myLook}), nobody
 * else's. Once a client tick it compares the worn theme id with the one applied
 * and, when they differ, pushes the eight colours into {@link OriginTheme} and
 * {@link OriginDeck} (or resets both to today's colours for "no theme"). Menus
 * read those values every frame, so a change repaints at once.
 *
 * A theme whose item.json can't be read is treated as not worn (default colours,
 * one log line); a theme id the catalog doesn't know yet is retried each tick,
 * since the catalog may still be loading. Fail-soft: any error leaves the colours
 * as they were. Until the cosmetics service has answered, the default shows.
 */
public final class ThemeManager {
	private ThemeManager() {
	}

	/** The id applied right now; null = Origin's own colours. */
	private static String applied = null;
	private static boolean warned = false;

	/** Hooks the client tick. Called once from OriginClientMod. */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
	}

	private static void tick() {
		try {
			String want = CosmeticsService.myLook().theme();
			if (Objects.equals(want, applied)) {
				return;
			}
			if (want == null) {
				OriginTheme.resetTheme();
				OriginDeck.resetTheme();
				applied = null;
				return;
			}
			if (CosmeticCatalog.item(want) == null) {
				return;   // catalog not loaded yet (or an id this build doesn't know): keep what's shown, retry
			}
			CosmeticDef def = CosmeticCatalog.def(want);
			if (def == null || def.theme == null) {
				OriginTheme.resetTheme();
				OriginDeck.resetTheme();
				applied = want;   // unusable theme: default colours, and don't retry every tick
				return;
			}
			ThemeColors c = def.theme;
			OriginTheme.applyTheme(c.bg(), c.card(), c.line(), c.text(), c.dim(), c.acc(), c.accHi(), c.onAcc());
			OriginDeck.applyTheme(c.bg(), c.card(), c.line(), c.text(), c.dim(), c.acc(), c.accHi(), c.onAcc());
			applied = want;
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: couldn't apply the menu theme", t);
			}
		}
	}
}
