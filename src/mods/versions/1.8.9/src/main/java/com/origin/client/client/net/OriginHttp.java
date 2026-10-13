package com.origin.client.client.net;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * The ONE way Origin opens an HTTP(S) connection on 1.8.9.
 *
 * WHY: 1.8.9 runs on Mojang's `jre-legacy` -- Java 8, possibly as old as 8u51 --
 * whose CA store predates Let's Encrypt (ISRG) and Google Trust Services roots.
 * Origin's services sit behind Cloudflare (GTS / Let's Encrypt chains), as do
 * Hypixel's API and most modern hosts, so a plain HttpsURLConnection fails there
 * with a PKIX "unable to find valid certification path" error. This class builds
 * one SSLContext that trusts the JDK's own roots PLUS the bundled public roots in
 * assets/originclient/certs (ISRG Root X1/X2, GTS Root R1-R4, DigiCert Global
 * Root G2, Baltimore CyberTrust), pinned to TLS 1.2 (old 8u builds default some
 * sockets lower; every host Origin talks to speaks 1.2).
 *
 * The trust is applied per connection (setSSLSocketFactory), never as the JVM
 * default, so Minecraft's and other mods' connections are untouched.
 *
 * Porting the 1.16.5 networking code is a one-line swap:
 *   (HttpURLConnection) new URL(url).openConnection()  ->  OriginHttp.open(url)
 * Timeouts and headers stay at each call site, exactly as in 1.16.5.
 * Fail-soft: if the bundled roots cannot be loaded, the JDK defaults are used
 * (the connection may then fail the way it always would have -- never worse).
 */
public final class OriginHttp {
	private static final String[] ROOTS = {
			"isrg_root_x1.pem",
			"isrg_root_x2.pem",
			"gts_root_r1.pem",
			"gts_root_r2.pem",
			"gts_root_r3.pem",
			"gts_root_r4.pem",
			"digicert_global_root_g2.pem",
			"baltimore_cybertrust_root.pem",
	};

	private static volatile SSLSocketFactory factory;
	private static volatile boolean tried = false;

	private OriginHttp() {
	}

	/** Opens a connection to `url` with Origin's trust store (HTTPS) -- not yet connected. */
	public static HttpURLConnection open(String url) throws IOException {
		return open(new URL(url));
	}

	/** Opens a connection to `url` with Origin's trust store (HTTPS) -- not yet connected. */
	public static HttpURLConnection open(URL url) throws IOException {
		URLConnection c = url.openConnection();
		if (c instanceof HttpsURLConnection) {
			SSLSocketFactory f = socketFactory();
			if (f != null) {
				((HttpsURLConnection) c).setSSLSocketFactory(f);
			}
		}
		return (HttpURLConnection) c;
	}

	/**
	 * Convenience GET: the body as UTF-8, or null on any failure / non-2xx.
	 * Timeouts in ms; `userAgent` may be null.
	 */
	public static String get(String url, int timeoutMs, String userAgent) {
		HttpURLConnection c = null;
		try {
			c = open(url);
			c.setConnectTimeout(timeoutMs);
			c.setReadTimeout(timeoutMs);
			if (userAgent != null) {
				c.setRequestProperty("User-Agent", userAgent);
			}
			int code = c.getResponseCode();
			if (code < 200 || code >= 300) {
				return null;
			}
			return readBody(c.getInputStream());
		} catch (Throwable t) {
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	/** Reads a response stream (input or error stream) fully as UTF-8; "" for null. */
	public static String readBody(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} finally {
			in.close();
		}
	}

	/** The merged-trust TLS 1.2 socket factory (built once), or null if it could not be built. */
	public static SSLSocketFactory socketFactory() {
		if (!tried) {
			synchronized (OriginHttp.class) {
				if (!tried) {
					factory = build();
					tried = true;
				}
			}
		}
		return factory;
	}

	private static SSLSocketFactory build() {
		try {
			KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
			ks.load(null, null);
			// 1) the JDK's own roots, copied in so nothing that works today breaks
			TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			jdk.init((KeyStore) null);
			int n = 0;
			for (TrustManager tm : jdk.getTrustManagers()) {
				if (tm instanceof X509TrustManager) {
					for (X509Certificate cert : ((X509TrustManager) tm).getAcceptedIssuers()) {
						ks.setCertificateEntry("jdk-" + (n++), cert);
					}
				}
			}
			// 2) the bundled public roots the old JRE lacks
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			for (String name : ROOTS) {
				try (InputStream in = OriginHttp.class.getResourceAsStream("/assets/originclient/certs/" + name)) {
					if (in == null) {
						continue;
					}
					Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(in);
					int i = 0;
					for (java.security.cert.Certificate cert : certs) {
						ks.setCertificateEntry("origin-" + name + "-" + (i++), cert);
					}
				} catch (Throwable t) {
					log("bundled root " + name + " skipped: " + t);
				}
			}
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(ks);
			SSLContext ctx;
			try {
				ctx = SSLContext.getInstance("TLSv1.2");
			} catch (Throwable t) {
				ctx = SSLContext.getInstance("TLS");
			}
			ctx.init(null, tmf.getTrustManagers(), null);
			return ctx.getSocketFactory();
		} catch (Throwable t) {
			log("trust store build failed; using JDK defaults: " + t);
			return null;
		}
	}

	private static void log(String msg) {
		try {
			com.origin.client.OriginClient.LOGGER.warn("[OriginHttp] " + msg);
		} catch (Throwable ignored) {
			// logging must never break networking
		}
	}
}
