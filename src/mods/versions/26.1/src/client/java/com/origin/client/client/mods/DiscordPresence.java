package com.origin.client.client.mods;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.SocketTimeoutException;
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
 * wanted status (tick(), allocation-free: one cached Wanted per state); the thread
 * connects when Discord is running, retries every 15 s when it isn't, and sends when
 * the status changed (at most once every 5 s -- Discord's own limit is 5 per 20 s).
 * While connected it re-sends the current status every 15 s as a keepalive: that
 * write is how a Discord that quit or restarted is noticed when the status itself
 * never changes. Every read and write has a 5 s deadline, so a hung Discord (or
 * something else squatting on discord-ipc-0) can never wedge the thread. Any IPC
 * error just drops the connection and retries later: Discord being closed is never
 * an error.
 *
 * The large image is the application's Rich Presence art asset named "origin".
 */
public final class DiscordPresence {
	private static final String CLIENT_ID = "1558941171546464356";
	private static final long RETRY_MS = 15_000L;
	private static final long MIN_GAP_MS = 5_000L;
	private static final long KEEPALIVE_MS = 15_000L;   // re-send while connected (4 per minute, well under 5 per 20 s)
	private static final long IO_TIMEOUT_MS = 5_000L;   // deadline for each frame read/write
	private static final long POLL_MS = 5L;

	private DiscordPresence() {
	}

	// The status the game wants shown right now. Written by the game thread,
	// read by the IPC thread.
	private record Wanted(String details, String state, long startSec) {
	}

	private static volatile Wanted wanted = null;
	private static final long LAUNCHED_SEC = System.currentTimeMillis() / 1000L;
	private static Thread thread;

	// tick() runs every client tick, so it must not allocate: details never
	// changes and there are only three states, so each Wanted is built once
	// (game thread only) and tick() just picks one.
	private static final String[] STATE_TEXT = {"In the menus", "Singleplayer", "Multiplayer"};   // never the address: there is no switch to hide it
	private static final Wanted[] BY_STATE = new Wanted[STATE_TEXT.length];
	private static String details;

	// ---- game thread ----

	static void tick(Minecraft mc) {
		int state = stateOf(mc);
		Wanted w = BY_STATE[state];
		if (w == null) {
			if (details == null) {
				details = "Minecraft " + SharedConstants.getCurrentVersion().name();
			}
			w = new Wanted(details, STATE_TEXT[state], LAUNCHED_SEC);
			BY_STATE[state] = w;
		}
		wanted = w;
		if (thread == null || !thread.isAlive()) {
			thread = new Thread(DiscordPresence::run, "Origin Discord Presence");
			thread.setDaemon(true);
			thread.start();
			com.origin.client.OriginClient.LOGGER.info("Origin: Discord presence started (always on)");
		}
	}

	private static int stateOf(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return 0;
		}
		return mc.hasSingleplayerServer() ? 1 : 2;
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
				// Send on a change (rate limited), or as a keepalive: with an unchanged
				// status the pipe is otherwise never touched, so a Discord that quit or
				// restarted would go unnoticed and presence would never come back.
				boolean changed = w != null && !w.equals(sent);
				if (w != null && (changed ? now - lastSendAt >= MIN_GAP_MS : now - lastSendAt >= KEEPALIVE_MS)) {
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
					com.origin.client.OriginClient.LOGGER.info("Origin: Discord connection lost, will retry");
				}
				nextConnectAt = System.currentTimeMillis() + RETRY_MS;
			}
		}
	}

	/** One open IPC connection (a Windows named pipe or a Unix socket). */
	private static final class Ipc {
		private final Closeable handle;
		private final RandomAccessFile pipe;      // Windows
		private final FileInputStream pipeIn;      // Windows: same handle, used only for available()
		private final SocketChannel socket;        // macOS / Linux, non-blocking

		private Ipc(RandomAccessFile pipe, SocketChannel socket) throws IOException {
			this.pipe = pipe;
			this.socket = socket;
			this.handle = pipe != null ? pipe : socket;
			// RandomAccessFile has no way to ask "is anything waiting?" and its read()
			// blocks forever. FileInputStream.available() on a pipe handle is a
			// PeekNamedPipe call, so reads can poll it against a deadline instead.
			// Shares the descriptor: closing the pipe closes this too.
			this.pipeIn = pipe != null ? new FileInputStream(pipe.getFD()) : null;
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
					RandomAccessFile raf = new RandomAccessFile("\\\\.\\pipe\\" + name, "rw");
					try {
						return new Ipc(raf, null);
					} catch (IOException e) {
						raf.close();
						throw e;
					}
				}
				for (String dir : unixDirs()) {
					File f = new File(dir, name);
					if (f.exists()) {
						SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
						try {
							// Non-blocking from the start: a full accept backlog (a stuck
							// Discord) must not park the thread in connect().
							ch.configureBlocking(false);
							long deadline = deadline();
							if (!ch.connect(UnixDomainSocketAddress.of(f.toPath()))) {
								while (!ch.finishConnect()) {
									pause(deadline);
								}
							}
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
				// A frame is a few hundred bytes and the pipe buffer is far larger;
				// replies are drained and a missed reply drops the connection, so this
				// cannot fill up and block.
				pipe.write(buf.array());
			} else {
				buf.flip();
				long deadline = deadline();
				while (buf.hasRemaining()) {
					if (socket.write(buf) == 0) {
						pause(deadline);
					}
				}
			}
		}

		/**
		 * Reads one whole frame and discards it; throws if Discord closed the pipe or
		 * the whole frame did not arrive within IO_TIMEOUT_MS.
		 */
		private void read() throws IOException {
			long deadline = deadline();
			ByteBuffer head = ByteBuffer.wrap(readFully(8, deadline)).order(ByteOrder.LITTLE_ENDIAN);
			int op = head.getInt();
			int len = head.getInt();
			if (len < 0 || len > 1 << 20) {
				throw new IOException("bad frame");
			}
			readFully(len, deadline);
			if (op == 2) {
				throw new EOFException("Discord closed the connection");   // CLOSE frame
			}
		}

		private byte[] readFully(int n, long deadline) throws IOException {
			byte[] b = new byte[n];
			if (pipe != null) {
				int off = 0;
				while (off < n) {
					int ready = pipeIn.available();   // throws once Discord has closed its end
					if (ready <= 0) {
						pause(deadline);
						continue;
					}
					int got = pipe.read(b, off, Math.min(ready, n - off));   // data is waiting: cannot block
					if (got < 0) {
						throw new EOFException();
					}
					off += got;
				}
			} else {
				ByteBuffer bb = ByteBuffer.wrap(b);
				while (bb.hasRemaining()) {
					int got = socket.read(bb);
					if (got < 0) {
						throw new EOFException();
					}
					if (got == 0) {
						pause(deadline);
					}
				}
			}
			return b;
		}

		private static long deadline() {
			return System.nanoTime() + IO_TIMEOUT_MS * 1_000_000L;
		}

		/** Sleeps one poll step, or throws once the deadline has passed. */
		private static void pause(long deadline) throws IOException {
			if (System.nanoTime() - deadline >= 0) {
				throw new SocketTimeoutException("Discord IPC did not answer within " + IO_TIMEOUT_MS + " ms");
			}
			try {
				Thread.sleep(POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("interrupted");
			}
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
