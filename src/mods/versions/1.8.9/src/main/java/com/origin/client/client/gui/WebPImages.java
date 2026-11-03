package com.origin.client.client.gui;

import com.origin.client.OriginClient;

import javax.imageio.spi.IIORegistry;

/**
 * Lets ImageIO read WebP on 1.8.9. Modrinth serves most pack and shader thumbnails
 * (gallery images and newer project icons) as WebP, which Java 8's ImageIO cannot
 * read, so without this those rows kept the blank Origin-mark tile in the Resource
 * Pack hub while every Fabric version showed them (v1.0.87 dev-shots).
 *
 * The decoder is TwelveMonkeys' pure-Java WebP reader (the same one 1.16.5+ ship),
 * shadowed into Origin's jar and relocated under com.origin.client.shadow so another
 * mod's copy can't clash (build.gradle). Its ImageIO service file is left out of the
 * jar on purpose: the reader is registered only here, when an Origin thumbnail first
 * loads, so Minecraft's own texture loading never sees it.
 *
 * Fail-soft: if registering fails, previews behave exactly as before (PNG/JPEG only).
 */
public final class WebPImages {
	private static boolean tried;

	private WebPImages() {
	}

	/** Registers the WebP reader once; safe to call from any thread, any number of times. */
	public static synchronized void install() {
		if (tried) {
			return;
		}
		tried = true;
		try {
			IIORegistry.getDefaultInstance().registerServiceProvider(
					new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
		} catch (Throwable t) {
			OriginClient.LOGGER.warn("WebP thumbnail decoder unavailable; WebP-only previews keep the fallback tile", t);
		}
	}
}
