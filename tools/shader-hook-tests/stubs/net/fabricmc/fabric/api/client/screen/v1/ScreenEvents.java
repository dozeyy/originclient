package net.fabricmc.fabric.api.client.screen.v1;

import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/** Test stub: afterTick listeners collect in TICKS; tick() runs them as one screen tick. */
public final class ScreenEvents {
	public static final List<AfterTick> TICKS = new ArrayList<AfterTick>();

	public interface AfterTick {
		void afterTick(Screen screen);
	}

	public static final class Event {
		public void register(AfterTick listener) {
			TICKS.add(listener);
		}
	}

	public static Event afterTick(Screen screen) {
		return new Event();
	}

	public static void tick(Screen screen) {
		for (AfterTick t : TICKS) {
			t.afterTick(screen);
		}
	}
}
