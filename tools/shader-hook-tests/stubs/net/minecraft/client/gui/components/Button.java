package net.minecraft.client.gui.components;

import net.minecraft.network.chat.Component;

/** Test stub: a button is its label. */
public class Button extends AbstractWidget {
	private final Component message;

	public Button(Component message) {
		this.message = message;
	}

	public Component getMessage() {
		return message;
	}
}
