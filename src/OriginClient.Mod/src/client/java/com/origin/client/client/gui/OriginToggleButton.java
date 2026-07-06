package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

// Flat, monochrome toggle row — deliberately ignores vanilla Button's own
// 9-slice texture and draws its own rect + pill so it matches Origin's
// Deskify look instead of vanilla Minecraft chrome.
public class OriginToggleButton extends Button {
	private static final int COLOR_ROW_BG = 0xFF1A1A1A;
	private static final int COLOR_ROW_BG_HOVER = 0xFF232323;
	private static final int COLOR_TEXT = 0xFFE0E0E0;
	private static final int COLOR_TEXT_MUTED = 0xFF888888;
	private static final int COLOR_PILL_ON = 0xFFE0E0E0;
	private static final int COLOR_PILL_OFF = 0xFF444444;
	private static final int COLOR_TEXT_ON_ACCENT = 0xFF121212;

	private final String label;
	private final BooleanSupplier stateSupplier;

	public OriginToggleButton(int x, int y, int width, int height, String label, BooleanSupplier stateSupplier, OnPress onPress) {
		super(x, y, width, height, Component.literal(label), onPress, Button.DEFAULT_NARRATION);
		this.label = label;
		this.stateSupplier = stateSupplier;
	}

	@Override
	protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		boolean on = stateSupplier.getAsBoolean();
		boolean hovered = mouseX >= getX() && mouseX < getX() + getWidth() && mouseY >= getY() && mouseY < getY() + getHeight();

		int bg = hovered ? COLOR_ROW_BG_HOVER : COLOR_ROW_BG;
		guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);

		var font = Minecraft.getInstance().font;
		int textY = getY() + (getHeight() - 8) / 2;
		guiGraphics.drawString(font, label, getX() + 12, textY, COLOR_TEXT);

		int pillWidth = 36;
		int pillHeight = 16;
		int pillX = getX() + getWidth() - pillWidth - 12;
		int pillY = getY() + (getHeight() - pillHeight) / 2;
		guiGraphics.fill(pillX, pillY, pillX + pillWidth, pillY + pillHeight, on ? COLOR_PILL_ON : COLOR_PILL_OFF);

		String state = on ? "ON" : "OFF";
		int stateColor = on ? COLOR_TEXT_ON_ACCENT : COLOR_TEXT_MUTED;
		int stateWidth = font.width(state);
		guiGraphics.drawString(font, state, pillX + (pillWidth - stateWidth) / 2, pillY + (pillHeight - 8) / 2, stateColor);
	}
}
