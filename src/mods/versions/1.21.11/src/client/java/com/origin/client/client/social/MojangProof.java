package com.origin.client.client.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Sign-in v2 (docs/SOCIAL.md "Identity v2"): proves to the Origin service who is
 * playing, using two things Mojang already signs for every player, so the
 * service can check them offline. (v1 asked Mojang from the service, and Mojang
 * refuses every request from Cloudflare Workers.)
 *
 *  1. the PLAYER CERTIFICATE (the key pair Minecraft uses to sign chat):
 *     POST api.minecraftservices.com/player/certificates with the game's own
 *     Minecraft access token. Mojang signs its public key + expiry for this uuid.
 *  2. the SIGNED PROFILE: sessionserver's textures property, which Mojang signs
 *     and which carries the uuid + exact name.
 * Then it signs "origin-auth|uuid|game|now" with the certificate's private key
 * (proof we hold it) and builds the /v1/auth body.
 *
 * Rules: the access token only ever goes to Mojang; the private key lives in
 * memory only (never on disk, never sent). Blocking network calls - call from a
 * background thread only (SocialClient's worker). This file is identical in every
 * module from 1.20 up: it uses nothing but the JDK and Gson.
 */
public final class MojangProof {
	private MojangProof() {
	}

	/** The account can't be proven: offline/dev account, or Mojang won't issue it a certificate. */
	public static final class Refused extends Exception {
		/** true = Mojang refused the certificate (multiplayer disabled on the Microsoft account). */
		public final boolean noOnlinePlay;

		Refused(boolean noOnlinePlay) {
			super(noOnlinePlay ? "certificate refused" : "no Microsoft account");
			this.noOnlinePlay = noOnlinePlay;
		}
	}

	private static final String CERTIFICATES = "https://api.minecraftservices.com/player/certificates";
	private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";
	/** The service accepts "game" or "launcher"; this mod is always the game. */
	private static final String CLIENT = "game";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** The certificate, kept in memory until Mojang says to refresh it. */
	private record Certificate(String uuid, PrivateKey privateKey, String publicKeyDer64, long expiresAt,
							   String keySignature, long refreshAt) {
	}

	private static Certificate cached = null;

	/** Drop the cached certificate (the service rejected a proof made with it). */
	public static synchronized void forget() {
		cached = null;
	}

	/**
	 * The /v1/auth body for this player. Throws {@link Refused} when the account
	 * can't be proven, IOException when Mojang can't be reached / answers oddly.
	 */
	public static synchronized JsonObject authBody(UUID profileId, String name, String accessToken)
			throws Refused, IOException {
		// Dev / offline launches carry a placeholder token Mojang can never accept;
		// skip the round trips (and Mojang's rate limit) for those.
		if (profileId == null || accessToken == null || accessToken.length() < 20 || "FabricMC".equals(accessToken)) {
			throw new Refused(false);
		}
		String uuid = profileId.toString().replace("-", "").toLowerCase(Locale.ROOT);
		Certificate cert = certificate(uuid, accessToken);
		JsonObject profile = getJson(PROFILE + uuid + "?unsigned=false");
		JsonObject textures = null;
		if (profile.get("properties") != null && profile.get("properties").isJsonArray()) {
			for (JsonElement e : profile.getAsJsonArray("properties")) {
				if (e.isJsonObject() && "textures".equals(str(e.getAsJsonObject(), "name"))) {
					textures = e.getAsJsonObject();
				}
			}
		}
		String value = str(textures, "value"), signature = str(textures, "signature");
		if (value == null || signature == null) {
			throw new IOException("Mojang profile had no signed textures");
		}
		// The name exactly as the signed profile spells it: the service compares the two exactly.
		String mojangName = null;
		try {
			JsonObject signedProfile = JsonParser.parseString(
					new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
			mojangName = str(signedProfile, "profileName");
		} catch (Exception ignored) {
			// fall back to the profile's own name field below
		}
		if (mojangName == null) {
			mojangName = str(profile, "name");
		}

		long at = System.currentTimeMillis();
		String signed = "origin-auth|" + uuid + "|" + CLIENT + "|" + at;
		String sig;
		try {
			Signature s = Signature.getInstance("SHA256withRSA");
			s.initSign(cert.privateKey());
			s.update(signed.getBytes(StandardCharsets.UTF_8));
			sig = Base64.getEncoder().encodeToString(s.sign());
		} catch (Exception e) {
			cached = null;
			throw new IOException("couldn't sign with the player certificate", e);
		}

		JsonObject t = new JsonObject();
		t.addProperty("value", value);
		t.addProperty("signature", signature);
		JsonObject b = new JsonObject();
		b.addProperty("client", CLIENT);
		b.addProperty("uuid", uuid);
		b.addProperty("name", mojangName != null ? mojangName : name);
		b.addProperty("at", at);
		b.addProperty("sig", sig);
		b.addProperty("publicKey", cert.publicKeyDer64());
		b.addProperty("keyExpiresAt", cert.expiresAt());
		b.addProperty("keySignature", cert.keySignature());
		b.add("textures", t);
		return b;
	}

	private static Certificate certificate(String uuid, String accessToken) throws Refused, IOException {
		long now = System.currentTimeMillis();
		Certificate c = cached;
		if (c != null && c.uuid().equals(uuid) && now < c.refreshAt()) {
			return c;
		}
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(CERTIFICATES))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build());
		if (r.statusCode() == 401 || r.statusCode() == 403) {
			// Mojang won't issue a certificate: multiplayer is disabled on this
			// Microsoft account (child account / Xbox privacy settings).
			throw new Refused(true);
		}
		if (r.statusCode() != 200) {
			throw new IOException("certificates HTTP " + r.statusCode());
		}
		try {
			JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
			JsonObject kp = o.getAsJsonObject("keyPair");
			// Both keys come as base64 inside "-----BEGIN RSA ... KEY-----" headers, but
			// the bodies are PKCS#8 (private) and X.509 SubjectPublicKeyInfo (public),
			// exactly what Minecraft's own Crypt reads after stripping the headers.
			byte[] priv = pemBody(str(kp, "privateKey"));
			byte[] pub = pemBody(str(kp, "publicKey"));
			PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(priv));
			long expiresAt = instant(str(o, "expiresAt"));
			String keySig = str(o, "publicKeySignatureV2");
			if (keySig == null || keySig.isEmpty() || expiresAt <= now) {
				throw new IOException("certificate response incomplete");
			}
			String refreshed = str(o, "refreshedAfter");
			long refreshAt = refreshed != null ? instant(refreshed) : expiresAt;
			// never hold it right up to expiry: the service wants keyExpiresAt in the future
			refreshAt = Math.min(refreshAt, expiresAt - 10 * 60_000L);
			c = new Certificate(uuid, key, Base64.getEncoder().encodeToString(pub), expiresAt,
					keySig.replaceAll("\\s", ""), refreshAt);
			cached = c;
			return c;
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("couldn't read the player certificate", e);
		}
	}

	private static JsonObject getJson(String url) throws IOException {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("Accept", "application/json")
				.GET()
				.build());
		if (r.statusCode() != 200) {
			throw new IOException("profile HTTP " + r.statusCode());
		}
		try {
			return JsonParser.parseString(r.body()).getAsJsonObject();
		} catch (Exception e) {
			throw new IOException("profile response unreadable", e);
		}
	}

	private static HttpResponse<String> send(HttpRequest req) throws IOException {
		try {
			return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}

	/** The DER bytes inside a PEM block (headers and line breaks stripped). */
	private static byte[] pemBody(String pem) throws IOException {
		if (pem == null) {
			throw new IOException("missing key");
		}
		String b64 = pem.replaceAll("-----[^-]*-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(b64);
	}

	/** Mojang's ISO-8601 time ("2026-10-14T21:13:22.123456Z") -> epoch ms. */
	private static long instant(String s) throws IOException {
		if (s == null) {
			throw new IOException("missing time");
		}
		try {
			return Instant.parse(s).toEpochMilli();
		} catch (Exception e) {
			try {
				return OffsetDateTime.parse(s).toInstant().toEpochMilli();
			} catch (Exception e2) {
				throw new IOException("bad time " + s, e2);
			}
		}
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e == null || e.isJsonNull() ? null : e.getAsString();
		} catch (Exception x) {
			return null;
		}
	}
}
