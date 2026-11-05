package org.lwjgl.opengl;

/**
 * Test stand-in for LWJGL 2's WindowsKeyboard: only the private native 1.8.9's util/KeyCodes
 * calls, MapVirtualKey(scancode, MAPVK_VSC_TO_VK), answered from a Windows layout table chosen
 * with -Dkeycodes.layout=us|azerty|qwertz|uk (KBDUS / KBDFR / KBDGR / KBDUK).
 */
final class WindowsKeyboard {
	private WindowsKeyboard() {
	}

	private static int MapVirtualKey(int scancode, int mapType) {
		String layout = System.getProperty("keycodes.layout", "us");
		int vk = override(layout, scancode);
		return vk != 0 ? vk : us(scancode);
	}

	private static int override(String layout, int sc) {
		if ("azerty".equals(layout)) {
			switch (sc) {
				case 12: return 0xDB;   // ) on VK_OEM_4
				case 16: return 'A';
				case 17: return 'Z';
				case 26: return 0xDD;   // ^ on VK_OEM_6
				case 27: return 0xBA;   // $ on VK_OEM_1
				case 30: return 'Q';
				case 39: return 'M';
				case 40: return 0xC0;   // ù on VK_OEM_3
				case 41: return 0xDE;   // ² on VK_OEM_7
				case 44: return 'W';
				case 50: return 0xBC;   // , on VK_OEM_COMMA
				case 51: return 0xBE;   // ; on VK_OEM_PERIOD
				case 52: return 0xBF;   // : on VK_OEM_2
				case 53: return 0xDF;   // ! on VK_OEM_8
				default: return 0;
			}
		}
		if ("qwertz".equals(layout)) {
			return sc == 21 ? 'Z' : sc == 44 ? 'Y' : 0;
		}
		if ("uk".equals(layout)) {
			switch (sc) {
				case 40: return 0xC0;   // ' on VK_OEM_3
				case 41: return 0xDF;   // ` on VK_OEM_8
				case 43: return 0xDE;   // # on VK_OEM_7
				case 86: return 0xDC;   // \ on VK_OEM_5
				default: return 0;
			}
		}
		return 0;
	}

	private static int us(int sc) {
		String[] rows = {"1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
		int[] firsts = {2, 16, 30, 44};
		for (int r = 0; r < rows.length; r++) {
			if (sc >= firsts[r] && sc < firsts[r] + rows[r].length()) {
				return rows[r].charAt(sc - firsts[r]);
			}
		}
		switch (sc) {
			case 12: return 0xBD;
			case 13: return 0xBB;
			case 26: return 0xDB;
			case 27: return 0xDD;
			case 39: return 0xBA;
			case 40: return 0xDE;
			case 41: return 0xC0;
			case 43: return 0xDC;
			case 51: return 0xBC;
			case 52: return 0xBE;
			case 53: return 0xBF;
			case 86: return 0xE2;   // VK_OEM_102: LWJGL 2 has no code for it on Windows
			default: return 0;
		}
	}
}
