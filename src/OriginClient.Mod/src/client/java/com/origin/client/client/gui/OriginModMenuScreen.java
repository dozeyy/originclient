package com.origin.client.client.gui;

import com.origin.client.client.OriginClientMod;
import com.origin.client.client.OriginConfig;
import com.origin.client.client.OriginFeatures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// The Right Shift overlay — a clean, non-pausing list of toggles for every
// Origin Client feature mod. Deliberately flat/minimal, matching the
// launcher's own Deskify visual language rather than vanilla Minecraft GUI.
public class OriginModMenuScreen extends Screen {
	private static final int PANEL_WIDTH = 260;
	private static final int ROW_HEIGHT = 28;
	private static final int ROW_SPACING = 8;
	private static final int ROW_COUNT = 6;

	private final Screen parent;

	public OriginModMenuScreen(Screen parent) {
		super(Component.literal("Mod Settings"));
		this.parent = parent;
	}

	private int panelTop() {
		return this.height / 2 - (ROW_HEIGHT * ROW_COUNT + ROW_SPACING * (ROW_COUNT - 1)) / 2;
	}

	@Override
	protected void init() {
		OriginFeatures features = OriginClientMod.FEATURES;
		int centerX = this.width / 2;
		int y = panelTop();

		addToggleRow(centerX, y, "Zoom", () -> features.zoomEnabled, v -> features.zoomEnabled = v);
		y += ROW_HEIGHT + ROW_SPACING;
		addToggleRow(centerX, y, "Freelook", () -> features.freelookEnabled, v -> features.freelookEnabled = v);
		y += ROW_HEIGHT + ROW_SPACING;
		addToggleRow(centerX, y, "HUD Info", () -> features.hudInfoEnabled, v -> features.hudInfoEnabled = v);
		y += ROW_HEIGHT + ROW_SPACING;
		addToggleRow(centerX, y, "Toggle Sprint", () -> features.toggleSprintEnabled, v -> features.toggleSprintEnabled = v);
		y += ROW_HEIGHT + ROW_SPACING;
		addToggleRow(centerX, y, "Toggle Sneak", () -> features.toggleSneakEnabled, v -> features.toggleSneakEnabled = v);
		y += ROW_HEIGHT + ROW_SPACING;
		addToggleRow(centerX, y, "Fullbright", () -> features.fullbrightEnabled, v -> features.fullbrightEnabled = v);
	}

	private void addToggleRow(int centerX, int y, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		OriginToggleButton button = new OriginToggleButton(
				centerX - PANEL_WIDTH / 2, y, PANEL_WIDTH, ROW_HEIGHT,
				label, getter,
				b -> {
					setter.accept(!getter.getAsBoolean());
					OriginConfig.save(OriginClientMod.FEATURES);
				});
		this.addRenderableWidget(button);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		guiGraphics.fill(0, 0, this.width, this.height, 0xD0121212);
		guiGraphics.drawCenteredString(this.font, "MOD SETTINGS", this.width / 2, panelTop() - 24, 0xE0E0E0);
		super.render(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		OriginConfig.save(OriginClientMod.FEATURES);
		this.minecraft.setScreen(parent);
	}
}
