package com.origin.client.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

// Java 8 has no InputStream.readAllBytes() (added in Java 9). This reads a whole
// stream into a byte[] via a buffer loop — the drop-in the render/config/shader
// code uses when slurping bundled JSON and preview images on 1.8.9.
public final class IoCompat {
	private IoCompat() {
	}

	public static byte[] readAllBytes(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	// gson 2.8.0 (bundled with MC 1.16.5) has no JsonObject.keySet() — that
	// arrived in gson 2.8.1. Derive the key set from entrySet() so the config/
	// UI JSON walks the same way it does on newer versions.
	public static java.util.Set<String> keys(com.google.gson.JsonObject obj) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
			out.add(e.getKey());
		}
		return out;
	}

	// ---- gson 2.2.4 gaps (1.8.9's bundled Gson): one obvious stand-in each ----

	/** JsonObject.size() (gson 2.8.1+). */
	public static int size(com.google.gson.JsonObject obj) {
		return obj == null ? 0 : obj.entrySet().size();
	}

	/** JsonParser.parseString(s) (gson 2.8.6+): `new JsonParser().parse(s)`. */
	public static com.google.gson.JsonElement parse(String s) {
		return new com.google.gson.JsonParser().parse(s);
	}

	/** JsonElement.deepCopy() (public since gson 2.8.2): a re-parse of its own JSON. */
	public static com.google.gson.JsonElement deepCopy(com.google.gson.JsonElement e) {
		return e == null ? null : new com.google.gson.JsonParser().parse(e.toString());
	}

	/** JsonArray.isEmpty() (gson 2.8.7+). */
	public static boolean isEmpty(com.google.gson.JsonArray arr) {
		return arr == null || arr.size() == 0;
	}

	/** JsonArray.remove(int) (gson 2.3+): a copy without that index (2.2.4 arrays only grow). */
	public static com.google.gson.JsonArray without(com.google.gson.JsonArray arr, int index) {
		com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		for (int i = 0; arr != null && i < arr.size(); i++) {
			if (i != index) {
				out.add(arr.get(i));
			}
		}
		return out;
	}
}
