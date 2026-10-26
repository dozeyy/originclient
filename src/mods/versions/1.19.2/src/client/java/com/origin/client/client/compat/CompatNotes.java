package com.origin.client.client.compat;

import com.origin.client.client.mods.ModOption;
import java.util.List;

/**
 * Puts CompatGates' "Off: needs a supported Sodium" note in front of the tooltip of every Mod Menu
 * option whose feature is switched off (the option key is the feature id), so the player sees why a
 * toggle does nothing on their Sodium / Iris. These speed-ups are options inside the Performance tab,
 * not mod cards, so StepAside's card/page note slot (which carries the card-level notes, e.g. the JEI
 * switch) has no place for them. Separate from CompatGates because ModOption must not load during
 * Mixin's config phase.
 */
public final class CompatNotes {
	private CompatNotes() { }

	/** Annotates {@code options} in place and returns it (so a static list initializer can wrap it). */
	public static List<ModOption> mark(List<ModOption> options) {
		try {
			for (ModOption o : options) {
				String note = CompatGates.note(o.key);
				if (note != null && (o.tooltip == null || !o.tooltip.startsWith(note))) {
					o.tooltip = o.tooltip == null || o.tooltip.isEmpty() ? note + "." : note + ". " + o.tooltip;
				}
			}
		} catch (Throwable ignored) {
			// A note is cosmetic; never let it break the settings list.
		}
		return options;
	}
}
