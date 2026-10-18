package com.origin.client.client.packs;

import java.util.ArrayList;
import java.util.List;

// How the hub's edits land in Minecraft's enabled-pack list without disturbing
// anything it doesn't manage. The hub only orders packs from resourcepacks/
// ("file/..." ids). Built-ins the player enabled (Programmer Art, High
// Contrast, mod resources) keep their exact positions, and pinned packs
// (required or fixed-position, like vanilla's default) are never moved or removed.
// The file packs fill the slots file packs already held, in the new order. Any
// extra newly enabled ones go on top, which is where Minecraft puts a pack you
// enable. Pure logic; unit-tested in tools/packhub-tests.
public final class PackOrder {
	private PackOrder() {
	}

	/** One enabled pack, top priority first. pinned = required or fixed position. */
	public record Slot(String id, boolean pinned) {
	}

	public static boolean managed(String id) {
		return id.startsWith("file/");
	}

	/**
	 * current = Minecraft's enabled list, top first; files = the enabled file packs
	 * the hub wants, top first. Returns the new enabled list, top first.
	 */
	public static List<String> apply(List<Slot> current, List<String> files) {
		int slots = 0;
		for (Slot s : current) {
			if (!s.pinned() && managed(s.id())) {
				slots++;
			}
		}
		List<String> out = new ArrayList<>();
		int extra = Math.max(0, files.size() - slots);
		int next = 0;
		boolean extrasPlaced = extra == 0;
		for (Slot s : current) {
			if (!extrasPlaced && !s.pinned()) {
				// extras go above everything except packs pinned to the top
				for (; next < extra; next++) {
					out.add(files.get(next));
				}
				extrasPlaced = true;
			}
			if (!s.pinned() && managed(s.id())) {
				if (next < files.size()) {
					out.add(files.get(next++));
				}
			} else {
				out.add(s.id());
			}
		}
		// nothing unpinned to sit above (empty list, or only pinned packs)
		List<String> tail = new ArrayList<>();
		for (; next < files.size(); next++) {
			tail.add(files.get(next));
		}
		if (!tail.isEmpty()) {
			int at = 0;
			while (at < out.size() && current.size() > at && current.get(at).pinned()
					&& !isBottomPinned(current, at)) {
				at++;
			}
			out.addAll(at, tail);
		}
		return out;
	}

	// A pinned pack below every unpinned one (vanilla's default sits at the bottom).
	private static boolean isBottomPinned(List<Slot> current, int at) {
		for (int i = at; i < current.size(); i++) {
			if (!current.get(i).pinned()) {
				return false;
			}
		}
		return true;
	}
}
