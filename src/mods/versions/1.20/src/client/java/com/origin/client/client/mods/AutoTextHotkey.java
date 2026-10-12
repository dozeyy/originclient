package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

// Auto Text Hotkey (Lunar parity): up to SLOTS hotkeys, each sending its own
// chat line or /command when pressed. A slot is a collapsible block in the
// mod page (its switch reveals Key / Modifier / Message), the same shape the
// Particle Changer uses for its per-type blocks. Keys are polled once per
// client tick, edge-triggered, and only act with no screen open -- so typing
// in chat or a sign can never fire one.
public final class AutoTextHotkey {
	private AutoTextHotkey() {
	}

	public static final int SLOTS = 8;
	private static final String ID = "autotext";
	// Servers kick for chat spam; one send per this many ms keeps a held or
	// mashed hotkey well under any anti-spam limit.
	private static final long MIN_GAP_MS = 1000;

	private static final boolean[] WAS_DOWN = new boolean[SLOTS + 1];
	private static long lastSentAt = 0;

	/** The mod's schema: one collapsible block per slot. */
	public static ModOption[] options() {
		List<ModOption> o = new ArrayList<>();
		for (int i = 1; i <= SLOTS; i++) {
			String slot = "slot" + i;
			o.add(ModOption.toggle(slot, "Hotkey " + i, false));
			o.add(ModOption.keybind("key" + i, "Key", -1).under(slot));
			o.add(ModOption.dropdown("modifier" + i, "Modifier", "None", "Ctrl", "Shift", "Alt").under(slot)
					.tip("Hold this with the key -- e.g. Ctrl + G. None means the key on its own."));
			o.add(ModOption.text("text" + i, "Message", "").under(slot)
					.tip("Chat text, or a command starting with /. Sent exactly as typed."));
		}
		return o.toArray(new ModOption[0]);
	}

	public static void tick(Minecraft mc) {
		boolean active = Mods.on(ID) && mc.player != null && mc.getConnection() != null && mc.screen == null;
		for (int i = 1; i <= SLOTS; i++) {
			boolean down = active && Mods.bool(ID, "slot" + i) && pressed(i);
			if (down && !WAS_DOWN[i]) {
				// Same character rules as typed chat (a stray § would get you kicked).
				send(mc, SharedConstants.filterText(Mods.text(ID, "text" + i)).trim());
			}
			WAS_DOWN[i] = down;
		}
	}

	// The slot's key plus exactly its modifier. "None" requires no Ctrl/Shift/Alt,
	// so a plain G and a Ctrl + G slot can share a key without both firing.
	private static boolean pressed(int i) {
		int key = Mods.keyCode(ID, "key" + i);
		if (key <= 0 || !OriginClientMod.isRawKeyDown(key)) {
			return false;
		}
		boolean ctrl = held(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
		boolean shift = held(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
		boolean alt = held(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
		return switch (Mods.mode(ID, "modifier" + i)) {
			case "Ctrl" -> ctrl;
			case "Shift" -> shift;
			case "Alt" -> alt;
			default -> !ctrl && !shift && !alt;
		};
	}

	private static boolean held(int left, int right) {
		return OriginClientMod.isRawKeyDown(left) || OriginClientMod.isRawKeyDown(right);
	}

	/** Sends a configured line: "/..." as a command, anything else as chat. */
	public static void send(Minecraft mc, String msg) {
		ClientPacketListener conn = mc.getConnection();
		long now = System.currentTimeMillis();
		if (conn == null || msg.isEmpty() || now - lastSentAt < MIN_GAP_MS) {
			return;
		}
		lastSentAt = now;
		if (msg.startsWith("/")) {
			if (msg.length() > 1) {
				conn.sendCommand(msg.substring(1));
			}
		} else {
			conn.sendChat(msg);
		}
	}
}
