package com.origin.client.client.mods;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Discord Status (Lunar parity): Discord Rich Presence for the "Origin Client"
 * Discord application, spoken directly over Discord's local IPC -- pure Java, no
 * native library, nothing sent anywhere but the Discord app on this PC.
 *
 * The protocol: Discord listens on a local pipe/socket named discord-ipc-0..9
 * (Windows: the named pipe \\.\pipe\discord-ipc-N; macOS/Linux: a Unix socket in
 * the temp/runtime dir). Every frame is a little-endian int32 opcode, a
 * little-endian int32 byte length, then that much UTF-8 JSON. We send a HANDSHAKE
 * (op 0, {v:1, client_id}), read Discord's READY reply, then send SET_ACTIVITY
 * commands (op 1) and read each reply so the pipe never backs up.
 *
 * Everything runs on one daemon thread. The game thread only publishes the
 * wanted status string (tick()); the thread connects when Discord is running,
 * retries every 15 s when it isn't, sends only when the status actually changed
 * (and at most once every 5 s -- Discord's own limit is 5 per 20 s), and clears
 * the status the moment the mod is switched off. Any IPC error just drops the
 * connection and retries later: Discord being closed is never an error.
 *
 * The large image is the application's Rich Presence art asset named "origin".
 *
 * 1.8.9: the details line names the version as a constant ("Minecraft 1.8.9" --
 * Minecraft.getVersion() is the LAUNCHER's profile id there, e.g. a Forge
 * profile name), the server comes from getCurrentServerData().serverIP, and
 * Gson is 2.2.4 (JsonObject.addProperty / add, both present). The IPC itself is
 * plain Java 8 and unchanged.
 */
public final class DiscordPresence {
	private static final String MOD = "discord";
	private static final String CLIENT_ID = "1558941171546464356";
	private static final long RETRY_MS = 15_000L;
	private static final long MIN_GAP_MS = 5_000L;
	private static final String MC_VERSION = "1.8.9";

	private DiscordPresence() {
	}

	// The status the game wants shown right now; null = show nothing. Written by
	// the game thread, read by the IPC thread.
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	private static final class Wanted {
		private final String details;
		private final String state;
		private final long startSec;

		private Wanted(String details, String state, long startSec) {
			this.details = details;
			this.state = state;
			this.startSec = startSec;
		}

		public String details() {
			return details;
		}

		public String state() {
			return state;
		}

		public long startSec() {
			return startSec;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Wanted)) {
				return false;
			}
			Wanted other = (Wanted) obj;
			return java.util.Objects.equals(this.details, other.details)
					&& java.util.Objects.equals(this.state, other.state)
					&& this.startSec == other.startSec;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(details, state, startSec);
		}
	}

	private static volatile Wanted wanted = null;
	private static final long LAUNCHED_SEC = System.currentTimeMillis() / 1000L;
	private static Thread thread;

	// ---- game thread ----

	static void tick(Minecraft mc) {
		wanted = Mods.on(MOD) ? build(mc) : null;
		if (wanted != null && (thread == null || !thread.isAlive())) {
			thread = new Thread(DiscordPresence::run, "Origin Discord Status");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static Wanted build(Minecraft mc) {
		String details = Mods.bool(MOD, "showVersion")
				? "Minecraft " + MC_VERSION : "Minecraft";
		String state;
		if (mc.theWorld == null || mc.thePlayer == null) {
			state = "In the menus";
		} else if (mc.isSingleplayer()) {
			state = "Singleplayer";
		} else {
			ServerData server = mc.getCurrentServerData();
			state = server != null && server.serverIP != null && !server.serverIP.trim().isEmpty()
					&& Mods.bool(MOD, "showServer") ? "Playing on " + server.serverIP.trim() : "Multiplayer";
		}
		return new Wanted(details, state, Mods.bool(MOD, "showTime") ? LAUNCHED_SEC : 0L);
	}

	// ---- IPC thread ----

	private static void run() {
		Ipc ipc = null;
		Wanted sent = null;
		boolean cleared = true;
		long lastSendAt = 0;
		long nextConnectAt = 0;
		while (true) {
			Wanted w = wanted;
			try {
				if (w == null && ipc == null) {
					return;   // switched off and nothing showing: the thread ends (tick restarts it)
				}
				if (ipc == null) {
					if (System.currentTimeMillis() < nextConnectAt) {
						Thread.sleep(500);
						continue;
					}
					nextConnectAt = System.currentTimeMillis() + RETRY_MS;
					ipc = Ipc.open();
					if (ipc == null) {
						continue;   // Discord isn't running
					}
					sent = null;
					cleared = true;
				}
				long now = System.currentTimeMillis();
				boolean changed = w == null ? !cleared : !w.equals(sent);
				if (changed && now - lastSendAt >= MIN_GAP_MS) {
					ipc.setActivity(w);
					lastSendAt = now;
					sent = w;
					cleared = w == null;
					if (w == null) {
						ipc.close();   // off: status cleared, let go of the pipe
						ipc = null;
						return;
					}
				}
				Thread.sleep(500);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				// Discord closed / restarted / sent something odd: drop the
				// connection and try again later. Never surfaces to the player.
				if (ipc != null) {
					ipc.close();
					ipc = null;
				}
				nextConnectAt = System.currentTimeMillis() + RETRY_MS;
			}
		}
	}

	/** One open IPC connection (a Windows named pipe or a Unix socket). */
	private static final class Ipc {
		private final Closeable handle;
		private final RandomAccessFile pipe;      // Windows
		private final SocketChannel socket;        // macOS / Linux

		private Ipc(RandomAccessFile pipe, SocketChannel socket) {
			this.pipe = pipe;
			this.socket = socket;
			this.handle = pipe != null ? pipe : socket;
		}

		/** Connects to the first discord-ipc-N that answers and handshakes; null if none. */
		static Ipc open() {
			for (int i = 0; i < 10; i++) {
				Ipc ipc = connect(i);
				if (ipc == null) {
					continue;
				}
				try {
					JsonObject hello = new JsonObject();
					hello.addProperty("v", 1);
					hello.addProperty("client_id", CLIENT_ID);
					ipc.write(0, hello.toString());
					ipc.read();   // READY (or an error frame -- either way the pipe is live)
					return ipc;
				} catch (Throwable t) {
					ipc.close();
				}
			}
			return null;
		}

		private static Ipc connect(int n) {
			String name = "discord-ipc-" + n;
			try {
				if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
					return new Ipc(new RandomAccessFile("\\\\.\\pipe\\" + name, "rw"), null);
				}
				for (String dir : unixDirs()) {
					File f = new File(dir, name);
					if (f.exists()) {
						SocketChannel ch = openUnix(f);
						if (ch != null) {
							return new Ipc(null, ch);
						}
					}
				}
			} catch (Throwable t) {
				// not there
			}
			return null;
		}

		// macOS / Linux: Unix-domain sockets are a Java 16 API (SocketChannel.open(
		// ProtocolFamily) + UnixDomainSocketAddress) and 1.8.9 compiles for Java 8,
		// so they're reached by reflection -- on an older runtime this is null and
		// Discord Status just stays off there. Windows (the named pipe above) is
		// what Origin ships for and needs none of this.
		private static SocketChannel openUnix(File f) {
			SocketChannel ch = null;
			try {
				Class<?> family = Class.forName("java.net.StandardProtocolFamily");
				Object unix = family.getField("UNIX").get(null);
				ch = (SocketChannel) SocketChannel.class
						.getMethod("open", Class.forName("java.net.ProtocolFamily")).invoke(null, unix);
				Object address = Class.forName("java.net.UnixDomainSocketAddress")
						.getMethod("of", java.nio.file.Path.class).invoke(null, f.toPath());
				ch.connect((java.net.SocketAddress) address);
				return ch;
			} catch (Throwable t) {
				if (ch != null) {
					try {
						ch.close();
					} catch (IOException ignored) {
						// already gone
					}
				}
				return null;
			}
		}

		private static String[] unixDirs() {
			String base = System.getenv("XDG_RUNTIME_DIR");
			if (base == null) {
				base = System.getenv("TMPDIR");
			}
			if (base == null) {
				base = "/tmp";
			}
			// Flatpak / Snap Discord put the socket in a subfolder of the runtime dir.
			return new String[]{base, base + "/app/com.discordapp.Discord", base + "/snap.discord"};
		}

		void setActivity(Wanted w) throws IOException {
			JsonObject args = new JsonObject();
			args.addProperty("pid", pid());
			if (w != null) {
				JsonObject act = new JsonObject();
				act.addProperty("details", w.details());
				act.addProperty("state", w.state());
				if (w.startSec() > 0) {
					JsonObject ts = new JsonObject();
					ts.addProperty("start", w.startSec());
					act.add("timestamps", ts);
				}
				JsonObject assets = new JsonObject();
				assets.addProperty("large_image", "origin");
				assets.addProperty("large_text", "Origin Client");
				act.add("assets", assets);
				args.add("activity", act);
			}
			JsonObject cmd = new JsonObject();
			cmd.addProperty("cmd", "SET_ACTIVITY");
			cmd.add("args", args);
			cmd.addProperty("nonce", UUID.randomUUID().toString());
			write(1, cmd.toString());
			read();   // Discord answers every command; reading keeps the pipe drained
		}

		// Java 8 has no ProcessHandle: the runtime MXBean's name is "pid@host".
		private static long pid() {
			try {
				String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
				return Long.parseLong(n.substring(0, n.indexOf('@')));
			} catch (Throwable t) {
				return 0L;
			}
		}

		private void write(int op, String json) throws IOException {
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			ByteBuffer buf = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
			buf.putInt(op).putInt(body.length).put(body);
			if (pipe != null) {
				pipe.write(buf.array());
			} else {
				buf.flip();
				while (buf.hasRemaining()) {
					socket.write(buf);
				}
			}
		}

		/** Reads one whole frame and discards it; throws if Discord closed the pipe. */
		private void read() throws IOException {
			ByteBuffer head = ByteBuffer.wrap(readFully(8)).order(ByteOrder.LITTLE_ENDIAN);
			int op = head.getInt();
			int len = head.getInt();
			if (len < 0 || len > 1 << 20) {
				throw new IOException("bad frame");
			}
			readFully(len);
			if (op == 2) {
				throw new EOFException("Discord closed the connection");   // CLOSE frame
			}
		}

		private byte[] readFully(int n) throws IOException {
			byte[] b = new byte[n];
			if (pipe != null) {
				pipe.readFully(b);
			} else {
				ByteBuffer bb = ByteBuffer.wrap(b);
				while (bb.hasRemaining()) {
					if (socket.read(bb) < 0) {
						throw new EOFException();
					}
				}
			}
			return b;
		}

		void close() {
			try {
				Objects.requireNonNull(handle).close();
			} catch (Throwable ignored) {
				// already gone
			}
		}
	}
}
