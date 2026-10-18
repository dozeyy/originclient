package com.origin.client.client.mods;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Discord Rich Presence for the "Origin Client" Discord application (Discord shows
 * "Playing Origin Client"). Always on for every player: it is not a mod and has no
 * switch in the mod menu. Spoken directly over Discord's local IPC -- pure Java, no
 * native library, nothing sent anywhere but the Discord app on this PC.
 *
 * What it shows: details = "Minecraft <version>"; state = "In the menus",
 * "Singleplayer" or "Multiplayer" (the server address is never shown); elapsed
 * time since launch. An old saved "discord" config block is simply never read.
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
 * retries every 15 s when it isn't, and sends only when the status actually changed
 * (and at most once every 5 s -- Discord's own limit is 5 per 20 s). Any IPC error just drops the
 * connection and retries later: Discord being closed is never an error.
 *
 * The large image is the application's Rich Presence art asset named "origin".
 */
public final class DiscordPresence {
	private static final String CLIENT_ID = "1558941171546464356";
	private static final long RETRY_MS = 15_000L;
	private static final long MIN_GAP_MS = 5_000L;

	private DiscordPresence() {
	}

	// The status the game wants shown right now. Written by the game thread,
	// read by the IPC thread.
	private record Wanted(String details, String state, long startSec) {
	}

	private static volatile Wanted wanted = null;
	private static final long LAUNCHED_SEC = System.currentTimeMillis() / 1000L;
	private static Thread thread;

	// ---- game thread ----

	static void tick(Minecraft mc) {
		wanted = build(mc);
		if (thread == null || !thread.isAlive()) {
			thread = new Thread(DiscordPresence::run, "Origin Discord Presence");
			thread.setDaemon(true);
			thread.start();
			com.origin.client.OriginClient.LOGGER.info("Origin: Discord presence started (always on)");
		}
	}

	private static Wanted build(Minecraft mc) {
		String details = "Minecraft " + SharedConstants.getCurrentVersion().getName();
		String state;
		if (mc.level == null || mc.player == null) {
			state = "In the menus";
		} else if (mc.hasSingleplayerServer()) {
			state = "Singleplayer";
		} else {
			state = "Multiplayer";   // never the address: there is no switch to hide it
		}
		return new Wanted(details, state, LAUNCHED_SEC);
	}

	// ---- IPC thread ----

	private static void run() {
		Ipc ipc = null;
		Wanted sent = null;
		boolean announced = false;
		long lastSendAt = 0;
		long nextConnectAt = 0;
		while (true) {
			Wanted w = wanted;
			try {
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
				}
				long now = System.currentTimeMillis();
				if (w != null && !w.equals(sent) && now - lastSendAt >= MIN_GAP_MS) {
					ipc.setActivity(w);
					lastSendAt = now;
					sent = w;
					if (!announced) {
						announced = true;   // once per run, so a closed Discord never spams the log
						com.origin.client.OriginClient.LOGGER.info("Origin: Discord presence is live ({} / {})", w.details(), w.state());
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
						SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
						try {
							ch.connect(UnixDomainSocketAddress.of(f.toPath()));
							return new Ipc(null, ch);
						} catch (IOException e) {
							ch.close();
						}
					}
				}
			} catch (Throwable t) {
				// not there
			}
			return null;
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
			args.addProperty("pid", ProcessHandle.current().pid());
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
