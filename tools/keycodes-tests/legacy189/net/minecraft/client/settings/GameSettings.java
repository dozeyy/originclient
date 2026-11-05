package net.minecraft.client.settings;

/** Test stand-in for 1.8.9's GameSettings: only the key name util/KeyCodes.name asks for. */
public class GameSettings {
	public static String getKeyDisplayString(int key) {
		return "key " + key;
	}
}
