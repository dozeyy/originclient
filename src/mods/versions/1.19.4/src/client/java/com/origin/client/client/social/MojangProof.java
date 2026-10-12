package com.origin.client.client.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * Builds the body of Origin's sign-in (POST /v1/auth, docs/SOCIAL.md → "Identity v2").
 *
 * Why it exists: Mojang answers every request from Cloudflare Workers with 403, so the
 * service can't ask Mojang who we are (v1's joinServer / hasJoined). Instead the game
 * brings two things Mojang already signs for every player, and the service checks
 * Mojang's signatures offline with Mojang's published keys:
 *  1. the PLAYER CERTIFICATE (the chat-signing key pair Minecraft 1.19+ uses; every
 *     version can fetch it): proves this uuid owns the key pair until keyExpiresAt;
 *  2. the SIGNED PROFILE (the textures property with Mojang's signature): proves the
 *     uuid's current name.
 * Then we sign "origin-auth|<uuid>|game|<now ms>" with the certificate's private key,
 * which proves we hold that key right now (the service accepts each signature once).
 *
 * Rules: the Minecraft access token only goes to Mojang (Bearer on the certificate
 * call), never to Origin; the private key stays in memory, never on disk or in logs.
 * The certificate is cached until Mojang says to refresh it (refreshedAfter).
 *
 * One file, identical in every pre-1.20 module (1.16.5 runs on Java 8, so: plain
 * HttpURLConnection, no records/var/switch expressions, Gson via fromJson because
 * Gson 2.8.0 has no static JsonParser.parseString). Call it off the game thread only.
 */
public final class MojangProof {
	private MojangProof() {
	}

	/** Mojang refused a certificate (401/403): online play is off for this Microsoft account. */
	public static final class NoOnlinePlay extends Exception {
	}

	/** Mojang has no profile for this uuid: an offline / non-Microsoft account. */
	public static final class NoProfile extends Exception {
	}

	private static final String CERT_URL = "https://api.minecraftservices.com/player/certificates";
	private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
	/** Fetch a new certificate this long before it expires, whatever refreshedAfter says. */
	private static final long EXPIRY_MARGIN_MS = 10L * 60L * 1000L;
	private static final Gson GSON = new Gson();

	// ---- the cached certificate (memory only; guarded by the class lock) ----
	private static String certUuid = null;
	private static PrivateKey privateKey = null;
	/** base64 X.509 SubjectPublicKeyInfo DER (the PEM body, re-encoded without line breaks) */
	private static String publicKeyDer = null;
	/** Mojang's publicKeySignatureV2 over (uuid, keyExpiresAt, publicKey), base64 */
	private static String keySignature = null;
	private static long keyExpiresAt = 0;
	private static long refreshAt = 0;

	/**
	 * The /v1/auth body for this player. `uuid` may be dashed or not; `client` is
	 * "game". Throws NoOnlinePlay / NoProfile for the two account problems, IOException
	 * when Mojang can't be reached or answers oddly (retry later).
	 */
	public static synchronized JsonObject authBody(String accessToken, String uuid, String name, String client)
			throws NoOnlinePlay, NoProfile, IOException {
		String id = uuid.replace("-", "").toLowerCase(Locale.ROOT);
		try {
			certificate(accessToken, id);
			JsonObject textures = signedTextures(id);
			// The service compares `name` with the name inside Mojang's signed value,
			// exactly; take it from there so a fresh rename can't make them differ.
			String profileName = profileName(textures.get("value").getAsString());
			long at = System.currentTimeMillis();
			Signature s = Signature.getInstance("SHA256withRSA");
			s.initSign(privateKey);
			s.update(("origin-auth|" + id + "|" + client + "|" + at).getBytes(StandardCharsets.UTF_8));

			JsonObject b = new JsonObject();
			b.addProperty("client", client);
			b.addProperty("uuid", id);
			b.addProperty("name", profileName != null ? profileName : name);
			b.addProperty("at", at);
			b.addProperty("sig", Base64.getEncoder().encodeToString(s.sign()));
			b.addProperty("publicKey", publicKeyDer);
			b.addProperty("keyExpiresAt", keyExpiresAt);
			b.addProperty("keySignature", keySignature);
			b.add("textures", textures);
			return b;
		} catch (GeneralSecurityException | RuntimeException e) {
			// a key we can't read or a malformed answer: drop it and fetch afresh next time
			forget();
			throw new IOException("Mojang proof failed", e);
		}
	}

	/** Drops the cached certificate (the service refused our proof; fetch a new one next time). */
	public static synchronized void forget() {
		certUuid = null;
		privateKey = null;
		publicKeyDer = null;
		keySignature = null;
		keyExpiresAt = 0;
		refreshAt = 0;
	}

	// ------------------------------------------------------------- certificate

	private static void certificate(String accessToken, String id)
			throws NoOnlinePlay, IOException, GeneralSecurityException {
		long now = System.currentTimeMillis();
		if (privateKey != null && id.equals(certUuid) && now < refreshAt) {
			return;
		}
		forget();
		Answer a = request("POST", CERT_URL, accessToken);
		if (a.code == 401 || a.code == 403) {
			throw new NoOnlinePlay();
		}
		if (a.code != 200 || a.json == null) {
			throw new IOException("certificate HTTP " + a.code);
		}
		JsonObject pair = a.json.getAsJsonObject("keyPair");
		// "-----BEGIN RSA PRIVATE KEY-----" headers, but the body is PKCS#8 (Minecraft's
		// own Crypt strips the headers and reads it the same way); the public body is
		// X.509 SubjectPublicKeyInfo, which is exactly what the service wants.
		byte[] priv = pemBody(pair.get("privateKey").getAsString());
		byte[] pub = pemBody(pair.get("publicKey").getAsString());
		PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(priv));
		// Mojang signs expiresAt as epoch milliseconds; the ISO string has microseconds,
		// toEpochMilli() truncates them just as Minecraft does.
		long expires = Instant.parse(a.json.get("expiresAt").getAsString()).toEpochMilli();
		long refresh = expires - EXPIRY_MARGIN_MS;
		JsonElement ra = a.json.get("refreshedAfter");
		if (ra != null && !ra.isJsonNull()) {
			refresh = Math.min(refresh, Instant.parse(ra.getAsString()).toEpochMilli());
		}
		String sig = a.json.get("publicKeySignatureV2").getAsString().replaceAll("\\s", "");
		if (expires <= now || sig.isEmpty()) {
			throw new IOException("certificate already expired / unsigned");
		}
		certUuid = id;
		privateKey = key;
		publicKeyDer = Base64.getEncoder().encodeToString(pub);
		keySignature = sig;
		keyExpiresAt = expires;
		// never re-use one past its margin, but don't refetch more than once a minute
		refreshAt = Math.max(now + 60_000L, refresh);
	}

	/** The base64 between the PEM header and footer, decoded. */
	private static byte[] pemBody(String pem) {
		String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(body);
	}

	// ------------------------------------------------------------- signed profile

	/** { value, signature } of the profile's "textures" property, as Mojang signed it. */
	private static JsonObject signedTextures(String id) throws NoProfile, IOException {
		Answer a = request("GET", PROFILE_URL + id + "?unsigned=false", null);
		if (a.code == 204 || a.code == 404) {
			throw new NoProfile();
		}
		if (a.code != 200 || a.json == null) {
			throw new IOException("profile HTTP " + a.code);
		}
		JsonArray props = a.json.getAsJsonArray("properties");
		if (props != null) {
			for (JsonElement e : props) {
				JsonObject p = e.getAsJsonObject();
				if ("textures".equals(p.get("name").getAsString()) && p.has("signature")) {
					JsonObject t = new JsonObject();
					t.addProperty("value", p.get("value").getAsString());
					t.addProperty("signature", p.get("signature").getAsString());
					return t;
				}
			}
		}
		throw new IOException("profile has no signed textures");
	}

	/** profileName inside the base64 textures value, or null. */
	private static String profileName(String value) {
		try {
			String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
			JsonElement n = GSON.fromJson(json, JsonObject.class).get("profileName");
			return n == null || n.isJsonNull() ? null : n.getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ------------------------------------------------------------- transport

	private static final class Answer {
		final int code;
		final JsonObject json;

		Answer(int code, JsonObject json) {
			this.code = code;
			this.json = json;
		}
	}

	private static Answer request(String method, String url, String bearer) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		try {
			c.setConnectTimeout(8000);
			c.setReadTimeout(10000);
			c.setRequestMethod(method);
			c.setRequestProperty("User-Agent", "OriginClient-social");
			c.setRequestProperty("Accept", "application/json");
			if (bearer != null) {
				c.setRequestProperty("Authorization", "Bearer " + bearer);
			}
			if ("POST".equals(method)) {
				// the certificate call takes an empty body
				c.setRequestProperty("Content-Type", "application/json");
				c.setDoOutput(true);
				try (OutputStream out = c.getOutputStream()) {
					out.write(new byte[0]);
				}
			}
			int code = c.getResponseCode();
			JsonObject json = null;
			try {
				json = GSON.fromJson(body(c, code), JsonObject.class);
			} catch (RuntimeException ignored) {
				// no / non-JSON body: the status code is enough
			}
			return new Answer(code, json);
		} finally {
			c.disconnect();
		}
	}

	private static String body(HttpURLConnection c, int code) throws IOException {
		InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
		if (in == null) {
			return "";
		}
		try (InputStream s = in) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			int n;
			while ((n = s.read(b)) > 0) {
				buf.write(b, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
